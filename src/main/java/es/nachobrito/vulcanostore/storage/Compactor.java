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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles background compaction (merging) of inactive data log segments.
 * <p>
 * This compactor runs in a background thread, identifying inactive segments,
 * discarding obsolete or deleted records, and atomically committing updated
 * offsets back to the main index.
 * </p>
 */
public class Compactor implements AutoCloseable {

    private final VulcanoConfig config;
    private final StorageEngine storageEngine;
    private final OffHeapKeyDir index;

    private Thread workerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Instantiates the background compactor.
     *
     * @param config        the database configuration.
     * @param storageEngine the storage coordinator.
     * @param index         the off-heap index.
     */
    public Compactor(VulcanoConfig config, StorageEngine storageEngine, OffHeapKeyDir index) {
        this.config = config;
        this.storageEngine = storageEngine;
        this.index = index;
    }

    /**
     * Triggers a synchronous compaction run for testing purposes.
     *
     * @throws IOException if a low-level I/O error occurs.
     */
    public void compact() throws IOException {
        int activeFileId = storageEngine.getActiveFileId();
        Path dbPath = config.getDbPath();

        // Scan the directory for all inactive segment file IDs
        java.util.List<Integer> inactiveFileIds;
        try (var stream = Files.list(dbPath)) {
            inactiveFileIds = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("\\d{8}\\.data"))
                    .map(p -> p.getFileName().toString().substring(0, 8))
                    .map(Integer::parseInt)
                    .filter(fileId -> fileId < activeFileId)
                    .sorted()
                    .toList();
        }

        if (inactiveFileIds.isEmpty()) {
            return; // Nothing to compact
        }

        // Merge inactive segments
        for (int fileId : inactiveFileIds) {
            Path filePath = dbPath.resolve(String.format("%08d.data", fileId));
            long capacity = Files.size(filePath);
            if (capacity < 22) {
                // Empty/corrupt segment, close and clean up
                storageEngine.closeAndRemoveSegment(fileId);
                continue;
            }

            // Open the inactive segment to read and migrate active records
            try (FileSegment segment = new FileSegment(fileId, filePath, capacity)) {
                long offset = 0;
                while (offset + 22 <= capacity) {
                    try {
                        BinaryRecord record = segment.read(offset);
                        if (record.timestamp() == 0 && record.key().length == 0) {
                            break;
                        }

                        byte[] key = record.key();
                        int keyLen = key.length;
                        int valLen = record.isTombstone() ? 0 : record.value().length;
                        int totalSize = 22 + keyLen + valLen;

                        // Check if this record is still active in the main index
                        OffHeapKeyDir.Slot slot;
                        synchronized (index) {
                            slot = index.get(key);
                        }

                        // If slot exists, points to this file, and starts at this exact offset, it is active!
                        if (slot != null && slot.fileId() == fileId && slot.valueOffset() == offset) {
                            // Migrate the active record: write it to the active segment
                            StorageEngine.WriteResult res = storageEngine.write(record);

                            // Atomically update the index coordinates
                            synchronized (index) {
                                index.put(key, res.fileId(), res.valueSize(), res.valueOffset(), res.keyOffset(), res.timestamp());
                            }
                        }

                        offset += totalSize;
                    } catch (Exception e) {
                        break;
                    }
                }
            }

            // After scanning, close and remove the compacted segment from the storage engine and delete from disk!
            storageEngine.closeAndRemoveSegment(fileId);
        }
    }

    /**
     * Starts the background compaction thread.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        Thread.sleep(1000); // Poll/run compaction every second
                        compact();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (IOException e) {
                        // Silently swallow/handle recovery generation error to maintain background thread durability resilience
                    }
                }
            }, "vulcanodb-compactor");
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    @Override
    public void close() throws Exception {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread.join(1000); // wait up to 1 second
        }
    }
}
