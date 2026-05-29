package es.nachobrito.vulcanodb;

import es.nachobrito.vulcanodb.storage.BinaryRecord;
import es.nachobrito.vulcanodb.storage.Compactor;
import es.nachobrito.vulcanodb.storage.OffHeapKeyDir;
import es.nachobrito.vulcanodb.storage.StorageEngine;

import java.io.IOException;
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
            this.index = new OffHeapKeyDir(config.getExpectedKeys());
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
                    if (index != null) {
                        index.close();
                    }
                }
            }
        }
    }
}
