package io.argus.benchmark;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Overhead Benchmark for Argus Virtual Thread Profiler
 *
 * Measures the performance impact of Argus on virtual thread operations:
 * - Throughput (tasks/second)
 * - Latency (p50, p95, p99, max)
 * - Memory usage
 * - GC overhead
 *
 * Usage:
 *   ./gradlew :samples:benchmark:runBaseline        # Without Argus
 *   ./gradlew :samples:benchmark:runWithArgusAgent  # With Argus agent
 *   ./gradlew :samples:benchmark:runWithArgusServer # With Argus agent + server
 */
public class OverheadBenchmark {

    // Benchmark configuration
    private static final int WARMUP_ITERATIONS = 3;
    private static final int BENCHMARK_ITERATIONS = 5;
    private static final int THREADS_PER_ITERATION = 10_000;
    private static final int WORK_UNITS = 1000;

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("benchmark.mode", "unknown");

        System.out.println("=".repeat(70));
        System.out.println("  Argus Overhead Benchmark");
        System.out.println("=".repeat(70));
        System.out.println();
        System.out.printf("  Mode:                %s%n", mode);
        System.out.printf("  Warmup iterations:   %d%n", WARMUP_ITERATIONS);
        System.out.printf("  Benchmark iterations:%d%n", BENCHMARK_ITERATIONS);
        System.out.printf("  Threads/iteration:   %,d%n", THREADS_PER_ITERATION);
        System.out.printf("  JVM:                 %s %s%n",
                System.getProperty("java.vendor"),
                System.getProperty("java.version"));
        System.out.println();

        // Warmup phase
        System.out.println("[Warmup Phase]");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            System.out.printf("  Warmup %d/%d...%n", i + 1, WARMUP_ITERATIONS);
            runIteration(false);
        }
        System.out.println("  Warmup complete.");
        System.out.println();

        // Force GC before benchmark
        System.gc();
        Thread.sleep(1000);

        // Benchmark phase
        System.out.println("[Benchmark Phase]");
        List<IterationResult> results = new ArrayList<>();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            System.out.printf("  Iteration %d/%d...%n", i + 1, BENCHMARK_ITERATIONS);
            IterationResult result = runIteration(true);
            results.add(result);
            System.out.printf("    Throughput: %,.0f tasks/sec, p99 latency: %.3f ms%n",
                    result.throughput, result.p99Latency);
        }
        System.out.println();

        // Print summary
        printSummary(mode, results);
    }

    private static IterationResult runIteration(boolean collectLatency) throws Exception {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        // Record initial state
        long initialHeapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long initialGcCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long initialGcTime = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();

        // Latency tracking
        long[] latencies = collectLatency ? new long[THREADS_PER_ITERATION] : null;
        AtomicLong latencyIndex = new AtomicLong(0);

        CountDownLatch latch = new CountDownLatch(THREADS_PER_ITERATION);
        long startTime = System.nanoTime();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < THREADS_PER_ITERATION; i++) {
                executor.submit(() -> {
                    long taskStart = System.nanoTime();
                    try {
                        // Simulate CPU work
                        doWork();
                    } finally {
                        if (collectLatency) {
                            long taskEnd = System.nanoTime();
                            int idx = (int) latencyIndex.getAndIncrement();
                            if (idx < latencies.length) {
                                latencies[idx] = taskEnd - taskStart;
                            }
                        }
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }

        long endTime = System.nanoTime();
        long elapsedNanos = endTime - startTime;

        // Record final state
        long finalHeapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long finalGcCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long finalGcTime = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();

        // Calculate metrics
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double throughput = THREADS_PER_ITERATION / elapsedSeconds;

        IterationResult result = new IterationResult();
        result.elapsedMs = elapsedNanos / 1_000_000.0;
        result.throughput = throughput;
        result.heapUsedMb = (finalHeapUsed - initialHeapUsed) / (1024.0 * 1024.0);
        result.gcCount = finalGcCount - initialGcCount;
        result.gcTimeMs = finalGcTime - initialGcTime;

        if (collectLatency && latencies != null) {
            int validCount = (int) Math.min(latencyIndex.get(), latencies.length);
            long[] validLatencies = Arrays.copyOf(latencies, validCount);
            Arrays.sort(validLatencies);

            result.minLatency = validLatencies[0] / 1_000_000.0;
            result.p50Latency = percentile(validLatencies, 50) / 1_000_000.0;
            result.p95Latency = percentile(validLatencies, 95) / 1_000_000.0;
            result.p99Latency = percentile(validLatencies, 99) / 1_000_000.0;
            result.maxLatency = validLatencies[validLatencies.length - 1] / 1_000_000.0;
        }

        return result;
    }

    private static void doWork() {
        // CPU-bound work to simulate real application logic
        double result = 0;
        for (int i = 0; i < WORK_UNITS; i++) {
            result += Math.sin(i) * Math.cos(i) * Math.tan(i % 90);
        }
        // Prevent dead code elimination
        if (result == Double.NaN) {
            throw new RuntimeException("Unexpected NaN");
        }
    }

    private static long percentile(long[] sortedData, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedData.length) - 1;
        return sortedData[Math.max(0, index)];
    }

    private static void printSummary(String mode, List<IterationResult> results) {
        System.out.println("=".repeat(70));
        System.out.println("  BENCHMARK RESULTS");
        System.out.println("=".repeat(70));
        System.out.printf("  Mode: %s%n", mode);
        System.out.println();

        // Calculate averages
        double avgThroughput = results.stream().mapToDouble(r -> r.throughput).average().orElse(0);
        double avgElapsed = results.stream().mapToDouble(r -> r.elapsedMs).average().orElse(0);
        double avgP50 = results.stream().mapToDouble(r -> r.p50Latency).average().orElse(0);
        double avgP95 = results.stream().mapToDouble(r -> r.p95Latency).average().orElse(0);
        double avgP99 = results.stream().mapToDouble(r -> r.p99Latency).average().orElse(0);
        double avgMax = results.stream().mapToDouble(r -> r.maxLatency).average().orElse(0);
        double avgHeap = results.stream().mapToDouble(r -> r.heapUsedMb).average().orElse(0);
        long totalGc = results.stream().mapToLong(r -> r.gcCount).sum();
        long totalGcTime = results.stream().mapToLong(r -> r.gcTimeMs).sum();

        // Standard deviation for throughput
        double stdDev = Math.sqrt(results.stream()
                .mapToDouble(r -> Math.pow(r.throughput - avgThroughput, 2))
                .average().orElse(0));

        System.out.println("  [Throughput]");
        System.out.printf("    Average:     %,.0f tasks/sec%n", avgThroughput);
        System.out.printf("    Std Dev:     %,.0f tasks/sec%n", stdDev);
        System.out.printf("    Total Time:  %.2f ms (avg per iteration)%n", avgElapsed);
        System.out.println();

        System.out.println("  [Latency (per task)]");
        System.out.printf("    p50:         %.3f ms%n", avgP50);
        System.out.printf("    p95:         %.3f ms%n", avgP95);
        System.out.printf("    p99:         %.3f ms%n", avgP99);
        System.out.printf("    max:         %.3f ms%n", avgMax);
        System.out.println();

        System.out.println("  [Memory & GC]");
        System.out.printf("    Heap delta:  %.2f MB (avg per iteration)%n", avgHeap);
        System.out.printf("    GC count:    %d (total across all iterations)%n", totalGc);
        System.out.printf("    GC time:     %d ms (total)%n", totalGcTime);
        System.out.println();

        // Machine-readable output for scripting
        System.out.println("-".repeat(70));
        System.out.println("  [CSV Output]");
        System.out.printf("  %s,%.0f,%.0f,%.3f,%.3f,%.3f,%.2f,%d,%d%n",
                mode, avgThroughput, stdDev, avgP50, avgP95, avgP99, avgHeap, totalGc, totalGcTime);
        System.out.println("  (mode,throughput,stddev,p50_ms,p95_ms,p99_ms,heap_mb,gc_count,gc_time_ms)");
        System.out.println("=".repeat(70));
    }

    static class IterationResult {
        double elapsedMs;
        double throughput;
        double minLatency;
        double p50Latency;
        double p95Latency;
        double p99Latency;
        double maxLatency;
        double heapUsedMb;
        long gcCount;
        long gcTimeMs;
    }
}
