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
        this.config = config;
        try {
            this.index = new OffHeapKeyDir(config.getMaxKeyMemoryMb(), config.getAverageKeySize());
            this.storageEngine = new StorageEngine(config);

            // Recover index state from disk logs on boot
            this.storageEngine.recover(index);

            // Initialize and start background compactor
            this.compactor = new Compactor(config, storageEngine, index);
            this.compactor.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize VulcanoStore storage engine", e);
        }
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
