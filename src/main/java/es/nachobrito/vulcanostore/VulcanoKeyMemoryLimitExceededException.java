package es.nachobrito.vulcanostore;

/**
 * Thrown when the off-heap memory occupied by unique active keys exceeds the configured limit.
 * <p>
 * This exception extends {@link IllegalStateException} to maintain compatibility with existing
 * database capacity error states while allowing client code to explicitly catch and handle
 * key memory limit exhaustion.
 * </p>
 */
public class VulcanoKeyMemoryLimitExceededException extends IllegalStateException {
    
    private final long currentMemoryBytes;
    private final long limitBytes;
    private final int keyLength;

    /**
     * Constructs a new limit exceeded exception.
     *
     * @param message            the detailed error message.
     * @param currentMemoryBytes the total memory currently occupied by active keys in bytes.
     * @param limitBytes         the maximum configured key memory limit in bytes.
     * @param keyLength          the byte length of the key that triggered the limit violation.
     */
    public VulcanoKeyMemoryLimitExceededException(String message, long currentMemoryBytes, long limitBytes, int keyLength) {
        super(message);
        this.currentMemoryBytes = currentMemoryBytes;
        this.limitBytes = limitBytes;
        this.keyLength = keyLength;
    }

    /**
     * Returns the total memory currently occupied by active keys in bytes.
     *
     * @return the current key memory usage in bytes.
     */
    public long getCurrentMemoryBytes() {
        return currentMemoryBytes;
    }

    /**
     * Returns the maximum configured key memory limit in bytes.
     *
     * @return the memory limit in bytes.
     */
    public long getLimitBytes() {
        return limitBytes;
    }

    /**
     * Returns the byte length of the key that triggered the limit violation.
     *
     * @return the offending key length in bytes.
     */
    public int getKeyLength() {
        return keyLength;
    }
}
