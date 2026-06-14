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
