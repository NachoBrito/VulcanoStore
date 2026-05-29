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
     * The expected number of unique keys that will be stored in the database.
     * <p>
     * This parameter is utilized to size the off-heap {@code OffHeapKeyDir} linear-probing index table
     * to ensure optimal hash distribution and minimize collision chains.
     * Default value is 10,000,000 keys.
     * </p>
     */
    private final long expectedKeys;

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

    private VulcanoConfig(Builder builder) {
        this.dbPath = builder.dbPath;
        this.segmentSize = builder.segmentSize;
        this.expectedKeys = builder.expectedKeys;
        this.syncStrategy = builder.syncStrategy;
        this.syncIntervalMs = builder.syncIntervalMs;
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
     * Returns the expected unique keys capacity target for off-heap index sizing.
     *
     * @return the expected key count capacity.
     */
    public long getExpectedKeys() {
        return expectedKeys;
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
        private long expectedKeys = 10_000_000;      // 10M keys default
        private SyncStrategy syncStrategy = SyncStrategy.SYNC_INTERVAL;
        private long syncIntervalMs = 500;           // 500ms default

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
         * Sets the expected key count capacity target for sizing the off-heap index.
         *
         * @param expectedKeys the expected unique key count. Must be positive.
         * @return this builder instance for method chaining.
         */
        public Builder expectedKeys(long expectedKeys) {
            this.expectedKeys = expectedKeys;
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
         * Assembles, validates, and returns a new {@link VulcanoConfig} instance.
         *
         * @return the validated {@link VulcanoConfig} object.
         * @throws IllegalArgumentException if dbPath or syncStrategy is null, or if segmentSize, expectedKeys, or syncIntervalMs is non-positive.
         */
        public VulcanoConfig build() {
            if (dbPath == null) {
                throw new IllegalArgumentException("Database path cannot be null");
            }
            if (segmentSize <= 0) {
                throw new IllegalArgumentException("Segment size must be positive");
            }
            if (expectedKeys <= 0) {
                throw new IllegalArgumentException("Expected keys capacity must be positive");
            }
            if (syncStrategy == null) {
                throw new IllegalArgumentException("Sync strategy cannot be null");
            }
            if (syncIntervalMs <= 0) {
                throw new IllegalArgumentException("Sync interval in milliseconds must be positive");
            }
            return new VulcanoConfig(this);
        }
    }
}
