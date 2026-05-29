package es.nachobrito.vulcanostore.storage;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests asserting binary serialization/deserialization fidelity for data log records.
 */
public class BinaryRecordTest {

    @Test
    public void testSerializationFidelity() {
        long timestamp = System.currentTimeMillis();
        byte[] key = "my-test-key".getBytes();
        byte[] value = "my-test-value-that-is-slightly-longer-to-test-byte-fidelity".getBytes();
        long expiry = timestamp + 10000;

        BinaryRecord record = new BinaryRecord(timestamp, key, value, expiry);
        assertFalse(record.isTombstone());

        byte[] serialized = record.serialize();
        assertNotNull(serialized);

        // Expected header size: timestamp (8) + keySize (2) + valueSize (4) + expiry (8) = 22 bytes
        // Total expected size = 22 + key.length + value.length
        int expectedSize = 22 + key.length + value.length;
        assertEquals(expectedSize, serialized.length);

        ByteBuffer buffer = ByteBuffer.wrap(serialized);
        BinaryRecord deserialized = BinaryRecord.deserialize(buffer);

        assertEquals(timestamp, deserialized.timestamp());
        assertArrayEquals(key, deserialized.key());
        assertArrayEquals(value, deserialized.value());
        assertEquals(expiry, deserialized.expiry());
        assertFalse(deserialized.isTombstone());
    }

    @Test
    public void testTombstoneSerialization() {
        long timestamp = System.currentTimeMillis();
        byte[] key = "tombstone-key".getBytes();
        byte[] value = null; // Represents deletion
        long expiry = 0;

        BinaryRecord record = new BinaryRecord(timestamp, key, value, expiry);
        assertTrue(record.isTombstone());

        byte[] serialized = record.serialize();
        assertNotNull(serialized);

        // Total expected size = 22 + key.length + 0 (value is null, valueSize = -1)
        int expectedSize = 22 + key.length;
        assertEquals(expectedSize, serialized.length);

        ByteBuffer buffer = ByteBuffer.wrap(serialized);
        BinaryRecord deserialized = BinaryRecord.deserialize(buffer);

        assertEquals(timestamp, deserialized.timestamp());
        assertArrayEquals(key, deserialized.key());
        assertNull(deserialized.value());
        assertEquals(expiry, deserialized.expiry());
        assertTrue(deserialized.isTombstone());
    }
}
