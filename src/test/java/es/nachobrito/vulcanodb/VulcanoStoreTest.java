package es.nachobrito.vulcanodb;

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
                .expectedKeys(1000)
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
}
