/*
 *    Copyright 2025 Nacho Brito
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package es.nachobrito.vulcanostore.storage;

import es.nachobrito.vulcanostore.VulcanoConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Unit and integration tests asserting Multi-Segment rollover thresholds and recovery.
 */
public class StorageEngineTest {

    @Test
    public void testSegmentRollover(@TempDir Path tempDir) throws IOException {
        // Configure StorageEngine with segmentSize = 50 bytes to force rollovers with small records
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(50)
                .maxKeyMemoryMb(4)
                .build();

        try (StorageEngine engine = new StorageEngine(config)) {
            byte[] key1 = "k1".getBytes();
            byte[] value1 = "v1".getBytes();
            BinaryRecord record1 = new BinaryRecord(System.currentTimeMillis(), key1, value1, 0);

            // Write 1st record: fits in 50 bytes (serialized size: 22 + 2 + 2 = 26 bytes)
            StorageEngine.WriteResult res1 = engine.write(record1);
            assertEquals(1, res1.fileId());
            assertEquals(0, res1.valueOffset());

            // Write 2nd record: size 26 bytes. Combined: 26 + 26 = 52 bytes (exceeds 50 bytes threshold)
            // This MUST trigger active segment rollover to file ID 2!
            BinaryRecord record2 = new BinaryRecord(System.currentTimeMillis(), "k2".getBytes(), "v2".getBytes(), 0);
            StorageEngine.WriteResult res2 = engine.write(record2);
            assertEquals(2, res2.fileId());
            assertEquals(0, res2.valueOffset());

            // Read records back from their respective segment coordinates
            BinaryRecord read1 = engine.read(res1.fileId(), res1.valueOffset());
            assertArrayEquals(key1, read1.key());
            assertArrayEquals(value1, read1.value());

            BinaryRecord read2 = engine.read(res2.fileId(), res2.valueOffset());
            assertArrayEquals("k2".getBytes(), read2.key());
            assertArrayEquals("v2".getBytes(), read2.value());

            // Verify both segment log files physically exist in our temporary directory
            assertTrue(Files.exists(tempDir.resolve("00000001.data")));
            assertTrue(Files.exists(tempDir.resolve("00000002.data")));
        }
    }

    @Test
    public void testStartupCrashRecovery(@TempDir Path tempDir) throws IOException {
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(1024 * 1024) // 1MB segment size
                .maxKeyMemoryMb(4)
                .build();

        // 1. Initialize engine, write sequential records including a deletion tombstone, and close
        try (StorageEngine engine = new StorageEngine(config)) {
            BinaryRecord rec1 = new BinaryRecord(1000L, "keyA".getBytes(), "valueA".getBytes(), 0);
            BinaryRecord rec2 = new BinaryRecord(1001L, "keyB".getBytes(), "valueB".getBytes(), 0);
            BinaryRecord rec3 = new BinaryRecord(1002L, "keyA".getBytes(), null, 0); // Tombstone delete keyA

            engine.write(rec1);
            engine.write(rec2);
            engine.write(rec3);
        }

        // 2. Open a fresh index and fresh engine instance on the same dbPath
        try (OffHeapKeyDir index = new OffHeapKeyDir(4);
             StorageEngine engine = new StorageEngine(config)) {

            // Reconstruct the index from the storage engine files
            engine.recover(index);

            // Assert index state after recovery
            // keyA was deleted by tombstone rec3, so it should not exist in the index
            assertNull(index.get("keyA".getBytes()));

            // keyB should exist and point to the correct record coordinates
            OffHeapKeyDir.Slot slotB = index.get("keyB".getBytes());
            assertNotNull(slotB);

            // Read record back from storage using recovered coordinates
            BinaryRecord readB = engine.read(slotB.fileId(), slotB.valueOffset());
            assertArrayEquals("keyB".getBytes(), readB.key());
            assertArrayEquals("valueB".getBytes(), readB.value());
            assertEquals(1001L, readB.timestamp());
        }
    }

    @Test
    public void testHintFileGeneration(@TempDir Path tempDir) throws IOException {
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(50) // small segment size to force rollover
                .maxKeyMemoryMb(4)
                .build();

        try (StorageEngine engine = new StorageEngine(config)) {
            BinaryRecord record1 = new BinaryRecord(1000L, "k1".getBytes(), "value1".getBytes(), 0);
            engine.write(record1);

            // Write 2nd record: serialized size 30. Exceeds 50 threshold, triggering segment 1 rollover
            BinaryRecord record2 = new BinaryRecord(1001L, "k2".getBytes(), "value2".getBytes(), 0);
            engine.write(record2);

            // Assert that "00000001.hint" physically exists in the database folder
            Path hintPath = tempDir.resolve("00000001.hint");
            assertTrue(Files.exists(hintPath));

            // Verify content of the hint file
            // Format: Timestamp(8B) + Offset(8B) + Value Size(4B) + Key Size(2B) + Key(Var)
            // Header is 22 bytes. Total size: 22 + key length (2) = 24 bytes.
            assertEquals(24, Files.size(hintPath));

            byte[] hintBytes = Files.readAllBytes(hintPath);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(hintBytes);

            assertEquals(1000L, buffer.getLong());      // Timestamp
            assertEquals(0L, buffer.getLong());         // Offset in .data segment
            assertEquals(6, buffer.getInt());           // Value size
            assertEquals(2, buffer.getShort());         // Key size
            byte[] keyBytes = new byte[2];
            buffer.get(keyBytes);
            assertArrayEquals("k1".getBytes(), keyBytes); // Key
        }

        // Assert that the active segment also gets a hint file when the database is closed
        Path activeHintPath = tempDir.resolve("00000002.hint");
        assertTrue(Files.exists(activeHintPath));
    }

    @Test
    public void testHintDrivenStartup(@TempDir Path tempDir) throws IOException {
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(1024 * 1024)
                .maxKeyMemoryMb(4)
                .build();

        // 1. Write some keys and close the engine to produce data and hint files
        try (StorageEngine engine = new StorageEngine(config)) {
            BinaryRecord rec1 = new BinaryRecord(1000L, "hintKeyA".getBytes(), "hintValA".getBytes(), 0);
            BinaryRecord rec2 = new BinaryRecord(1001L, "hintKeyB".getBytes(), "hintValB".getBytes(), 0);

            engine.write(rec1);
            engine.write(rec2);
        }

        // Assert that data file exists
        Path dataPath = tempDir.resolve("00000001.data");
        assertTrue(Files.exists(dataPath));

        // Note: For now, hintPath might not exist because hint files are not yet implemented.
        // During the TDD RED phase of Task 6.2, we must prepare the test but expect failure.
        // We will mock/write a dummy hint file or simply delete the data file and expect recovery to fail.
        // Since no hint file is written yet, deleting the .data file will mean recovery returns nothing.
        Files.delete(dataPath);
        assertFalse(Files.exists(dataPath));

        // 2. Open a fresh index and recover
        try (OffHeapKeyDir index = new OffHeapKeyDir(4);
             StorageEngine engine = new StorageEngine(config)) {

            // Reconstruct the index solely from hint files
            engine.recover(index);

            // Assert that the index was successfully and fully populated
            OffHeapKeyDir.Slot slotA = index.get("hintKeyA".getBytes());
            assertNotNull(slotA);
            assertEquals(1, slotA.fileId());
            assertEquals(8, slotA.valueSize());
            assertEquals(1000L, slotA.timestamp());

            OffHeapKeyDir.Slot slotB = index.get("hintKeyB".getBytes());
            assertNotNull(slotB);
            assertEquals(1, slotB.fileId());
            assertEquals(8, slotB.valueSize());
            assertEquals(1001L, slotB.timestamp());
        }
    }
}
