package io.argus.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics Demo - Generates GC, CPU, and Virtual Thread activity.
 *
 * This demo creates:
 * - Memory pressure to trigger GC events
 * - CPU load from computation
 * - Virtual threads with various lifetimes
 *
 * Run with:
 *   ./gradlew :samples:virtual-thread-simulation:runMetricsDemo
 *
 * Dashboard:
 *   http://localhost:9202/
 *
 * API Endpoints:
 *   curl http://localhost:9202/gc-analysis | jq
 *   curl http://localhost:9202/cpu-metrics | jq
 */
public class MetricsDemo {

    private static volatile boolean running = true;
    private static final Random random = new Random();
    private static final AtomicLong totalAllocated = new AtomicLong(0);
    private static final AtomicLong gcCount = new AtomicLong(0);

    public static void main(String[] args) throws InterruptedException {
        printBanner();

        int durationSeconds = Integer.getInteger("duration", 60);
        System.out.printf("Running for %d seconds (use -Dduration=N to change)%n%n", durationSeconds);

        CountDownLatch startLatch = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        // 1. Memory Pressure Threads - Allocates and discards objects to trigger frequent GC
        for (int m = 0; m < 3; m++) {
            final int memId = m;
            Thread memoryThread = Thread.ofVirtual()
                    .name("memory-pressure-" + memId)
                    .start(() -> {
                        await(startLatch);
                        System.out.printf("[Memory-%d] Starting memory pressure generator...%n", memId);

                        List<byte[]> tempStorage = new ArrayList<>();

                        while (running) {
                            try {
                                // Allocate 2-8 MB chunks more frequently
                                int size = (2 + random.nextInt(7)) * 1024 * 1024;
                                byte[] chunk = new byte[size];

                                // Fill with random data to prevent optimization
                                random.nextBytes(chunk);
                                tempStorage.add(chunk);
                                totalAllocated.addAndGet(size);

                                // Keep only last 5 chunks per thread (~40MB max per thread)
                                while (tempStorage.size() > 5) {
                                    tempStorage.remove(0);
                                }

                                // Faster allocation = more GC pressure
                                Thread.sleep(50 + random.nextInt(100));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            } catch (OutOfMemoryError e) {
                                tempStorage.clear();
                                gcCount.incrementAndGet();
                            }
                        }
                    });
            threads.add(memoryThread);
        }

        // 2. CPU Intensive Threads - Creates high CPU load
        int cpuWorkerCount = Runtime.getRuntime().availableProcessors();
        System.out.printf("[CPU] Spawning %d CPU workers for maximum load...%n", cpuWorkerCount);

        for (int i = 0; i < cpuWorkerCount; i++) {
            final int id = i;
            Thread cpuThread = Thread.ofVirtual()
                    .name("cpu-worker-" + id)
                    .start(() -> {
                        await(startLatch);
                        System.out.printf("[CPU-%d] Starting CPU worker...%n", id);

                        while (running) {
                            // CPU intensive: calculate more primes (higher range = more CPU)
                            long count = countPrimes(50000 + random.nextInt(20000));

                            // Very short pause - keeps CPU high
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }

                            // Prevent optimization
                            if (count < 0) System.out.println(count);
                        }
                    });
            threads.add(cpuThread);
        }

        // 2b. Additional CPU burst threads for spikes
        for (int i = 0; i < 2; i++) {
            final int id = i;
            Thread burstThread = Thread.ofVirtual()
                    .name("cpu-burst-" + id)
                    .start(() -> {
                        await(startLatch);
                        while (running) {
                            try {
                                // Burst: intense computation for 2 seconds
                                long endTime = System.currentTimeMillis() + 2000;
                                while (System.currentTimeMillis() < endTime && running) {
                                    countPrimes(100000);
                                }
                                // Rest for 3 seconds
                                Thread.sleep(3000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    });
            threads.add(burstThread);
        }

        // 3. Short-lived Virtual Threads - Creates START/END events
        Thread spawnerThread = Thread.ofVirtual()
                .name("thread-spawner")
                .start(() -> {
                    await(startLatch);
                    System.out.println("[Spawner] Starting virtual thread spawner...");

                    while (running) {
                        try {
                            // Spawn 5-10 short-lived threads
                            int count = 5 + random.nextInt(6);
                            List<Thread> shortLived = new ArrayList<>();

                            for (int i = 0; i < count; i++) {
                                final int taskId = i;
                                Thread t = Thread.ofVirtual()
                                        .name("task-" + System.currentTimeMillis() + "-" + taskId)
                                        .start(() -> {
                                            // Simulate short task
                                            try {
                                                Thread.sleep(random.nextInt(100));
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                        });
                                shortLived.add(t);
                            }

                            // Wait for all to complete
                            for (Thread t : shortLived) {
                                t.join();
                            }

                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                });
        threads.add(spawnerThread);

        // 4. Pinning Thread - Creates PINNED events (synchronized blocks)
        Thread pinningThread = Thread.ofVirtual()
                .name("pinning-worker")
                .start(() -> {
                    await(startLatch);
                    System.out.println("[Pinning] Starting pinning worker...");

                    Object lock = new Object();

                    while (running) {
                        try {
                            // This will cause virtual thread pinning
                            synchronized (lock) {
                                Thread.sleep(50 + random.nextInt(100));
                            }
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                });
        threads.add(pinningThread);

        // Start all threads
        System.out.println("Starting all workers...");
        System.out.println();
        startLatch.countDown();

        // Status printer
        Thread statusThread = Thread.ofVirtual()
                .name("status-printer")
                .start(() -> {
                    await(startLatch);
                    while (running) {
                        try {
                            Thread.sleep(5000);
                            printStatus();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                });
        threads.add(statusThread);

        // Run for specified duration
        Thread.sleep(durationSeconds * 1000L);

        // Shutdown
        System.out.println();
        System.out.println("Shutting down...");
        running = false;

        for (Thread t : threads) {
            t.join(2000);
        }

        printFinalStats();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long countPrimes(int max) {
        long count = 0;
        for (int n = 2; n <= max; n++) {
            if (isPrime(n)) count++;
        }
        return count;
    }

    private static boolean isPrime(int n) {
        if (n < 2) return false;
        if (n == 2) return true;
        if (n % 2 == 0) return false;
        for (int i = 3; i * i <= n; i += 2) {
            if (n % i == 0) return false;
        }
        return true;
    }

    private static void printBanner() {
        System.out.println("=".repeat(60));
        System.out.println("  Argus Metrics Demo");
        System.out.println("  - Memory Pressure (triggers GC)");
        System.out.println("  - CPU Load (4 workers)");
        System.out.println("  - Virtual Thread Spawning");
        System.out.println("  - Pinning Events");
        System.out.println("=".repeat(60));
        System.out.println();
        System.out.println("Endpoints:");
        System.out.println("  Dashboard:    http://localhost:9202/");
        System.out.println("  GC Analysis:  http://localhost:9202/gc-analysis");
        System.out.println("  CPU Metrics:  http://localhost:9202/cpu-metrics");
        System.out.println("  Metrics:      http://localhost:9202/metrics");
        System.out.println();
    }

    private static void printStatus() {
        long allocated = totalAllocated.get();
        String allocStr = formatBytes(allocated);

        Runtime rt = Runtime.getRuntime();
        long usedMem = rt.totalMemory() - rt.freeMemory();
        long maxMem = rt.maxMemory();

        System.out.printf("[Status] Allocated: %s | Heap: %s / %s | GC triggered: %d times%n",
                allocStr,
                formatBytes(usedMem),
                formatBytes(maxMem),
                gcCount.get());
    }

    private static void printFinalStats() {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Final Statistics:");
        System.out.printf("  Total Allocated: %s%n", formatBytes(totalAllocated.get()));
        System.out.printf("  OOM Events: %d%n", gcCount.get());
        System.out.println("=".repeat(60));
        System.out.println("Done.");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
