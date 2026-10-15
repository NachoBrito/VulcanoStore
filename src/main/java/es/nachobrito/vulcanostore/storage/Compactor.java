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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(Compactor.class);

    private record PendingMigration(BinaryRecord record, long oldOffset) {}

    private final VulcanoConfig config;
    private final StorageEngine storageEngine;
    private final OffHeapKeyDir index;

    private Thread workerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

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
        if (stopped.get() || Thread.currentThread().isInterrupted()) {
            return;
        }

        int activeFileId = storageEngine.getActiveFileId();
        Path dbPath = config.getDbPath();

        // Scan the directory for all inactive segment file IDs
        List<Integer> inactiveFileIds;
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
            if (stopped.get() || Thread.currentThread().isInterrupted()) {
                break;
            }

            Path filePath = dbPath.resolve(String.format("%08d.data", fileId));
            if (!Files.exists(filePath)) {
                continue;
            }

            long capacity;
            try {
                capacity = Files.size(filePath);
            } catch (IOException e) {
                LOGGER.error("Failed to read size for segment file {}. Skipping.", filePath, e);
                continue;
            }

            if (capacity < 22) {
                LOGGER.error("Segment file {} is truncated or corrupt (size: {} bytes). Aborting compaction for this segment.", filePath, capacity);
                continue; // Do NOT delete truncated/corrupt segment
            }

            // Pass 1: Scan segment, validate all records, determine active vs dead records
            List<PendingMigration> activeRecordsToMigrate = new ArrayList<>();
            int deadRecords = 0;
            int tombstoneRecords = 0;
            boolean scanFailed = false;

            try (FileSegment segment = new FileSegment(fileId, filePath, capacity, true)) {
                long offset = 0;
                while (offset + 22 <= capacity) {
                    if (stopped.get() || Thread.currentThread().isInterrupted()) {
                        scanFailed = true;
                        break;
                    }

                    BinaryRecord record;
                    try {
                        record = segment.read(offset);
                    } catch (Exception e) {
                        LOGGER.error("Failed to read record at offset {} in segment {}. Aborting compaction for this segment.", offset, fileId, e);
                        scanFailed = true;
                        break;
                    }

                    if (record.timestamp() == 0 && record.key().length == 0) {
                        // Normal padding / end of written records in preallocated segment
                        break;
                    }

                    byte[] key = record.key();
                    int keyLen = key.length;
                    int valLen = record.isTombstone() ? 0 : record.value().length;
                    int totalSize = 22 + keyLen + valLen;

                    if (record.isTombstone()) {
                        deadRecords++;
                        tombstoneRecords++;
                    } else {
                        OffHeapKeyDir.Slot slot;
                        synchronized (index) {
                            slot = index.get(key);
                        }
                        if (slot != null && slot.fileId() == fileId && slot.valueOffset() == offset) {
                            activeRecordsToMigrate.add(new PendingMigration(record, offset));
                        } else {
                            deadRecords++;
                        }
                    }

                    offset += totalSize;
                }
            } catch (Exception e) {
                LOGGER.error("Exception occurred while accessing segment {}. Aborting compaction for this segment.", fileId, e);
                scanFailed = true;
            }

            if (scanFailed) {
                LOGGER.warn("Scan failed for segment {}. Source segment preserved.", fileId);
                continue;
            }

            int totalRecords = activeRecordsToMigrate.size() + deadRecords;
            if (totalRecords == 0) {
                LOGGER.debug("Segment {} has 0 total records. Skipping compaction.", fileId);
                continue;
            }

            // Threshold-based compaction check
            if ((double) deadRecords / totalRecords < config.getCompactionThreshold()) {
                LOGGER.debug("Segment {} dead record ratio ({}/{}) is below compaction threshold {}. Skipping compaction.",
                        fileId, deadRecords, totalRecords, config.getCompactionThreshold());
                continue;
            }

            // Safety guard against accidental deletion:
            if (activeRecordsToMigrate.isEmpty()) {
                if (tombstoneRecords == totalRecords) {
                    LOGGER.info("Segment {} contains only tombstone records ({}/{}). Removing segment.", fileId, tombstoneRecords, totalRecords);
                    storageEngine.closeAndRemoveSegment(fileId);
                    continue;
                } else {
                    LOGGER.error("Segment {} has {} records but none matched the index, and not all are tombstones (tombstones: {}). Preserving file on disk to avoid data loss.",
                            fileId, totalRecords, tombstoneRecords);
                    continue;
                }
            }

            // Pass 2: Migrate active records
            boolean migrationSuccess = true;
            for (PendingMigration migration : activeRecordsToMigrate) {
                if (stopped.get() || Thread.currentThread().isInterrupted()) {
                    migrationSuccess = false;
                    break;
                }

                BinaryRecord record = migration.record();
                byte[] key = record.key();
                long oldOffset = migration.oldOffset();

                // Re-verify the record is still active before writing
                boolean stillActive;
                synchronized (index) {
                    OffHeapKeyDir.Slot slot = index.get(key);
                    stillActive = (slot != null && slot.fileId() == fileId && slot.valueOffset() == oldOffset);
                }

                if (!stillActive) {
                    // Key was updated or deleted concurrently by a user thread
                    continue;
                }

                StorageEngine.WriteResult res;
                try {
                    res = storageEngine.write(record);
                } catch (Exception e) {
                    LOGGER.error("Failed to write migrated record for key in segment {}. Aborting segment removal.", fileId, e);
                    migrationSuccess = false;
                    break;
                }

                // Atomically update index coordinates if still matching
                synchronized (index) {
                    OffHeapKeyDir.Slot slot = index.get(key);
                    if (slot != null && slot.fileId() == fileId && slot.valueOffset() == oldOffset) {
                        index.put(key, res.fileId(), res.valueSize(), res.valueOffset(), res.keyOffset(), res.timestamp());
                    }
                }
            }

            // Only delete the segment after confirming 100% successful migration
            if (migrationSuccess) {
                LOGGER.info("Successfully compacted segment {}. Removing old segment.", fileId);
                storageEngine.closeAndRemoveSegment(fileId);
            } else {
                LOGGER.warn("Migration failed or aborted for segment {}. Source segment preserved.", fileId);
            }
        }
    }

    /**
     * Starts the background compaction thread.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(() -> {
                while (running.get() && !stopped.get()) {
                    try {
                        Thread.sleep(1000); // Poll/run compaction every second
                        compact();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        LOGGER.error("Unexpected error in compactor background thread", e);
                    }
                }
            }, "vulcanodb-compactor");
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    @Override
    public void close() throws Exception {
        stopped.set(true);
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread.join(5000); // wait up to 5 seconds
        }
    }
}

