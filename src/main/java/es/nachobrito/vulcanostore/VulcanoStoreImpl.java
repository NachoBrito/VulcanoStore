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
    private final VulcanoConfig config;
    private final OffHeapKeyDir index;
    private final StorageEngine storageEngine;
    private final Compactor compactor;

    /**
     * Instantiates the VulcanoStore database engine with the specified configuration.
     *
     * @param config the validated {@link VulcanoConfig} settings.
     */
    public VulcanoStoreImpl(VulcanoConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }
        this.config = resolveConfig(config);
        try {
            this.index = new OffHeapKeyDir(this.config.getMaxKeyMemoryMb(), this.config.getAverageKeySize());
            this.storageEngine = new StorageEngine(this.config);

            // Recover index state from disk logs on boot
            this.storageEngine.recover(index);

            // Initialize and start background compactor
            this.compactor = new Compactor(this.config, storageEngine, index);
            this.compactor.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize VulcanoStore storage engine", e);
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

            return VulcanoConfig.builder()
                    .dbPath(dbPath)
                    .maxKeyMemoryMb(config.getMaxKeyMemoryMb())
                    .segmentSize(segmentSize)
                    .syncStrategy(syncStrategy)
                    .syncIntervalMs(syncIntervalMs)
                    .averageKeySize(averageKeySize)
                    .build();
        } else {
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
        synchronized (index) {
            try {
                if (compactor != null) {
                    compactor.close();
                }
            } catch (Exception e) {
                throw new IOException("Failed to stop background compactor", e);
            } finally {
                try {
                    if (storageEngine != null) {
                        storageEngine.close();
                    }
                } finally {
                    index.close();
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
