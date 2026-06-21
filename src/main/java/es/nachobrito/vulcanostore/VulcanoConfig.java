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

package es.nachobrito.vulcanostore;

import java.nio.file.Path;

/**
 * Configuration class holding storage path, sizing thresholds, and synchronization modes for VulcanoStore.
 * <p>
 * This class is immutable and is instantiated via its nested {@link Builder}.
 * Default values are provided for most parameters to facilitate simple out-of-the-box setups.
 * </p>
 */
public class VulcanoConfig {

    /**
     * The file system path to the directory where VulcanoStore segment and hint files are stored.
     */
    private final Path dbPath;

    /**
     * The maximum size of each individual data log segment file in bytes.
     * <p>
     * Once the active segment exceeds this size, it is closed, and a new segment is initialized.
     * Default value is 128 MB (134,217,728 bytes).
     * </p>
     */
    private final long segmentSize;

    /**
     * The maximum off-heap memory size allocated for unique active keys in MB.
     * <p>
     * This parameter limits the total memory occupied by the active keys in the database.
     * Default value is 128 MB.
     * </p>
     */
    private final long maxKeyMemoryMb;

    /**
     * The synchronization strategy for flushing native memory-mapped pages back to physical storage.
     * <p>
     * Dictates the durability profile of the library.
     * Default strategy is {@link SyncStrategy#SYNC_INTERVAL}.
     * </p>
     */
    private final SyncStrategy syncStrategy;

    /**
     * The background periodic interval in milliseconds at which the storage engine calls {@code force()} to flush pages.
     * <p>
     * Only active when {@link #syncStrategy} is set to {@link SyncStrategy#SYNC_INTERVAL}.
     * Default value is 500 milliseconds.
     * </p>
     */
    private final long syncIntervalMs;

    /**
     * The expected average size of a key in bytes.
     */
    private final int averageKeySize;

    private VulcanoConfig(Builder builder) {
        this.dbPath = builder.dbPath;
        this.segmentSize = builder.segmentSize;
        this.maxKeyMemoryMb = builder.maxKeyMemoryMb;
        this.syncStrategy = builder.syncStrategy;
        this.syncIntervalMs = builder.syncIntervalMs;
        this.averageKeySize = builder.averageKeySize;
    }

    /**
     * Returns the file system path configured for storing segment data.
     *
     * @return the {@link Path} to the database directory.
     */
    public Path getDbPath() {
        return dbPath;
    }

    /**
     * Returns the maximum size of a single data log segment in bytes.
     *
     * @return the segment size in bytes.
     */
    public long getSegmentSize() {
        return segmentSize;
    }

    /**
     * Returns the configured maximum off-heap key memory limit in MB.
     *
     * @return the maximum key memory limit in MB.
     */
    public long getMaxKeyMemoryMb() {
        return maxKeyMemoryMb;
    }

    /**
     * Returns the configured synchronization strategy for log durability.
     *
     * @return the {@link SyncStrategy} enum value.
     */
    public SyncStrategy getSyncStrategy() {
        return syncStrategy;
    }

    /**
     * Returns the periodic interval in milliseconds for background page cache flushing.
     *
     * @return the sync interval in milliseconds.
     */
    public long getSyncIntervalMs() {
        return syncIntervalMs;
    }

    /**
     * Returns the expected average key size in bytes.
     *
     * @return the average key size in bytes.
     */
    public int getAverageKeySize() {
        return averageKeySize;
    }

    /**
     * Creates a new instance of the configuration {@link Builder}.
     *
     * @return a fresh {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for assembling and validating {@link VulcanoConfig} configurations.
     */
    public static class Builder {
        private Path dbPath;
        private long segmentSize = 128 * 1024 * 1024; // 128MB default
        private long maxKeyMemoryMb = 128;           // 128MB default
        private SyncStrategy syncStrategy = SyncStrategy.SYNC_INTERVAL;
        private long syncIntervalMs = 500;           // 500ms default
        private int averageKeySize = 36;             // Default to length in bytes of UUID string (36 bytes)

        /**
         * Sets the directory path for database storage.
         *
         * @param dbPath the {@link Path} to the directory. Cannot be null.
         * @return this builder instance for method chaining.
         */
        public Builder dbPath(Path dbPath) {
            this.dbPath = dbPath;
            return this;
        }

        /**
         * Sets the maximum segment file size threshold in bytes.
         *
         * @param segmentSize the segment size in bytes. Must be positive.
         * @return this builder instance for method chaining.
         */
        public Builder segmentSize(long segmentSize) {
            this.segmentSize = segmentSize;
            return this;
        }

        /**
         * Sets the maximum off-heap key memory limit in MB.
         *
         * @param maxKeyMemoryMb the maximum key memory limit in MB. Must be positive.
         * @return this builder instance for method chaining.
         */
        public Builder maxKeyMemoryMb(long maxKeyMemoryMb) {
            this.maxKeyMemoryMb = maxKeyMemoryMb;
            return this;
        }

        /**
         * Sets the synchronization strategy for flushing memory map buffers.
         *
         * @param syncStrategy the desired {@link SyncStrategy}. Cannot be null.
         * @return this builder instance for method chaining.
         */
        public Builder syncStrategy(SyncStrategy syncStrategy) {
            this.syncStrategy = syncStrategy;
            return this;
        }

        /**
         * Sets the background flush interval in milliseconds.
         *
         * @param syncIntervalMs the flush interval. Must be positive.
         * @return this builder instance for method chaining.
         */
        public Builder syncIntervalMs(long syncIntervalMs) {
            this.syncIntervalMs = syncIntervalMs;
            return this;
        }

        /**
         * Sets the expected average key size in bytes.
         *
         * @param averageKeySize the average key size in bytes. Must be positive.
         * @return this builder instance for method chaining.
         */
        public Builder averageKeySize(int averageKeySize) {
            this.averageKeySize = averageKeySize;
            return this;
        }

        /**
         * Assembles, validates, and returns a new {@link VulcanoConfig} instance.
         *
         * @return the validated {@link VulcanoConfig} object.
         * @throws IllegalArgumentException if dbPath or syncStrategy is null, or if segmentSize, maxKeyMemoryMb, or syncIntervalMs is non-positive.
         */
        public VulcanoConfig build() {
            if (dbPath == null) {
                throw new IllegalArgumentException("Database path cannot be null");
            }
            if (segmentSize <= 0) {
                throw new IllegalArgumentException("Segment size must be positive");
            }
            if (maxKeyMemoryMb <= 0) {
                throw new IllegalArgumentException("Maximum key memory must be positive");
            }
            if (syncStrategy == null) {
                throw new IllegalArgumentException("Sync strategy cannot be null");
            }
            if (syncIntervalMs <= 0) {
                throw new IllegalArgumentException("Sync interval in milliseconds must be positive");
            }
            if (averageKeySize <= 0) {
                throw new IllegalArgumentException("Average key size must be positive");
            }
            return new VulcanoConfig(this);
        }
    }
}
