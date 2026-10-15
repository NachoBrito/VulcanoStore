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

import es.nachobrito.vulcanostore.storage.BinaryRecord;
import es.nachobrito.vulcanostore.storage.Compactor;
import es.nachobrito.vulcanostore.storage.OffHeapKeyDir;
import es.nachobrito.vulcanostore.storage.StorageEngine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

/**
 * Concrete implementation of the {@link VulcanoStore} in-process library.
 * <p>
 * This class coordinates the memory-mapped storage engine segments, hint files,
 * background compaction, and JEP 454 off-heap indexing.
 * </p>
 */
public class VulcanoStoreImpl implements VulcanoStore {
    private static class DirectoryLockEntry {
        final FileChannel channel;
        final FileLock lock;
        final boolean isShared;
        int refCount;

        DirectoryLockEntry(FileChannel channel, FileLock lock, boolean isShared) {
            this.channel = channel;
            this.lock = lock;
            this.isShared = isShared;
            this.refCount = 1;
        }
    }

    private static final java.util.Map<Path, DirectoryLockEntry> ACTIVE_DIRECTORY_LOCKS = new java.util.HashMap<>();

    private final VulcanoConfig config;
    private final OffHeapKeyDir index;
    private final StorageEngine storageEngine;
    private final Compactor compactor;
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private final Path normalizedLockPath;
    private final java.util.concurrent.ScheduledExecutorService flusherExecutor;
    private final Thread shutdownHook;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Instantiates the VulcanoStore database engine with the specified configuration.
     *
     * @param config the validated {@link VulcanoConfig} settings.
     */
    public VulcanoStoreImpl(VulcanoConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }

        Path dbPath = config.getDbPath();
        if (!config.isReadOnly()) {
            try {
                Files.createDirectories(dbPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create database directory: " + dbPath, e);
            }
        }

        Path lockPath = dbPath.resolve("vulcano.lock");
        this.normalizedLockPath = lockPath.toAbsolutePath().normalize();

        synchronized (ACTIVE_DIRECTORY_LOCKS) {
            DirectoryLockEntry entry = ACTIVE_DIRECTORY_LOCKS.get(normalizedLockPath);
            if (config.isReadOnly()) {
                if (entry != null) {
                    if (!entry.isShared) {
                        throw new IllegalStateException("Database directory is already locked by another process: " + dbPath);
                    }
                    entry.refCount++;
                    this.lockChannel = entry.channel;
                    this.directoryLock = entry.lock;
                } else {
                    FileChannel ch;
                    try {
                        if (Files.exists(lockPath)) {
                            ch = FileChannel.open(lockPath, StandardOpenOption.READ);
                        } else {
                            if (!Files.exists(dbPath)) {
                                Files.createDirectories(dbPath);
                            }
                            ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                        }
                        FileLock lock;
                        try {
                            lock = ch.tryLock(0, Long.MAX_VALUE, true);
                        } catch (OverlappingFileLockException e) {
                            lock = null;
                        }
                        if (lock == null) {
                            ch.close();
                            throw new IllegalStateException("Database directory is already locked by another process: " + dbPath);
                        }
                        entry = new DirectoryLockEntry(ch, lock, true);
                        ACTIVE_DIRECTORY_LOCKS.put(normalizedLockPath, entry);
                        this.lockChannel = ch;
                        this.directoryLock = lock;
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to acquire database directory lock on " + lockPath, e);
                    }
                }
            } else {
                if (entry != null) {
                    throw new IllegalStateException("Database directory is already locked by another process: " + dbPath);
                }
                FileChannel ch;
                try {
                    ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                    FileLock lock;
                    try {
                        lock = ch.tryLock();
                    } catch (OverlappingFileLockException e) {
                        lock = null;
                    }
                    if (lock == null) {
                        ch.close();
                        throw new IllegalStateException("Database directory is already locked by another process: " + dbPath);
                    }
                    entry = new DirectoryLockEntry(ch, lock, false);
                    ACTIVE_DIRECTORY_LOCKS.put(normalizedLockPath, entry);
                    this.lockChannel = ch;
                    this.directoryLock = lock;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to acquire database directory lock on " + lockPath, e);
                }
            }
        }

        java.util.concurrent.ScheduledExecutorService flusher = null;
        Thread hook = null;
        try {
            this.config = resolveConfig(config);
            this.index = new OffHeapKeyDir(this.config.getMaxKeyMemoryMb(), this.config.getAverageKeySize());
            this.storageEngine = new StorageEngine(this.config);

            // Recover index state from disk logs on boot
            this.storageEngine.recover(index);

            // Initialize and start background compactor (only in read-write mode)
            if (!this.config.isReadOnly()) {
                this.compactor = new Compactor(this.config, storageEngine, index);
                this.compactor.start();
            } else {
                this.compactor = null;
            }

            // Initialize and start background flusher if SYNC_INTERVAL (only in read-write mode)
            if (!this.config.isReadOnly() && this.config.getSyncStrategy() == SyncStrategy.SYNC_INTERVAL) {
                flusher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "vulcanostore-flusher");
                    t.setDaemon(true);
                    return t;
                });
                flusher.scheduleWithFixedDelay(() -> {
                    try {
                        storageEngine.forceActiveSegment();
                    } catch (Throwable ignored) {
                    }
                }, this.config.getSyncIntervalMs(), this.config.getSyncIntervalMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            this.flusherExecutor = flusher;

            // Register shutdown hook
            hook = new Thread(() -> {
                try {
                    VulcanoStoreImpl.this.close();
                } catch (Throwable ignored) {
                }
            }, "vulcanostore-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(hook);
            this.shutdownHook = hook;
        } catch (Throwable t) {
            if (hook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) {
                }
            }
            if (flusher != null) {
                flusher.shutdownNow();
            }
            releaseLock();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to initialize VulcanoStore storage engine", t);
        }
    }

    private static VulcanoConfig resolveConfig(VulcanoConfig config) {
        java.nio.file.Path dbPath = config.getDbPath();
        java.nio.file.Path metadataPath = dbPath.resolve("vulcano.properties");

        if (java.nio.file.Files.exists(metadataPath)) {
            java.util.Properties props = new java.util.Properties();
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(metadataPath)) {
                props.load(in);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read metadata file: " + metadataPath, e);
            }

            long segmentSize = Long.parseLong(props.getProperty("segmentSize", String.valueOf(config.getSegmentSize())));
            SyncStrategy syncStrategy = SyncStrategy.valueOf(props.getProperty("syncStrategy", config.getSyncStrategy().name()));
            long syncIntervalMs = Long.parseLong(props.getProperty("syncIntervalMs", String.valueOf(config.getSyncIntervalMs())));
            int averageKeySize = Integer.parseInt(props.getProperty("averageKeySize", String.valueOf(config.getAverageKeySize())));
            double compactionThreshold = Double.parseDouble(props.getProperty("compactionThreshold", String.valueOf(config.getCompactionThreshold())));

            return VulcanoConfig.builder()
                    .dbPath(dbPath)
                    .maxKeyMemoryMb(config.getMaxKeyMemoryMb())
                    .segmentSize(segmentSize)
                    .syncStrategy(syncStrategy)
                    .syncIntervalMs(syncIntervalMs)
                    .averageKeySize(averageKeySize)
                    .compactionThreshold(compactionThreshold)
                    .readOnly(config.isReadOnly())
                    .build();
        } else {
            if (config.isReadOnly()) {
                return config;
            }
            try {
                java.nio.file.Files.createDirectories(dbPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create database directory: " + dbPath, e);
            }

            java.util.Properties props = new java.util.Properties();
            props.setProperty("segmentSize", String.valueOf(config.getSegmentSize()));
            props.setProperty("syncStrategy", config.getSyncStrategy().name());
            props.setProperty("syncIntervalMs", String.valueOf(config.getSyncIntervalMs()));
            props.setProperty("averageKeySize", String.valueOf(config.getAverageKeySize()));
            props.setProperty("maxKeyMemoryMb", String.valueOf(config.getMaxKeyMemoryMb()));
            props.setProperty("compactionThreshold", String.valueOf(config.getCompactionThreshold()));

            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(metadataPath)) {
                props.store(out, "VulcanoStore Database Configuration Metadata");
            } catch (IOException e) {
                throw new RuntimeException("Failed to write metadata file: " + metadataPath, e);
            }

            return config;
        }
    }

    /**
     * Returns the resolved active database configuration.
     *
     * @return the active configuration.
     */
    public VulcanoConfig getConfig() {
        return config;
    }

    @Override
    public void put(byte[] key, byte[] value) throws IOException {
        if (config.isReadOnly()) {
            throw new UnsupportedOperationException("Database is opened in read-only mode");
        }
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }

        synchronized (index) {
            boolean isNewKey = (index.get(key) == null);
            if (isNewKey) {
                long maxBytes = config.getMaxKeyMemoryMb() * 1024L * 1024L;
                long currentBytes = index.getActiveKeysMemoryBytes();
                if (currentBytes + key.length > maxBytes) {
                    throw new VulcanoKeyMemoryLimitExceededException(
                            "Key memory limit exceeded. Configured: " + maxBytes + " bytes, Current: " + currentBytes + " bytes, Attempted key size: " + key.length + " bytes.",
                            currentBytes,
                            maxBytes,
                            key.length
                    );
                }
            }
            BinaryRecord record = new BinaryRecord(System.currentTimeMillis(), key, value, 0);
            StorageEngine.WriteResult res = storageEngine.write(record);
            index.put(key, res.fileId(), res.valueSize(), res.valueOffset(), res.keyOffset(), res.timestamp());
            if (config.getSyncStrategy() == SyncStrategy.SYNC_ALWAYS) {
                storageEngine.forceActiveSegment();
            }
        }
    }

    @Override
    public Optional<byte[]> get(byte[] key) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        synchronized (index) {
            OffHeapKeyDir.Slot slot = index.get(key);
            if (slot == null) {
                return Optional.empty();
            }
            BinaryRecord record = storageEngine.read(slot.fileId(), slot.valueOffset());
            if (record == null || record.isTombstone()) {
                return Optional.empty();
            }
            return Optional.of(record.value());
        }
    }

    @Override
    public boolean delete(byte[] key) throws IOException {
        if (config.isReadOnly()) {
            throw new UnsupportedOperationException("Database is opened in read-only mode");
        }
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        synchronized (index) {
            OffHeapKeyDir.Slot slot = index.get(key);
            if (slot == null) {
                return false;
            }

            // Append tombstone record to log segment
            BinaryRecord tombstone = new BinaryRecord(System.currentTimeMillis(), key, null, 0);
            storageEngine.write(tombstone);

            // Remove key from index
            index.remove(key);
            if (config.getSyncStrategy() == SyncStrategy.SYNC_ALWAYS) {
                storageEngine.forceActiveSegment();
            }
            return true;
        }
    }

    @Override
    public boolean exists(byte[] key) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        synchronized (index) {
            return index.get(key) != null;
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown in progress
            }
        }

        if (flusherExecutor != null) {
            flusherExecutor.shutdown();
            try {
                if (!flusherExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    flusherExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                flusherExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        try {
            if (compactor != null) {
                compactor.close();
            }
        } catch (Exception e) {
            throw new IOException("Failed to stop background compactor", e);
        } finally {
            synchronized (index) {
                try {
                    if (storageEngine != null) {
                        storageEngine.close();
                    }
                } finally {
                    try {
                        if (index != null) {
                            index.close();
                        }
                    } finally {
                        releaseLock();
                    }
                }
            }
        }
    }

    private void releaseLock() {
        if (normalizedLockPath != null) {
            synchronized (ACTIVE_DIRECTORY_LOCKS) {
                DirectoryLockEntry entry = ACTIVE_DIRECTORY_LOCKS.get(normalizedLockPath);
                if (entry != null) {
                    entry.refCount--;
                    if (entry.refCount <= 0) {
                        ACTIVE_DIRECTORY_LOCKS.remove(normalizedLockPath);
                        if (entry.lock != null && entry.lock.isValid()) {
                            try {
                                entry.lock.release();
                            } catch (IOException ignored) {
                            }
                        }
                        if (entry.channel != null && entry.channel.isOpen()) {
                            try {
                                entry.channel.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public List<byte[]> keys() throws IOException {
        synchronized (index) {
            return index.getAllKeys();
        }
    }
}
