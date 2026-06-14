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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests asserting single-segment logging, MappedByteBuffer append offsets, and direct reads.
 */
public class FileSegmentTest {

    @Test
    public void testAppendAndRead(@TempDir Path tempDir) throws IOException {
        Path segmentPath = tempDir.resolve("00000001.data");
        long capacity = 1024 * 1024; // 1MB

        try (FileSegment segment = new FileSegment(1, segmentPath, capacity)) {
            assertEquals(1, segment.getFileId());
            assertEquals(segmentPath, segment.getFilePath());

            byte[] key1 = "key-1".getBytes();
            byte[] value1 = "value-1".getBytes();
            BinaryRecord record1 = new BinaryRecord(System.currentTimeMillis(), key1, value1, 0);

            // Write first record
            long offset1 = segment.append(record1);
            assertEquals(0, offset1);

            // Read first record back
            BinaryRecord read1 = segment.read(offset1);
            assertEquals(record1.timestamp(), read1.timestamp());
            assertArrayEquals(record1.key(), read1.key());
            assertArrayEquals(record1.value(), read1.value());
            assertEquals(record1.expiry(), read1.expiry());

            // Write second record (tombstone)
            byte[] key2 = "deleted-key".getBytes();
            BinaryRecord record2 = new BinaryRecord(System.currentTimeMillis(), key2, null, 0);
            long offset2 = segment.append(record2);

            // Expected offset2 is the serialized length of record1 (22 + 5 + 7 = 34 bytes)
            assertEquals(34, offset2);

            // Read second record back
            BinaryRecord read2 = segment.read(offset2);
            assertEquals(record2.timestamp(), read2.timestamp());
            assertArrayEquals(record2.key(), read2.key());
            assertNull(read2.value());
            assertTrue(read2.isTombstone());
        }
    }
}
