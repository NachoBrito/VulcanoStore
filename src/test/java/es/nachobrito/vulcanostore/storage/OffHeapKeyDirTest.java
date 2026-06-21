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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Unit tests asserting correctness, safety, and probing behaviors of the off-heap index.
 */
public class OffHeapKeyDirTest {

    @Test
    public void testBasicPutAndGet() {
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(1)) {
            byte[] key = "user-12345".getBytes();
            keyDir.put(key, 1, 100, 2048, 1024, 999999L);

            assertEquals(1, keyDir.getUniqueKeyCount());

            OffHeapKeyDir.Slot slot = keyDir.get(key);
            assertNotNull(slot);
            assertEquals(1, slot.fileId());
            assertEquals(100, slot.valueSize());
            assertEquals(2048, slot.valueOffset());
            assertEquals(1024, slot.keyOffset());
            assertEquals(999999L, slot.timestamp());
        }
    }

    @Test
    public void testUpdateExistingKey() {
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(1)) {
            byte[] key = "update-key".getBytes();
            keyDir.put(key, 1, 50, 100, 20, 500L);
            assertEquals(1, keyDir.getUniqueKeyCount());

            // Update same key
            keyDir.put(key, 2, 80, 500, 30, 800L);
            assertEquals(1, keyDir.getUniqueKeyCount()); // Capacity count must not increase!

            OffHeapKeyDir.Slot slot = keyDir.get(key);
            assertNotNull(slot);
            assertEquals(2, slot.fileId());
            assertEquals(80, slot.valueSize());
            assertEquals(500, slot.valueOffset());
            assertEquals(800, slot.timestamp());
        }
    }

    @Test
    public void testDeleteKey() {
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(1)) {
            byte[] key = "delete-me".getBytes();
            keyDir.put(key, 1, 50, 100, 20, 500L);
            assertTrue(keyDir.remove(key));
            assertEquals(0, keyDir.getUniqueKeyCount());

            assertNull(keyDir.get(key));
            assertFalse(keyDir.remove(key)); // Already deleted
        }
    }

    @Test
    public void testCapacityExhaustionRejection() {
        // Enforces maxKeyMemoryMb = 1 limit (1,048,576 bytes)
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(1)) {
            // Write unique keys until we exceed the 1 MB limit
            es.nachobrito.vulcanostore.VulcanoKeyMemoryLimitExceededException ex = assertThrows(
                    es.nachobrito.vulcanostore.VulcanoKeyMemoryLimitExceededException.class, () -> {
                        for (int i = 0; i < 30000; i++) {
                            byte[] k = ("key-item-index-padding-to-make-it-larger-" + i).getBytes();
                            keyDir.put(k, 1, 10, i * 10, i, 100L);
                        }
                    }
            );
            assertTrue(ex.getMessage().contains("limit exceeded") || ex.getMessage().contains("limit"));
            assertTrue(keyDir.getActiveKeysMemoryBytes() <= 1024 * 1024);

            // Updating an existing key must still be allowed at full capacity
            // Let's find one key that was successfully inserted
            byte[] existingKey = "key-item-index-padding-to-make-it-larger-0".getBytes();
            assertDoesNotThrow(() -> keyDir.put(existingKey, 2, 99, 999, 99, 9999L));
        }
    }

    @Test
    public void testLinearProbingWrapping() {
        // Set capacity to 1 MB
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(1)) {
            // Write 10 unique keys, causing multiple natural collisions and boundary wraps
            for (int i = 0; i < 10; i++) {
                keyDir.put(("wrap-probe-key-item-" + i).getBytes(), 1, 100, i * 100, i * 2, 12345L);
            }
            assertEquals(10, keyDir.getUniqueKeyCount());

            // Ensure all 10 keys can be successfully resolved and retrieved
            for (int i = 0; i < 10; i++) {
                OffHeapKeyDir.Slot slot = keyDir.get(("wrap-probe-key-item-" + i).getBytes());
                assertNotNull(slot);
                assertEquals(i * 100, slot.valueOffset());
            }
        }
    }

    @Test
    public void testArenaDeallocationSafety() {
        OffHeapKeyDir keyDir = new OffHeapKeyDir(1);
        byte[] key = "safety".getBytes();
        keyDir.close();

        // Accessing the FFM closed segment should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> keyDir.put(key, 1, 10, 100, 10, 100L));
    }

    @Test
    public void testGetAllKeys() {
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(1)) {
            assertTrue(keyDir.getAllKeys().isEmpty());

            byte[] key1 = "key-1".getBytes();
            byte[] key2 = "key-2".getBytes();
            byte[] key3 = "key-3".getBytes();

            keyDir.put(key1, 1, 10, 100, 10, 1000L);
            keyDir.put(key2, 1, 10, 200, 20, 1001L);
            keyDir.put(key3, 1, 10, 300, 30, 1002L);

            java.util.List<byte[]> allKeys = keyDir.getAllKeys();
            assertEquals(3, allKeys.size());

            boolean foundKey1 = false;
            boolean foundKey2 = false;
            boolean foundKey3 = false;
            for (byte[] k : allKeys) {
                if (java.util.Arrays.equals(k, key1)) foundKey1 = true;
                if (java.util.Arrays.equals(k, key2)) foundKey2 = true;
                if (java.util.Arrays.equals(k, key3)) foundKey3 = true;
            }
            assertTrue(foundKey1);
            assertTrue(foundKey2);
            assertTrue(foundKey3);

            // Deleting a key should remove it from the returned keys list
            keyDir.remove(key2);
            allKeys = keyDir.getAllKeys();
            assertEquals(2, allKeys.size());
            foundKey1 = false;
            foundKey2 = false;
            foundKey3 = false;
            for (byte[] k : allKeys) {
                if (java.util.Arrays.equals(k, key1)) foundKey1 = true;
                if (java.util.Arrays.equals(k, key2)) foundKey2 = true;
                if (java.util.Arrays.equals(k, key3)) foundKey3 = true;
            }
            assertTrue(foundKey1);
            assertFalse(foundKey2);
            assertTrue(foundKey3);
        }
    }

    @Test
    public void testCustomAverageKeySizeConstructor() {
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(1, 100)) {
            byte[] key = "test-key".getBytes();
            keyDir.put(key, 1, 100, 2048, 1024, 999999L);
            assertNotNull(keyDir.get(key));
        }
    }
}

