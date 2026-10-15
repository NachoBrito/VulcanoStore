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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Represents a single append-only binary data log segment mapped off-heap using {@code MappedByteBuffer}.
 * <p>
 * This class handles low-level writes and direct offset reads for a single file segment.
 * </p>
 */
public class FileSegment implements AutoCloseable {
    private final int fileId;
    private final Path filePath;
    private final long capacity;

    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final MappedByteBuffer buffer;

    private final boolean readOnly;
    private long writeOffset = 0;

    /**
     * Instantiates a new FileSegment, creating and mapping the target file off-heap in read-write mode.
     *
     * @param fileId   the unique sequential file ID.
     * @param filePath the path to the physical file on disk.
     * @param capacity the maximum byte capacity to allocate for the segment.
     * @throws IOException if a low-level I/O or mapping error occurs.
     */
    public FileSegment(int fileId, Path filePath, long capacity) throws IOException {
        this(fileId, filePath, capacity, false);
    }

    /**
     * Instantiates a new FileSegment, mapping the target file off-heap.
     *
     * @param fileId   the unique sequential file ID.
     * @param filePath the path to the physical file on disk.
     * @param capacity the maximum byte capacity to allocate for the segment.
     * @param readOnly true to open and map the segment in read-only mode; false for read-write.
     * @throws IOException if a low-level I/O or mapping error occurs.
     */
    public FileSegment(int fileId, Path filePath, long capacity, boolean readOnly) throws IOException {
        this.fileId = fileId;
        this.filePath = filePath;
        this.capacity = capacity;
        this.readOnly = readOnly;

        if (!readOnly) {
            // Ensure parent directories exist
            Path parent = filePath.getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            this.raf = new RandomAccessFile(filePath.toFile(), "rw");
            this.channel = raf.getChannel();
            this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, capacity);
        } else {
            this.raf = new RandomAccessFile(filePath.toFile(), "r");
            this.channel = raf.getChannel();
            this.buffer = (capacity > 0) ? channel.map(FileChannel.MapMode.READ_ONLY, 0, capacity) : null;
        }
    }

    /**
     * Returns the unique file ID for this segment.
     *
     * @return the sequential file identifier.
     */
    public int getFileId() {
        return fileId;
    }

    /**
     * Returns the physical file system path of the segment log file.
     *
     * @return the file {@link Path}.
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * Returns the current sequential write offset of the segment.
     *
     * @return the write offset in bytes.
     */
    public long getWriteOffset() {
        return writeOffset;
    }

    /**
     * Sets the sequential write offset for this segment.
     *
     * @param writeOffset the new write offset in bytes.
     */
    public void setWriteOffset(long writeOffset) {
        if (writeOffset < 0 || writeOffset > capacity) {
            throw new IllegalArgumentException("Write offset out of bounds: " + writeOffset);
        }
        this.writeOffset = writeOffset;
    }

    /**
     * Appends a binary record sequentially to the end of the segment.
     *
     * @param record the {@link BinaryRecord} to write.
     * @return the start byte offset of the written record in this segment.
     * @throws IOException if a low-level write error occurs or segment capacity is exceeded.
     */
    public long append(BinaryRecord record) throws IOException {
        if (readOnly) {
            throw new IOException("Cannot append to read-only segment: " + fileId);
        }
        byte[] data = record.serialize();
        if (writeOffset + data.length > capacity) {
            throw new IOException("Segment capacity exceeded for file ID " + fileId);
        }

        long currentOffset = writeOffset;
        buffer.position((int) currentOffset);
        buffer.put(data);
        writeOffset += data.length;

        return currentOffset;
    }

    /**
     * Reads a binary record from the specified byte offset in this segment.
     *
     * @param offset the start byte offset where the record begins.
     * @return the reconstructed {@link BinaryRecord}.
     * @throws IOException if a low-level read error occurs or the offset falls out of bounds.
     */
    public BinaryRecord read(long offset) throws IOException {
        if (offset < 0 || offset >= capacity) {
            throw new IOException("Offset " + offset + " is out of bounds for segment capacity " + capacity);
        }

        // Create a stateless duplicate view to ensure thread-confined isolation during read parsing
        ByteBuffer slice = buffer.duplicate();
        slice.position((int) offset);
        return BinaryRecord.deserialize(slice);
    }

    /**
     * Forces any changes made to this segment's memory-mapped buffer to be written to storage.
     */
    public void force() {
        if (buffer != null) {
            buffer.force();
        }
    }

    /**
     * Flushes dirty pages, closes the file channels, and safely releases resources.
     *
     * @throws IOException if a low-level flush or channel close error occurs.
     */
    @Override
    public void close() throws IOException {
        if (!readOnly && buffer != null) {
            force();
        }
        if (channel != null) {
            channel.close();
        }
        if (raf != null) {
            raf.close();
        }
    }
}
