package es.nachobrito.vulcanostore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD integration tests asserting fundamental VulcanoStore CRUD lifecycle operations.
 */
public class VulcanoStoreTest {

    private VulcanoConfig config;
    private VulcanoStore store;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) {
        config = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(1024 * 1024) // 1MB for testing
                .maxKeyMemoryMb(4)
                .build();
        store = new VulcanoStoreImpl(config);
    }

    @Test
    public void testPutAndGet() throws IOException {
        byte[] key = "myKey".getBytes();
        byte[] value = "myValue".getBytes();

        store.put(key, value);

        Optional<byte[]> result = store.get(key);
        assertTrue(result.isPresent());
        assertArrayEquals(value, result.get());
    }

    @Test
    public void testPutAndGetStringConvenience() throws IOException {
        store.put("greeting", "hello world");

        Optional<String> result = store.get("greeting");
        assertTrue(result.isPresent());
        assertEquals("hello world", result.get());
    }

    @Test
    public void testGetNonExistentKey() throws IOException {
        Optional<byte[]> result = store.get("non-existent".getBytes());
        assertFalse(result.isPresent());
    }

    @Test
    public void testExists() throws IOException {
        byte[] key = "existsKey".getBytes();
        assertFalse(store.exists(key));

        store.put(key, "yes".getBytes());
        assertTrue(store.exists(key));
    }

    @Test
    public void testDelete() throws IOException {
        byte[] key = "deleteKey".getBytes();
        store.put(key, "data".getBytes());
        assertTrue(store.exists(key));

        boolean deleted = store.delete(key);
        assertTrue(deleted);
        assertFalse(store.exists(key));
        assertFalse(store.get(key).isPresent());
    }

    @Test
    public void testDeleteNonExistentKey() throws IOException {
        boolean deleted = store.delete("no-such-key".getBytes());
        assertFalse(deleted);
    }

    @Test
    public void testKeyMemoryLimitExceeded(@TempDir Path tempDir) throws IOException {
        VulcanoConfig limitConfig = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(2 * 1024 * 1024)
                .maxKeyMemoryMb(1) // 1MB limit = 1,048,576 bytes
                .build();

        try (VulcanoStore limitStore = new VulcanoStoreImpl(limitConfig)) {
            // We write keys until we hit the 1 MB limit
            VulcanoKeyMemoryLimitExceededException ex = assertThrows(
                VulcanoKeyMemoryLimitExceededException.class, () -> {
                    for (int i = 0; i < 30000; i++) {
                        byte[] k = ("key-item-index-padding-to-make-it-larger-" + i).getBytes();
                        limitStore.put(k, "val".getBytes());
                    }
                }
            );

            assertTrue(ex.getMessage().contains("limit exceeded") || ex.getMessage().contains("Limit"));
            assertTrue(ex.getCurrentMemoryBytes() <= 1024 * 1024);
            assertEquals(1024 * 1024, ex.getLimitBytes());
            assertTrue(ex.getKeyLength() > 0);
        }
    }

    @Test
    public void testKeyOverwriteAllowedAtLimit(@TempDir Path tempDir) throws IOException {
        VulcanoConfig limitConfig = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(5 * 1024 * 1024)
                .maxKeyMemoryMb(1) // 1MB limit
                .build();

        try (VulcanoStore limitStore = new VulcanoStoreImpl(limitConfig)) {
            // Insert some keys first
            byte[] existingKey = "overwrite-me-key-0".getBytes();
            limitStore.put(existingKey, "initial".getBytes());

            // Exceed the limit with other keys
            assertThrows(VulcanoKeyMemoryLimitExceededException.class, () -> {
                for (int i = 0; i < 30000; i++) {
                    byte[] k = ("key-item-index-padding-to-make-it-larger-" + i).getBytes();
                    limitStore.put(k, "val".getBytes());
                }
            });

            // Updating the existing key MUST still be allowed!
            assertDoesNotThrow(() -> {
                limitStore.put(existingKey, "updated-value".getBytes());
            });

            Optional<String> val = limitStore.get("overwrite-me-key-0");
            assertTrue(val.isPresent());
            assertEquals("updated-value", val.get());
        }
    }

    @Test
    public void testKeyDeletionReclaimsMemory(@TempDir Path tempDir) throws IOException {
        VulcanoConfig limitConfig = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(5 * 1024 * 1024)
                .maxKeyMemoryMb(1) // 1MB limit
                .build();

        try (VulcanoStore limitStore = new VulcanoStoreImpl(limitConfig)) {
            // Put keys until just below the limit, or until it throws
            int successfulKeysCount = 0;
            java.util.List<byte[]> keysList = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < 30000; i++) {
                    byte[] k = ("key-item-index-padding-to-make-it-larger-" + i).getBytes();
                    limitStore.put(k, "val".getBytes());
                    keysList.add(k);
                    successfulKeysCount++;
                }
                fail("Should have exceeded memory limit");
            } catch (VulcanoKeyMemoryLimitExceededException e) {
                // Good
            }

            // Now delete 10 keys to free up some space
            assertTrue(successfulKeysCount > 10);
            for (int i = 0; i < 10; i++) {
                byte[] k = keysList.get(i);
                assertTrue(limitStore.delete(k));
            }

            // We should now be able to insert at least one new key that is small
            assertDoesNotThrow(() -> {
                limitStore.put("new-key-after-deletion", "new-val");
            });

            assertTrue(limitStore.exists("new-key-after-deletion"));
        }
    }
}
