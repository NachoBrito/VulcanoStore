package es.nachobrito.vulcanodb.storage;

import es.nachobrito.vulcanodb.VulcanoConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Coordinates active and inactive file segments inside the VulcanoStore database.
 * <p>
 * This manager handles sequential append logging, active segment file rollovers
 * when capacities are exceeded, offset-based reads, and directory scanning on boot.
 * </p>
 */
public class StorageEngine implements AutoCloseable {

    /**
     * Represents the exact byte offset coordinate mapping returned upon successful writes.
     */
    public record WriteResult(
        int fileId,
        int valueSize,
        long valueOffset,
        long keyOffset,
        long timestamp
    ) {}

    private final VulcanoConfig config;
    private final Map<Integer, FileSegment> inactiveSegments;
    
    private int activeFileId;
    private FileSegment activeSegment;

    /**
     * Instantiates the storage manager, scanning the configured database folder.
     *
     * @param config the validated {@link VulcanoConfig} configurations.
     * @throws IOException if a low-level file scanning or directory access error occurs.
     */
    public StorageEngine(VulcanoConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }
        this.config = config;
        this.inactiveSegments = new HashMap<>();

        Path dbPath = config.getDbPath();
        if (dbPath != null) {
            Files.createDirectories(dbPath);
        }

        // Scan directory on boot to locate existing segment files
        int maxFileId = 0;
        try (var stream = Files.list(dbPath)) {
            var dataFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches("\\d{8}\\.data"))
                .sorted((p1, p2) -> p1.getFileName().toString().compareTo(p2.getFileName().toString()))
                .toList();

            if (!dataFiles.isEmpty()) {
                String lastFileName = dataFiles.get(dataFiles.size() - 1).getFileName().toString();
                maxFileId = Integer.parseInt(lastFileName.substring(0, 8));
            }
        }

        // Start active segment: if files exist, we start with maxFileId + 1. Otherwise start with 1.
        this.activeFileId = maxFileId + 1;
        Path activePath = dbPath.resolve(String.format("%08d.data", activeFileId));
        this.activeSegment = new FileSegment(activeFileId, activePath, config.getSegmentSize());
    }

    /**
     * Appends a record sequentially. If the active segment is full, triggers an active rollover.
     *
     * @param record the {@link BinaryRecord} to log.
     * @return the {@link WriteResult} coordinates containing file ID and offset positions.
     * @throws IOException if a low-level write error occurs.
     */
    public WriteResult write(BinaryRecord record) throws IOException {
        if (record == null) {
            throw new IllegalArgumentException("Record cannot be null");
        }

        byte[] serialized = record.serialize();
        long recordSize = serialized.length;

        if (recordSize > config.getSegmentSize()) {
            throw new IOException("Record size " + recordSize + " is larger than segment size limit " + config.getSegmentSize());
        }

        // Check if active segment has enough space; trigger rollover if full
        if (activeSegment.getWriteOffset() + recordSize > config.getSegmentSize()) {
            // Rollover: close current active segment
            activeSegment.close();
            
            // Map the closed segment as inactive
            inactiveSegments.put(activeFileId, new FileSegment(activeFileId, activeSegment.getFilePath(), config.getSegmentSize()));

            // Open new active segment with incremented ID
            activeFileId++;
            Path newActivePath = config.getDbPath().resolve(String.format("%08d.data", activeFileId));
            activeSegment = new FileSegment(activeFileId, newActivePath, config.getSegmentSize());
        }

        long offset = activeSegment.append(record);

        int keyLen = record.key().length;
        int valLen = record.isTombstone() ? 0 : record.value().length;

        long keyOffset = offset + 22;
        long valueOffset = offset; // In Bitcask, the index valueOffset points to the start of the record

        return new WriteResult(
            activeFileId,
            valLen,
            valueOffset,
            keyOffset,
            record.timestamp()
        );
    }

    /**
     * Reads a record from the specified file segment at its byte offset coordinate.
     *
     * @param fileId the segment file ID.
     * @param offset the start offset where the record begins.
     * @return the reconstructed {@link BinaryRecord}.
     * @throws IOException if a low-level read error occurs or the segment does not exist.
     */
    public BinaryRecord read(int fileId, long offset) throws IOException {
        FileSegment segment = getOrLoadSegment(fileId);
        return segment.read(offset);
    }

    private FileSegment getOrLoadSegment(int fileId) throws IOException {
        if (fileId == activeFileId) {
            return activeSegment;
        }
        FileSegment seg = inactiveSegments.get(fileId);
        if (seg == null) {
            Path filePath = config.getDbPath().resolve(String.format("%08d.data", fileId));
            if (!Files.exists(filePath)) {
                throw new IOException("Segment file not found for file ID: " + fileId);
            }
            seg = new FileSegment(fileId, filePath, config.getSegmentSize());
            inactiveSegments.put(fileId, seg);
        }
        return seg;
    }

    /**
     * Rebuilds the in-memory off-heap index by sequentially scanning all segment files.
     *
     * @param index the {@link OffHeapKeyDir} to populate.
     * @throws IOException if a low-level file scanning or directory access error occurs.
     */
    public void recover(OffHeapKeyDir index) throws IOException {
        if (index == null) {
            throw new IllegalArgumentException("Index cannot be null");
        }

        Path dbPath = config.getDbPath();
        try (var stream = Files.list(dbPath)) {
            var dataFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches("\\d{8}\\.data"))
                .sorted((p1, p2) -> p1.getFileName().toString().compareTo(p2.getFileName().toString()))
                .toList();

            for (Path path : dataFiles) {
                String fileName = path.getFileName().toString();
                int fileId = Integer.parseInt(fileName.substring(0, 8));

                // Skip the current active segment file since it is newly initialized
                if (fileId == activeFileId) {
                    continue;
                }

                recoverSegment(path, fileId, index);
            }
        }
    }

    private void recoverSegment(Path filePath, int fileId, OffHeapKeyDir index) throws IOException {
        long capacity = Files.size(filePath);
        if (capacity < 22) {
            return;
        }

        try (FileSegment segment = new FileSegment(fileId, filePath, capacity)) {
            long offset = 0;
            while (offset + 22 <= capacity) {
                try {
                    BinaryRecord record = segment.read(offset);
                    if (record.timestamp() == 0 && record.key().length == 0) {
                        break;
                    }

                    byte[] key = record.key();
                    int keyLen = key.length;
                    int valLen = record.isTombstone() ? 0 : record.value().length;
                    int totalSize = 22 + keyLen + valLen;

                    long keyOffset = offset + 22;
                    long valueOffset = offset; // In Bitcask, the index valueOffset points to the start of the record

                    if (record.isTombstone()) {
                        index.remove(key);
                    } else {
                        index.put(key, fileId, valLen, valueOffset, keyOffset, record.timestamp());
                    }

                    offset += totalSize;
                } catch (Exception e) {
                    break;
                }
            }
        }
    }

    /**
     * Safely closes the active and inactive segment file channels and locks.
     *
     * @throws IOException if a low-level close error occurs.
     */
    @Override
    public void close() throws IOException {
        if (activeSegment != null) {
            activeSegment.close();
        }
        for (FileSegment seg : inactiveSegments.values()) {
            if (seg != null) {
                seg.close();
            }
        }
        inactiveSegments.clear();
    }
}
