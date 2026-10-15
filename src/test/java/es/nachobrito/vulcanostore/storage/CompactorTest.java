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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Unit and integration tests for background log compaction and atomic swapping.
 */
public class CompactorTest {

    @Test
    public void testLogCompaction(@TempDir Path tempDir) throws Exception {
        // 1. Configure StorageEngine with segmentSize = 65 so rec1 and rec2 fit in segment 1
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(65)
                .maxKeyMemoryMb(4)
                .build();

        try (OffHeapKeyDir index = new OffHeapKeyDir(4);
             StorageEngine engine = new StorageEngine(config)) {

            // We write a few records to produce multiple inactive segments
            // Segment 1: k1 -> v1, k2 -> v2 (stale)
            BinaryRecord rec1 = new BinaryRecord(1000L, "k1".getBytes(), "v1".getBytes(), 0);
            BinaryRecord rec2 = new BinaryRecord(1001L, "k2".getBytes(), "v2".getBytes(), 0);

            StorageEngine.WriteResult res1 = engine.write(rec1);
            index.put(rec1.key(), res1.fileId(), res1.valueSize(), res1.valueOffset(), res1.keyOffset(), res1.timestamp());

            StorageEngine.WriteResult res2 = engine.write(rec2);
            index.put(rec2.key(), res2.fileId(), res2.valueSize(), res2.valueOffset(), res2.keyOffset(), res2.timestamp());

            // Segment 2: k2 -> v2_updated (active), k3 -> v3 (stale)
            BinaryRecord rec3 = new BinaryRecord(1002L, "k2".getBytes(), "v2_updated".getBytes(), 0);
            BinaryRecord rec4 = new BinaryRecord(1003L, "k3".getBytes(), "v3".getBytes(), 0);

            StorageEngine.WriteResult res3 = engine.write(rec3);
            index.put(rec3.key(), res3.fileId(), res3.valueSize(), res3.valueOffset(), res3.keyOffset(), res3.timestamp());

            StorageEngine.WriteResult res4 = engine.write(rec4);
            index.put(rec4.key(), res4.fileId(), res4.valueSize(), res4.valueOffset(), res4.keyOffset(), res4.timestamp());

            // Segment 3: k3 -> null (tombstone, active deletion), triggers another rollover
            BinaryRecord rec5 = new BinaryRecord(1004L, "k3".getBytes(), null, 0);
            StorageEngine.WriteResult res5 = engine.write(rec5);
            index.remove(rec5.key()); // deleted from index

            // At this point:
            // - Segments 1 and 2 are inactive.
            // - Segment 3 is the active segment.
            // Inactive data files: "00000001.data", "00000002.data" and their hints
            assertTrue(Files.exists(tempDir.resolve("00000001.data")));
            assertTrue(Files.exists(tempDir.resolve("00000002.data")));

            // Run compaction synchronously
            try (Compactor compactor = new Compactor(config, engine, index)) {
                compactor.compact();
            }

            // Assertions after compaction:
            // 1. Stale segments (00000001 and 00000002) should be physically deleted from disk
            assertFalse(Files.exists(tempDir.resolve("00000001.data")));
            assertFalse(Files.exists(tempDir.resolve("00000002.data")));
            assertFalse(Files.exists(tempDir.resolve("00000001.hint")));
            assertFalse(Files.exists(tempDir.resolve("00000002.hint")));

            // 2. Active keys (k1 and k2) must still exist and return correct values from updated coordinates
            OffHeapKeyDir.Slot slot1 = index.get("k1".getBytes());
            assertNotNull(slot1);
            BinaryRecord read1 = engine.read(slot1.fileId(), slot1.valueOffset());
            assertArrayEquals("v1".getBytes(), read1.value());

            OffHeapKeyDir.Slot slot2 = index.get("k2".getBytes());
            assertNotNull(slot2);
            BinaryRecord read2 = engine.read(slot2.fileId(), slot2.valueOffset());
            assertArrayEquals("v2_updated".getBytes(), read2.value());

            // 3. Purged key (k3) must be completely gone from index
            assertNull(index.get("k3".getBytes()));
        }
    }

    @Test
    public void testAtomicIndexSwapping(@TempDir Path tempDir) throws Exception {
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(1024 * 1024)
                .maxKeyMemoryMb(4)
                .build();

        try (OffHeapKeyDir index = new OffHeapKeyDir(4);
             StorageEngine engine = new StorageEngine(config)) {

            // Write two valid records representing alternative valid states for "key"
            BinaryRecord recA = new BinaryRecord(1000L, "key".getBytes(), "valueA".getBytes(), 0);
            BinaryRecord recB = new BinaryRecord(1001L, "key".getBytes(), "valueB".getBytes(), 0);

            StorageEngine.WriteResult resA = engine.write(recA);
            StorageEngine.WriteResult resB = engine.write(recB);

            // Initially point the index to state A
            index.put("key".getBytes(), resA.fileId(), resA.valueSize(), resA.valueOffset(), resA.keyOffset(), resA.timestamp());

            int threadCount = 4;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount + 1);
            AtomicBoolean running = new AtomicBoolean(true);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger errorCount = new AtomicInteger(0);

            // Spawn concurrent reader threads verifying coordinate consistency
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        while (running.get()) {
                            // Read record. Because lookups and swaps must be synchronized atomically,
                            // we should NEVER get a corrupt intermediate state.
                            // We synchronize lookup + read to ensure thread safety under concurrent modifications.
                            byte[] value;
                            synchronized (index) {
                                OffHeapKeyDir.Slot slot = index.get("key".getBytes());
                                if (slot != null) {
                                    BinaryRecord record = engine.read(slot.fileId(), slot.valueOffset());
                                    value = record.value();
                                } else {
                                    value = null;
                                }
                            }

                            if (value != null) {
                                String valStr = new String(value);
                                if (!valStr.equals("valueA") && !valStr.equals("valueB")) {
                                    errorCount.incrementAndGet(); // Found corrupted intermediate coordinate combo!
                                }
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                });
            }

            // Spawn swap thread toggling the index coordinate mapping atomically
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean toggle = true;
                    while (running.get()) {
                        synchronized (index) {
                            if (toggle) {
                                index.put("key".getBytes(), resB.fileId(), resB.valueSize(), resB.valueOffset(), resB.keyOffset(), resB.timestamp());
                            } else {
                                index.put("key".getBytes(), resA.fileId(), resA.valueSize(), resA.valueOffset(), resA.keyOffset(), resA.timestamp());
                            }
                        }
                        toggle = !toggle;
                        Thread.yield();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                }
            });

            // Start all threads simultaneously
            startLatch.countDown();
            Thread.sleep(200); // Let them contend for a bit
            running.set(false);
            executor.shutdown();

            assertEquals(0, errorCount.get(), "Concurrency swap resulted in non-atomic coordinate reading!");
        }
    }

    @Test
    public void testMultiSegmentCompactionWithActiveDataGreaterThanSegmentSize(@TempDir Path tempDir) throws Exception {
        // Configure StorageEngine with small segment size (4 KB) so 1,000 records span multiple segments
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(4096)
                .maxKeyMemoryMb(4)
                .build();

        try (OffHeapKeyDir index = new OffHeapKeyDir(4);
             StorageEngine engine = new StorageEngine(config)) {

            int totalRecords = 1000;
            for (int i = 0; i < totalRecords; i++) {
                byte[] key = String.format("key-%05d", i).getBytes();
                byte[] val = String.format("value-payload-for-%05d", i).getBytes();
                BinaryRecord record = new BinaryRecord(System.currentTimeMillis(), key, val, 0);
                StorageEngine.WriteResult res = engine.write(record);
                index.put(key, res.fileId(), res.valueSize(), res.valueOffset(), res.keyOffset(), res.timestamp());
            }

            int activeFileIdBefore = engine.getActiveFileId();
            // Verify multiple segments were indeed generated
            assertTrue(activeFileIdBefore > 5, "Expected multiple segments across 1,000 records, but got activeFileId: " + activeFileIdBefore);

            long segmentCountBefore;
            try (var s = Files.list(tempDir)) {
                segmentCountBefore = s.filter(p -> p.getFileName().toString().endsWith(".data")).count();
            }

            // Inactive segments currently have 100% active records (0 dead records)
            try (Compactor compactor = new Compactor(config, engine, index)) {
                compactor.compact();
            }

            int activeFileIdAfter = engine.getActiveFileId();
            long segmentCountAfter;
            try (var s = Files.list(tempDir)) {
                segmentCountAfter = s.filter(p -> p.getFileName().toString().endsWith(".data")).count();
            }

            // Segments with 0 dead records must be skipped: activeFileId must NOT advance and segment count must not blow up
            assertEquals(activeFileIdBefore, activeFileIdAfter, "Compacting 100% active segments should skip them and not trigger new segment rollovers");
            assertEquals(segmentCountBefore, segmentCountAfter, "Segment count should remain unchanged when 0 dead records exist");

            // 100% of all 1,000 keys and their exact values must remain intact and retrievable
            for (int i = 0; i < totalRecords; i++) {
                byte[] key = String.format("key-%05d", i).getBytes();
                byte[] expectedVal = String.format("value-payload-for-%05d", i).getBytes();

                OffHeapKeyDir.Slot slot = index.get(key);
                assertNotNull(slot, "Slot must exist for key " + i);
                BinaryRecord record = engine.read(slot.fileId(), slot.valueOffset());
                assertNotNull(record, "Record must be readable for key " + i);
                assertArrayEquals(expectedVal, record.value(), "Value must match for key " + i);
            }
        }
    }

    @Test
    public void testConcurrentIngestionAndCompactionStress(@TempDir Path tempDir) throws Exception {
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(8192)
                .maxKeyMemoryMb(4)
                .build();

        int writerThreads = 4;
        int recordsPerThread = 250;
        ExecutorService writerPool = Executors.newFixedThreadPool(writerThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger writeErrors = new AtomicInteger(0);

        try (es.nachobrito.vulcanostore.VulcanoStore store = new es.nachobrito.vulcanostore.VulcanoStoreImpl(config)) {
            for (int t = 0; t < writerThreads; t++) {
                final int threadId = t;
                writerPool.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < recordsPerThread; i++) {
                            String key = "thread-" + threadId + "-key-" + i;
                            String val = "thread-" + threadId + "-val-" + i;
                            store.put(key, val);
                            if (i % 5 == 0) {
                                // Overwrite to create dead records that compactor can clean
                                store.put(key, val + "-updated");
                            }
                        }
                    } catch (Exception e) {
                        writeErrors.incrementAndGet();
                    }
                });
            }

            startLatch.countDown();
            writerPool.shutdown();
            assertTrue(writerPool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));

            assertEquals(0, writeErrors.get(), "Concurrent ingestion encountered write errors");

            // Verify all committed keys are readable
            for (int t = 0; t < writerThreads; t++) {
                for (int i = 0; i < recordsPerThread; i++) {
                    String key = "thread-" + t + "-key-" + i;
                    String expectedVal = (i % 5 == 0)
                            ? "thread-" + t + "-val-" + i + "-updated"
                            : "thread-" + t + "-val-" + i;
                    var valOpt = store.get(key);
                    assertTrue(valOpt.isPresent(), "Key " + key + " should be present");
                    assertEquals(expectedVal, valOpt.get(), "Value for key " + key + " must match");
                }
            }
        }
    }

    @Test
    public void testFaultToleranceAbortOnFailure(@TempDir Path tempDir) throws Exception {
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(300)
                .maxKeyMemoryMb(4)
                .build();

        try (OffHeapKeyDir index = new OffHeapKeyDir(4);
             StorageEngine engine = new StorageEngine(config)) {

            // Write record to segment 1 and force rollover to segment 2
            BinaryRecord rec1 = new BinaryRecord(System.currentTimeMillis(), "k1".getBytes(), new byte[150], 0);
            StorageEngine.WriteResult res1 = engine.write(rec1);
            index.put(rec1.key(), res1.fileId(), res1.valueSize(), res1.valueOffset(), res1.keyOffset(), res1.timestamp());

            // Write second record which exceeds remaining space in segment 1 and triggers rollover to segment 2
            BinaryRecord rec2 = new BinaryRecord(System.currentTimeMillis(), "k2".getBytes(), new byte[150], 0);
            StorageEngine.WriteResult res2 = engine.write(rec2);
            index.put(rec2.key(), res2.fileId(), res2.valueSize(), res2.valueOffset(), res2.keyOffset(), res2.timestamp());

            assertTrue(engine.getActiveFileId() > 1, "Expected rollover to active segment > 1");
            Path seg1File = tempDir.resolve("00000001.data");
            assertTrue(Files.exists(seg1File));

            // Truncate segment 1 to a corrupt size (< 22 bytes)
            Files.write(seg1File, new byte[]{1, 2, 3, 4, 5});

            try (Compactor compactor = new Compactor(config, engine, index)) {
                compactor.compact();
            }

            // Fault tolerance: the corrupt/truncated source segment MUST NOT be deleted
            assertTrue(Files.exists(seg1File), "Compactor must abort and NOT delete truncated/corrupt source segment file");
        }
    }
}

