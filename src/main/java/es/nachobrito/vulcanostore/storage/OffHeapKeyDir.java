package es.nachobrito.vulcanostore.storage;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import es.nachobrito.vulcanostore.VulcanoKeyMemoryLimitExceededException;

/**
 * High-performance, zero-GC, off-heap index for VulcanoStore keys.
 * <p>
 * This class maps all keys off-heap to a flat contiguous {@link MemorySegment}
 * using open-addressing with linear probing and hash collision wrapping.
 * It uses Java 25 Foreign Function & Memory (FFM) APIs (JEP 454) to bypass
 * the JVM garbage collector entirely, eliminating latency spikes.
 * </p>
 */
public class OffHeapKeyDir implements AutoCloseable {

    /**
     * Represents the indexing slot metadata retrieved for a given key.
     */
    public record Slot(
        int fileId,
        int valueSize,
        long valueOffset,
        long keyOffset,
        long timestamp
    ) {}

    /**
     * The size of a single slot in bytes.
     * <p>
     * Rationale: The core fields consume exactly 42 bytes. We pad the slot to 48 bytes
     * (the next multiple of 8) to satisfy the 8-byte alignment constraints of FFM JAVA_LONG
     * fields on standard hardware, avoiding alignment exceptions and maximizing CPU L1/L2 prefetching speed.
     * </p>
     */
    private static final long SLOT_SIZE = 48;

    /**
     * Relative byte offset of the Key Hash (8-byte long) within a slot.
     */
    private static final long HASH_OFFSET = 0;

    /**
     * Relative byte offset of the File ID (4-byte int) within a slot.
     */
    private static final long FILE_ID_OFFSET = 8;

    /**
     * Relative byte offset of the Value Size (4-byte int) within a slot.
     */
    private static final long VAL_SIZE_OFFSET = 12;

    /**
     * Relative byte offset of the Value Offset (8-byte long) within a slot.
     */
    private static final long VAL_OFFSET_OFFSET = 16;

    /**
     * Relative byte offset of the Key Offset on disk (8-byte long) within a slot.
     */
    private static final long KEY_OFFSET_OFFSET = 24;

    /**
     * Relative byte offset of the Timestamp (8-byte long) within a slot.
     */
    private static final long TS_OFFSET = 32;

    /**
     * Relative byte offset of the Key Size (2-byte short) within a slot.
     */
    private static final long KEY_SIZE_OFFSET = 40;
    
    /**
     * Special hash value reserved to mark deleted (tombstone) slots.
     * <p>
     * Rationale: Under linear probing, simply clearing a deleted slot to 0L would break
     * the collision search chain, causing subsequent lookups to stop early and miss keys.
     * Storing -1L preserves the search path.
     * </p>
     */
    private static final long TOMBSTONE_HASH = -1L;

    /**
     * The FNV-1a 64-bit non-cryptographic hash initialization offset basis.
     * <p>
     * Rationale: Standard FNV-1a offset basis constant for 64-bit hashing.
     * </p>
     */
    private static final long FNV_64_INIT = 0xcbf29ce484222325L;

    /**
     * The FNV-1a 64-bit non-cryptographic hash prime multiplier.
     * <p>
     * Rationale: Standard FNV-1a prime constant to achieve rapid avalanching distribution.
     * </p>
     */
    private static final long FNV_64_PRIME = 0x100000001b3L;

    /**
     * Safe fallback hash value used when FNV-1a naturally evaluates to 0L or -1L.
     * <p>
     * Rationale: Because 0L is reserved for empty slots and -1L is reserved for tombstones,
     * any natural collision with these values must fall back to a safe non-reserved constant.
     * </p>
     */
    private static final long SAFE_FALLBACK_HASH = 42L;

    /**
     * The load factor threshold for open-addressing indexing.
     * <p>
     * Rationale: Capping table occupancy at 70% prevents cluster degradation, ensuring
     * that point lookup complexity remains close to O(1).
     * </p>
     */
    private static final double LOAD_FACTOR = 0.7;

    /**
     * Size of a Java 64-bit long in bytes.
     */
    private static final long LONG_SIZE_IN_BYTES = 8;

    /**
     * Average byte size allocated per key to size the off-heap raw keys storage buffer.
     * <p>
     * Rationale: Assumes a generous average key size of 128 bytes (such as large UUIDs or
     * long key strings) to ensure the contiguous raw keys memory segment does not overflow.
     * </p>
     */
    private static final long AVERAGE_KEY_SIZE_PADDING = 128;

    private final long maxKeyMemoryMb;
    private final long maxKeyMemoryBytes;
    private final long expectedKeys;
    private final long totalSlots;
    private final Arena arena;
    private final MemorySegment segment; // Flat slot structures memory array
    private final MemorySegment offHeapOffsetsSegment; // Parallel array storing off-heap keyOffsets for each slot
    private final MemorySegment keysSegment; // Flat raw key bytes segment for in-memory comparisons
    
    private long keysWriteOffset = 0;
    private int uniqueKeyCount = 0;
    private long activeKeysMemoryBytes = 0;

    /**
     * Instantiates the off-heap index, pre-allocating memory for the safe load capacity based on max key memory.
     *
     * @param maxKeyMemoryMb the maximum off-heap memory for keys in MB.
     */
    public OffHeapKeyDir(long maxKeyMemoryMb) {
        if (maxKeyMemoryMb <= 0) {
            throw new IllegalArgumentException("Maximum key memory must be positive");
        }
        this.maxKeyMemoryMb = maxKeyMemoryMb;
        this.maxKeyMemoryBytes = maxKeyMemoryMb * 1024L * 1024L;
        
        // Calculate safe expectedKeys using a conservative 24-byte key size estimate for slots
        this.expectedKeys = maxKeyMemoryBytes / 24;
        this.totalSlots = (long) (expectedKeys / LOAD_FACTOR);
        this.arena = Arena.ofShared();
        
        long slotsBytes = totalSlots * SLOT_SIZE;
        this.segment = arena.allocate(slotsBytes);
        this.offHeapOffsetsSegment = arena.allocate(totalSlots * LONG_SIZE_IN_BYTES);
        this.keysSegment = arena.allocate(maxKeyMemoryBytes);
    }

    /**
     * Returns the configured maximum key memory in MB.
     *
     * @return maximum key memory in MB.
     */
    public long getMaxKeyMemoryMb() {
        return maxKeyMemoryMb;
    }

    /**
     * Returns the total memory currently occupied by unique active keys in bytes.
     *
     * @return active keys memory in bytes.
     */
    public long getActiveKeysMemoryBytes() {
        return activeKeysMemoryBytes;
    }

    private void checkClosed() {
        if (!arena.scope().isAlive()) {
            throw new IllegalStateException("OffHeapKeyDir has been closed");
        }
    }

    /**
     * Highly efficient 64-bit FNV-1a non-cryptographic hash for key lookups.
     */
    private long hash(byte[] key) {
        long hash = FNV_64_INIT;
        for (byte b : key) {
            hash ^= b;
            hash *= FNV_64_PRIME;
        }
        if (hash == 0L || hash == TOMBSTONE_HASH) {
            hash = SAFE_FALLBACK_HASH;
        }
        return hash;
    }

    /**
     * Returns the current number of unique keys indexed in memory.
     *
     * @return the unique key count.
     */
    public int getUniqueKeyCount() {
        checkClosed();
        return uniqueKeyCount;
    }

    private long findSlotOffset(byte[] key, long h) {
        long baseIndex = Math.abs(h) % totalSlots;
        long index = baseIndex;

        while (true) {
            long slotOffset = index * SLOT_SIZE;
            long storedHash = segment.get(ValueLayout.JAVA_LONG, slotOffset + HASH_OFFSET);

            if (storedHash == 0L) {
                // Empty slot, key does not exist
                return -1;
            }

            if (storedHash == TOMBSTONE_HASH) {
                // Deleted slot, continue probing
                index = (index + 1) % totalSlots;
                continue;
            }

            if (storedHash == h) {
                short storedKeySize = segment.get(ValueLayout.JAVA_SHORT, slotOffset + KEY_SIZE_OFFSET);
                if (storedKeySize == key.length) {
                    long offHeapKeyOffset = offHeapOffsetsSegment.get(ValueLayout.JAVA_LONG, index * LONG_SIZE_IN_BYTES);
                    
                    boolean match = true;
                    for (int i = 0; i < key.length; i++) {
                        byte b = keysSegment.get(ValueLayout.JAVA_BYTE, offHeapKeyOffset + i);
                        if (b != key[i]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        return slotOffset;
                    }
                }
            }

            index = (index + 1) % totalSlots;
            if (index == baseIndex) {
                return -1; // Fully probed
            }
        }
    }

    private long findInsertionSlotOffset(byte[] key, long h) {
        long baseIndex = Math.abs(h) % totalSlots;
        long index = baseIndex;

        while (true) {
            long slotOffset = index * SLOT_SIZE;
            long storedHash = segment.get(ValueLayout.JAVA_LONG, slotOffset + HASH_OFFSET);

            // Empty or tombstone slot is eligible for insertion
            if (storedHash == 0L || storedHash == TOMBSTONE_HASH) {
                return slotOffset;
            }

            if (storedHash == h) {
                short storedKeySize = segment.get(ValueLayout.JAVA_SHORT, slotOffset + KEY_SIZE_OFFSET);
                if (storedKeySize == key.length) {
                    long offHeapKeyOffset = offHeapOffsetsSegment.get(ValueLayout.JAVA_LONG, index * LONG_SIZE_IN_BYTES);
                    
                    boolean match = true;
                    for (int i = 0; i < key.length; i++) {
                        byte b = keysSegment.get(ValueLayout.JAVA_BYTE, offHeapKeyOffset + i);
                        if (b != key[i]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        return slotOffset; // Return existing slot to overwrite/update
                    }
                }
            }

            index = (index + 1) % totalSlots;
            if (index == baseIndex) {
                throw new IllegalStateException("Database index is completely full");
            }
        }
    }

    /**
     * Inserts or updates a key offset mapping in the off-heap array.
     *
     * @param key         the lookup key in bytes.
     * @param fileId      the segment file ID.
     * @param valueSize   the byte size of the value.
     * @param valueOffset the file offset of the value.
     * @param keyOffset   the file offset of the key.
     * @param timestamp   the epoch timestamp of the transaction.
     * @throws IllegalStateException if a new key is added and index capacity is fully exhausted.
     */
    public void put(byte[] key, int fileId, int valueSize, long valueOffset, long keyOffset, long timestamp) {
        checkClosed();
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        
        long h = hash(key);
        long existingSlotOffset = findSlotOffset(key, h);

        if (existingSlotOffset != -1) {
            // Update existing slot metadata
            segment.set(ValueLayout.JAVA_INT, existingSlotOffset + FILE_ID_OFFSET, fileId);
            segment.set(ValueLayout.JAVA_INT, existingSlotOffset + VAL_SIZE_OFFSET, valueSize);
            segment.set(ValueLayout.JAVA_LONG, existingSlotOffset + VAL_OFFSET_OFFSET, valueOffset);
            segment.set(ValueLayout.JAVA_LONG, existingSlotOffset + KEY_OFFSET_OFFSET, keyOffset);
            segment.set(ValueLayout.JAVA_LONG, existingSlotOffset + TS_OFFSET, timestamp);
        } else {
            // Insert new key
            if (activeKeysMemoryBytes + key.length > maxKeyMemoryBytes) {
                throw new VulcanoKeyMemoryLimitExceededException(
                    "Database key memory limit exceeded. Configured limit: " + maxKeyMemoryBytes + " bytes, attempted to add key of size: " + key.length + " bytes, current usage: " + activeKeysMemoryBytes + " bytes.",
                    activeKeysMemoryBytes,
                    maxKeyMemoryBytes,
                    key.length
                );
            }
            if (uniqueKeyCount >= expectedKeys) {
                throw new IllegalStateException("Database index capacity exceeded. Expected key limit reached.");
            }

            long insertSlotOffset = findInsertionSlotOffset(key, h);
            long slotIndex = insertSlotOffset / SLOT_SIZE;
            
            // Append key to off-heap keys segment for in-memory resolution
            long kOffset = keysWriteOffset;
            if (kOffset + key.length > keysSegment.byteSize()) {
                throw new IllegalStateException("Off-heap keys storage buffer exceeded");
            }
            
            for (int i = 0; i < key.length; i++) {
                keysSegment.set(ValueLayout.JAVA_BYTE, kOffset + i, key[i]);
            }
            keysWriteOffset += key.length;

            // Populate the parallel array mapping slots to key offsets
            offHeapOffsetsSegment.set(ValueLayout.JAVA_LONG, slotIndex * LONG_SIZE_IN_BYTES, kOffset);

            // Populate slot segment fields
            segment.set(ValueLayout.JAVA_LONG, insertSlotOffset + HASH_OFFSET, h);
            segment.set(ValueLayout.JAVA_INT, insertSlotOffset + FILE_ID_OFFSET, fileId);
            segment.set(ValueLayout.JAVA_INT, insertSlotOffset + VAL_SIZE_OFFSET, valueSize);
            segment.set(ValueLayout.JAVA_LONG, insertSlotOffset + VAL_OFFSET_OFFSET, valueOffset);
            segment.set(ValueLayout.JAVA_LONG, insertSlotOffset + KEY_OFFSET_OFFSET, keyOffset);
            segment.set(ValueLayout.JAVA_LONG, insertSlotOffset + TS_OFFSET, timestamp);
            segment.set(ValueLayout.JAVA_SHORT, insertSlotOffset + KEY_SIZE_OFFSET, (short) key.length);

            uniqueKeyCount++;
            activeKeysMemoryBytes += key.length;
        }
    }

    /**
     * Retrieves the indexing slot metadata associated with the key.
     *
     * @param key the lookup key in bytes.
     * @return the {@link Slot} metadata if found; null otherwise.
     */
    public Slot get(byte[] key) {
        checkClosed();
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        long h = hash(key);
        long slotOffset = findSlotOffset(key, h);

        if (slotOffset == -1) {
            return null;
        }

        int fileId = segment.get(ValueLayout.JAVA_INT, slotOffset + FILE_ID_OFFSET);
        int valueSize = segment.get(ValueLayout.JAVA_INT, slotOffset + VAL_SIZE_OFFSET);
        long valueOffset = segment.get(ValueLayout.JAVA_LONG, slotOffset + VAL_OFFSET_OFFSET);
        long keyOffset = segment.get(ValueLayout.JAVA_LONG, slotOffset + KEY_OFFSET_OFFSET);
        long timestamp = segment.get(ValueLayout.JAVA_LONG, slotOffset + TS_OFFSET);

        return new Slot(fileId, valueSize, valueOffset, keyOffset, timestamp);
    }

    /**
     * Removes the key mapping from the index, writing a tombstone to preserve linear probing chains.
     *
     * @param key the lookup key in bytes.
     * @return true if the key existed and was marked deleted; false otherwise.
     */
    public boolean remove(byte[] key) {
        checkClosed();
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        long h = hash(key);
        long slotOffset = findSlotOffset(key, h);

        if (slotOffset == -1) {
            return false;
        }

        int keySize = segment.get(ValueLayout.JAVA_SHORT, slotOffset + KEY_SIZE_OFFSET) & 0xFFFF;

        // Write tombstone hash to preserve collision lookup chains
        segment.set(ValueLayout.JAVA_LONG, slotOffset + HASH_OFFSET, TOMBSTONE_HASH);
        
        uniqueKeyCount--;
        activeKeysMemoryBytes -= keySize;
        return true;
    }

    /**
     * Safely closes the confined FFM Arena, releasing all allocated off-heap native memory back to the OS.
     */
    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
