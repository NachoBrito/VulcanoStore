package es.nachobrito.vulcanodb;

import java.io.IOException;
import java.util.Optional;

/**
 * Programmatic interface for the VulcanoStore in-process key-value library.
 * <p>
 * This interface exposes direct byte array operations for maximum execution efficiency
 * and GC-free interactions, alongside convenient String-based default overrides.
 * </p>
 */
public interface VulcanoStore extends AutoCloseable {

    /**
     * Stores a key-value pair in the database.
     *
     * @param key   the lookup key in bytes. Cannot be null.
     * @param value the target value in bytes. Cannot be null.
     * @throws IOException           if a low-level disk I/O error occurs.
     * @throws IllegalStateException if the database index capacity is fully exhausted.
     */
    void put(byte[] key, byte[] value) throws IOException;

    /**
     * Stores a String key-value pair, encoded using UTF-8.
     *
     * @param key   the lookup key String. Cannot be null.
     * @param value the target value String. Cannot be null.
     * @throws IOException           if a low-level disk I/O error occurs.
     * @throws IllegalStateException if the database index capacity is fully exhausted.
     */
    default void put(String key, String value) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        put(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), 
            value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Retrieves the byte value associated with the key.
     *
     * @param key the lookup key in bytes. Cannot be null.
     * @return an {@link Optional} containing the raw byte value, or empty if the key does not exist.
     * @throws IOException if a low-level disk I/O error occurs.
     */
    Optional<byte[]> get(byte[] key) throws IOException;

    /**
     * Retrieves the String value associated with the key, decoded using UTF-8.
     *
     * @param key the lookup key String. Cannot be null.
     * @return an {@link Optional} containing the String value, or empty if the key does not exist.
     * @throws IOException if a low-level disk I/O error occurs.
     */
    default Optional<String> get(String key) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        return get(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Deletes a key-value pair from the store.
     * <p>
     * Deletions append a tombstone record to disk and remove the index entry.
     * </p>
     *
     * @param key the lookup key in bytes to delete. Cannot be null.
     * @return true if the key existed in the index and was successfully marked deleted; false otherwise.
     * @throws IOException if a low-level disk I/O error occurs.
     */
    boolean delete(byte[] key) throws IOException;

    /**
     * Deletes a String key-value pair from the store.
     *
     * @param key the lookup key String to delete. Cannot be null.
     * @return true if the key existed in the index and was successfully marked deleted; false otherwise.
     * @throws IOException if a low-level disk I/O error occurs.
     */
    default boolean delete(String key) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        return delete(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Checks if a key exists in the database index.
     *
     * @param key the lookup key in bytes. Cannot be null.
     * @return true if the key exists; false otherwise.
     * @throws IOException if a low-level disk I/O error occurs.
     */
    boolean exists(byte[] key) throws IOException;

    /**
     * Checks if a String key exists in the database index.
     *
     * @param key the lookup key String. Cannot be null.
     * @return true if the key exists; false otherwise.
     * @throws IOException if a low-level disk I/O error occurs.
     */
    default boolean exists(String key) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        return exists(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Closes the storage engine, flushing active buffers and safely releasing native memory allocations.
     *
     * @throws IOException if a low-level disk I/O error occurs during flush/close.
     */
    @Override
    void close() throws IOException;
}
