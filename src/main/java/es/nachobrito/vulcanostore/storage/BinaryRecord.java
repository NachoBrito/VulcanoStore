package es.nachobrito.vulcanostore.storage;

import java.nio.ByteBuffer;

/**
 * Represents a single database transaction record serialized on disk inside data log segment files.
 *
 * @param timestamp epoch millisecond timestamp of the write operation.
 * @param key       raw key bytes.
 * @param value     raw value bytes. Can be null to represent a tombstone (deletion) marker.
 * @param expiry    epoch millisecond absolute timestamp for key expiration (0 if no expiry).
 */
public record BinaryRecord(
    long timestamp,
    byte[] key,
    byte[] value,
    long expiry
) {

    /**
     * Checks if this record is a tombstone, representing a deleted key.
     *
     * @return true if the value is null; false otherwise.
     */
    public boolean isTombstone() {
        return value == null;
    }

    /**
     * Serializes this record into a flat byte array according to the storage specification.
     *
     * @return a serialized byte array containing the header and payload.
     */
    public byte[] serialize() {
        int keyLen = this.key().length;
        int valLen = this.isTombstone() ? 0 : this.value().length;
        int totalSize = 8 + 2 + 4 + 8 + keyLen + valLen; // timestamp(8) + keySize(2) + valSize(4) + expiry(8) + payload

        byte[] data = new byte[totalSize];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        
        buffer.putLong(this.timestamp());
        buffer.putShort((short) keyLen);
        buffer.putInt(this.isTombstone() ? -1 : valLen);
        buffer.putLong(this.expiry());
        buffer.put(this.key());
        if (!this.isTombstone()) {
            buffer.put(this.value());
        }
        
        return data;
    }

    /**
     * Deserializes a binary record from a byte buffer at its current position.
     *
     * @param buffer the {@link ByteBuffer} containing the serialized record.
     * @return a reconstructed {@link BinaryRecord} instance.
     */
    public static BinaryRecord deserialize(ByteBuffer buffer) {
        long timestamp = buffer.getLong();
        int keyLen = buffer.getShort() & 0xFFFF;
        int valLen = buffer.getInt();
        long expiry = buffer.getLong();

        byte[] key = new byte[keyLen];
        buffer.get(key);

        byte[] value = null;
        if (valLen != -1) {
            value = new byte[valLen];
            buffer.get(value);
        }

        return new BinaryRecord(timestamp, key, value, expiry);
    }
}
