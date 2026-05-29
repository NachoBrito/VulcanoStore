package es.nachobrito.vulcanodb.storage;

import es.nachobrito.vulcanodb.VulcanoConfig;
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
                .expectedKeys(100)
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
                .expectedKeys(100)
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
        try (OffHeapKeyDir index = new OffHeapKeyDir(100);
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
}
