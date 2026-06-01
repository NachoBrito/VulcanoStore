package es.nachobrito.vulcanostore.benchmark;

import es.nachobrito.vulcanostore.VulcanoConfig;
import es.nachobrito.vulcanostore.VulcanoStoreImpl;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * GC-centric programmatic microbenchmark suite measuring throughput, latency,
 * and garbage collection activity for VulcanoStore point operations.
 */
public class VulcanoBenchmark {

    private static final int WARMUP_OPS = 20_000;
    private static final int BENCHMARK_OPS = 100_000;
    private static final int KEY_SIZE = 16;
    private static final int VALUE_SIZE = 100;

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("          VULCANOSTORE MICROBENCHMARK             ");
        System.out.println("==================================================");

        Path tempDir = Files.createTempDirectory("vulcanodb-benchmark-");
        try {
            VulcanoConfig config = VulcanoConfig.builder()
                    .dbPath(tempDir)
                    .segmentSize(128 * 1024 * 1024) // 128MB
                    .maxKeyMemoryMb(64)
                    .build();

            System.out.println("Database Directory: " + tempDir.toAbsolutePath());
            System.out.println("Warm-up Operations: " + WARMUP_OPS);
            System.out.println("Benchmark Operations: " + BENCHMARK_OPS);
            System.out.println("Key Size: " + KEY_SIZE + " bytes, Value Size: " + VALUE_SIZE + " bytes");
            System.out.println("--------------------------------------------------");

            try (VulcanoStoreImpl store = new VulcanoStoreImpl(config)) {
                // Generate benchmark keys and values in memory to avoid allocation during benchmark loops
                byte[][] keys = new byte[BENCHMARK_OPS][KEY_SIZE];
                byte[][] values = new byte[BENCHMARK_OPS][VALUE_SIZE];
                Random random = new Random(42);

                for (int i = 0; i < BENCHMARK_OPS; i++) {
                    random.nextBytes(keys[i]);
                    random.nextBytes(values[i]);
                }

                // 1. Warm-up Phase
                System.out.print("Warming up JVM JIT compiler... ");
                for (int i = 0; i < WARMUP_OPS; i++) {
                    store.put(keys[i], values[i]);
                    store.get(keys[i]);
                }
                System.out.println("Done.");

                // Get GC state before benchmark
                long gcCountBefore = getGcCount();
                long gcTimeBefore = getGcTime();

                // 2. Write Benchmark
                System.out.println("Running Write (PUT) Benchmark...");
                long writeStart = System.nanoTime();
                List<Long> writeLatencies = new ArrayList<>(BENCHMARK_OPS);

                for (int i = 0; i < BENCHMARK_OPS; i++) {
                    long opStart = System.nanoTime();
                    store.put(keys[i], values[i]);
                    long opDuration = System.nanoTime() - opStart;
                    writeLatencies.add(opDuration);
                }
                long writeTotalTime = System.nanoTime() - writeStart;

                // 3. Read Benchmark
                System.out.println("Running Read (GET) Benchmark...");
                long readStart = System.nanoTime();
                List<Long> readLatencies = new ArrayList<>(BENCHMARK_OPS);

                for (int i = 0; i < BENCHMARK_OPS; i++) {
                    long opStart = System.nanoTime();
                    store.get(keys[i]);
                    long opDuration = System.nanoTime() - opStart;
                    readLatencies.add(opDuration);
                }
                long readTotalTime = System.nanoTime() - readStart;

                // Get GC state after benchmark
                long gcCountAfter = getGcCount();
                long gcTimeAfter = getGcTime();

                // Calculate metrics
                double writeTotalSec = writeTotalTime / 1_000_000_000.0;
                double writeThroughput = BENCHMARK_OPS / writeTotalSec;
                double writeAvgLatMs = (writeTotalTime / (double) BENCHMARK_OPS) / 1_000_000.0;

                double readTotalSec = readTotalTime / 1_000_000_000.0;
                double readThroughput = BENCHMARK_OPS / readTotalSec;
                double readAvgLatMs = (readTotalTime / (double) BENCHMARK_OPS) / 1_000_000.0;

                long totalGcCount = gcCountAfter - gcCountBefore;
                long totalGcTime = gcTimeAfter - gcTimeBefore;

                Collections.sort(writeLatencies);
                Collections.sort(readLatencies);
                double write99LatMs = writeLatencies.get((int) (BENCHMARK_OPS * 0.99)) / 1_000_000.0;
                double read99LatMs = readLatencies.get((int) (BENCHMARK_OPS * 0.99)) / 1_000_000.0;

                System.out.println("==================================================");
                System.out.println("               BENCHMARK RESULTS                  ");
                System.out.println("==================================================");
                System.out.printf("Write (PUT) Throughput:  %,.2f ops/sec\n", writeThroughput);
                System.out.printf("Write Average Latency:   %.4f ms\n", writeAvgLatMs);
                System.out.printf("Write 99th Percentile:   %.4f ms\n", write99LatMs);
                System.out.println("--------------------------------------------------");
                System.out.printf("Read (GET) Throughput:   %,.2f ops/sec\n", readThroughput);
                System.out.printf("Read Average Latency:    %.4f ms\n", readAvgLatMs);
                System.out.printf("Read 99th Percentile:    %.4f ms\n", read99LatMs);
                System.out.println("--------------------------------------------------");
                System.out.println("               GC MONITOR STATS                   ");
                System.out.println("--------------------------------------------------");
                System.out.println("JVM GC Collections:      " + totalGcCount);
                System.out.println("JVM GC Pause Duration:   " + totalGcTime + " ms");
                System.out.println("==================================================");
            }
        } finally {
            // Cleanup database files after run
            deleteDirectory(tempDir);
        }
    }

    private static long getGcCount() {
        long count = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            if (c != -1) {
                count += c;
            }
        }
        return count;
    }

    private static long getGcTime() {
        long time = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = bean.getCollectionTime();
            if (t != -1) {
                time += t;
            }
        }
        return time;
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Collections.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }
}
