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
            assertDoesNotThrow(() -> limitStore.put(existingKey, "updated-value".getBytes()));

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
            assertDoesNotThrow(() -> limitStore.put("new-key-after-deletion", "new-val"));

            assertTrue(limitStore.exists("new-key-after-deletion"));
        }
    }

    @Test
    public void testKeysEmpty() throws IOException {
        assertTrue(store.keys().isEmpty());
    }

    @Test
    public void testKeysLifecycle() throws IOException {
        byte[] key1 = "k1".getBytes();
        byte[] key2 = "k2".getBytes();
        byte[] key3 = "k3".getBytes();

        store.put(key1, "v1".getBytes());
        store.put(key2, "v2".getBytes());

        java.util.List<byte[]> keys = store.keys();
        assertEquals(2, keys.size());

        boolean foundK1 = false;
        boolean foundK2 = false;
        for (byte[] k : keys) {
            if (java.util.Arrays.equals(k, key1)) foundK1 = true;
            if (java.util.Arrays.equals(k, key2)) foundK2 = true;
        }
        assertTrue(foundK1);
        assertTrue(foundK2);

        // Overwrite key1 - size of keys should remain 2
        store.put(key1, "v1-updated".getBytes());
        assertEquals(2, store.keys().size());

        // Put new key3 - size becomes 3
        store.put(key3, "v3".getBytes());
        assertEquals(3, store.keys().size());

        // Delete key2 - size becomes 2 and key2 is no longer in keys()
        store.delete(key2);
        keys = store.keys();
        assertEquals(2, keys.size());
        foundK1 = false;
        foundK2 = false;
        boolean foundK3 = false;
        for (byte[] k : keys) {
            if (java.util.Arrays.equals(k, key1)) foundK1 = true;
            if (java.util.Arrays.equals(k, key2)) foundK2 = true;
            if (java.util.Arrays.equals(k, key3)) foundK3 = true;
        }
        assertTrue(foundK1);
        assertFalse(foundK2);
        assertTrue(foundK3);
    }

    @Test
    public void testKeysRecovery(@TempDir Path tempDir) throws IOException {
        VulcanoConfig recoveryConfig = VulcanoConfig.builder()
                .dbPath(tempDir)
                .segmentSize(1024 * 1024)
                .maxKeyMemoryMb(2)
                .build();

        byte[] key1 = "recovered-key-1".getBytes();
        byte[] key2 = "recovered-key-2".getBytes();

        try (VulcanoStore store1 = new VulcanoStoreImpl(recoveryConfig)) {
            store1.put(key1, "value1".getBytes());
            store1.put(key2, "value2".getBytes());
        }

        // Reopen database and verify recovery restores index entries accessible via keys()
        try (VulcanoStore store2 = new VulcanoStoreImpl(recoveryConfig)) {
            java.util.List<byte[]> keys = store2.keys();
            assertEquals(2, keys.size());

            boolean foundK1 = false;
            boolean foundK2 = false;
            for (byte[] k : keys) {
                if (java.util.Arrays.equals(k, key1)) foundK1 = true;
                if (java.util.Arrays.equals(k, key2)) foundK2 = true;
            }
            assertTrue(foundK1);
            assertTrue(foundK2);
        }
    }
}

