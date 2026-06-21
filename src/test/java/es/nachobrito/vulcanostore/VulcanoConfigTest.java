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

package es.nachobrito.vulcanostore;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                .maxKeyMemoryMb(256)
                .syncStrategy(SyncStrategy.SYNC_ALWAYS)
                .syncIntervalMs(100)
                .build();

        assertEquals(tempPath, config.getDbPath());
        assertEquals(64 * 1024 * 1024, config.getSegmentSize());
        assertEquals(256, config.getMaxKeyMemoryMb());
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
    public void testMaxKeyMemoryMustBePositive() {
        Path tempPath = Paths.get("/tmp/vulcanodb-test");
        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .maxKeyMemoryMb(0)
                    .build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .maxKeyMemoryMb(-5)
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

    @Test
    public void testAverageKeySizeDefaultValue() {
        Path tempPath = Paths.get("/tmp/vulcanodb-test");
        VulcanoConfig config = VulcanoConfig.builder()
                .dbPath(tempPath)
                .build();
        assertEquals(36, config.getAverageKeySize());
    }

    @Test
    public void testAverageKeySizeMustBePositive() {
        Path tempPath = Paths.get("/tmp/vulcanodb-test");
        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .averageKeySize(0)
                    .build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            VulcanoConfig.builder()
                    .dbPath(tempPath)
                    .averageKeySize(-5)
                    .build();
        });
    }
}

