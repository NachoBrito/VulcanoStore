package es.nachobrito.vulcanostore.storage;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Unit tests asserting correctness, safety, and probing behaviors of the off-heap index.
 */
public class OffHeapKeyDirTest {

    @Test
    public void testBasicPutAndGet() {
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(100)) {
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
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(100)) {
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
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(100)) {
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
        // Enforces expectedKeys = 5 capacity limit
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(5)) {
            for (int i = 0; i < 5; i++) {
                keyDir.put(("key-" + i).getBytes(), 1, 10, i * 10, i, 100L);
            }
            assertEquals(5, keyDir.getUniqueKeyCount());

            // 6th key should be rejected
            assertThrows(IllegalStateException.class, () -> {
                keyDir.put("extra-key".getBytes(), 1, 10, 50, 5, 100L);
            });

            // Updating an existing key must still be allowed at full capacity
            assertDoesNotThrow(() -> {
                keyDir.put("key-2".getBytes(), 2, 99, 999, 99, 9999L);
            });
        }
    }

    @Test
    public void testLinearProbingWrapping() {
        // Set capacity to 10 keys
        try (OffHeapKeyDir keyDir = new OffHeapKeyDir(10)) {
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
        OffHeapKeyDir keyDir = new OffHeapKeyDir(100);
        byte[] key = "safety".getBytes();
        keyDir.close();

        // Accessing the FFM closed segment should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> {
            keyDir.put(key, 1, 10, 100, 10, 100L);
        });
    }
}
