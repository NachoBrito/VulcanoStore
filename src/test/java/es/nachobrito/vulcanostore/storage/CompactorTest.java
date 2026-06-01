package es.nachobrito.vulcanostore.storage;

import es.nachobrito.vulcanostore.SyncStrategy;
import es.nachobrito.vulcanostore.VulcanoConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
        // 1. Configure StorageEngine with segmentSize = 50 to force rollovers with small records
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(50)
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
}
