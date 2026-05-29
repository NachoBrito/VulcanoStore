package es.nachobrito.vulcanostore;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD validation tests for configuration inputs.
 */
public class VulcanoConfigTest {

    @Test
    public void testValidConfiguration() {
        Path tempPath = Paths.get("/tmp/vulcanodb-test");
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempPath)
                .segmentSize(64 * 1024 * 1024)
                .expectedKeys(5_000_000)
                .syncStrategy(SyncStrategy.SYNC_ALWAYS)
                .syncIntervalMs(100)
                .build();

        assertEquals(tempPath, config.getDbPath());
        assertEquals(64 * 1024 * 1024, config.getSegmentSize());
        assertEquals(5_000_000, config.getExpectedKeys());
        assertEquals(SyncStrategy.SYNC_ALWAYS, config.getSyncStrategy());
        assertEquals(100, config.getSyncIntervalMs());
    }

    @Test
    public void testDbPathCannotBeNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(null)
                    .build();
        });
    }

    @Test
    public void testSegmentSizeMustBePositive() {
        Path tempPath = Paths.get("/tmp/vulcanodb-test");
        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .segmentSize(0)
                    .build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .segmentSize(-100)
                    .build();
        });
    }

    @Test
    public void testExpectedKeysMustBePositive() {
        Path tempPath = Paths.get("/tmp/vulcanodb-test");
        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .expectedKeys(0)
                    .build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .expectedKeys(-5)
                    .build();
        });
    }

    @Test
    public void testSyncStrategyCannotBeNull() {
        Path tempPath = Paths.get("/tmp/vulcanodb-test");
        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .syncStrategy(null)
                    .build();
        });
    }

    @Test
    public void testSyncIntervalMustBePositive() {
        Path tempPath = Paths.get("/tmp/vulcanodb-test");
        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .syncIntervalMs(0)
                    .build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .syncIntervalMs(-10)
                    .build();
        });
    }
}
