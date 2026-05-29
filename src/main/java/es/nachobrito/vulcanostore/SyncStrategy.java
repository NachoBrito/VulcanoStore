package es.nachobrito.vulcanostore;

/**
 * Defines the synchronization strategy for flushing MappedByteBuffer dirty pages to physical disk.
 */
public enum SyncStrategy {
    /**
     * Call force() after every write operation. Restricts write throughput to disk latency but guarantees absolute durability.
     */
    SYNC_ALWAYS,
    
    /**
     * Trigger background force() flushes periodically at a configured interval.
     */
    SYNC_INTERVAL,
    
    /**
     * Let the OS manage dirty page flushing asynchronously. Provides maximum execution speed.
     */
    SYNC_NONE
}
