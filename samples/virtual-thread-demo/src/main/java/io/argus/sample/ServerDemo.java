package io.argus.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server Demo - Long-running application for dashboard testing.
 *
 * This demo runs continuously, generating virtual thread events at regular intervals.
 * Includes frequent pinning scenarios to test Carrier Thread monitoring.
 *
 * Run with:
 *   ./gradlew :samples:virtual-thread-demo:runServerDemo
 *
 * Dashboard: http://localhost:9202/
 *
 * Features:
 *   - Continuous virtual thread creation (every 1 second)
 *   - Long-running background workers (visible in Thread View)
 *   - Frequent pinning events (every 2 seconds)
 *   - Varied thread durations for realistic monitoring
 */
public class ServerDemo {

    private static final AtomicInteger taskCounter = new AtomicInteger(0);
    private static final AtomicInteger workerCounter = new AtomicInteger(0);
    private static volatile boolean running = true;
    private static final Object SHARED_LOCK = new Object();
    private static final List<Thread> backgroundWorkers = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Argus Server Demo - Continuous Virtual Thread Generator");
        System.out.println("=".repeat(60));
        System.out.println();
        System.out.println("Dashboard: http://localhost:9202/");
        System.out.println();
        System.out.println("This demo generates:");
        System.out.println("  - Short-lived threads every 1 second");
        System.out.println("  - Long-running background workers");
        System.out.println("  - Pinning events every 2 seconds");
        System.out.println();
        System.out.println("Press Enter to stop...");
        System.out.println();

        // Start long-running background workers (always visible in Thread View)
        startBackgroundWorkers(5);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        // Short-lived threads every 1 second
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            int batch = taskCounter.incrementAndGet();
            createShortLivedThreads(batch);
        }, 0, 1, TimeUnit.SECONDS);

        // Pinning scenario every 2 seconds
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            int batch = taskCounter.get();
            System.out.printf("[Batch %d] PINNING: Creating pinned threads...%n", batch);
            createPinningScenario();
        }, 1, 2, TimeUnit.SECONDS);

        // Wait for Enter key
        try (Scanner scanner = new Scanner(System.in)) {
            scanner.nextLine();
        }

        running = false;
        scheduler.shutdown();

        // Stop background workers
        for (Thread t : backgroundWorkers) {
            t.interrupt();
        }

        System.out.println("Shutting down...");
    }

    /**
     * Creates long-running background workers that stay alive.
     */
    private static void startBackgroundWorkers(int count) {
        System.out.printf("Starting %d background workers...%n", count);

        for (int i = 0; i < count; i++) {
            int workerId = workerCounter.incrementAndGet();
            Thread worker = Thread.ofVirtual()
                    .name("bg-worker-" + workerId)
                    .start(() -> {
                        System.out.printf("  [Worker %d] Started%n", workerId);
                        while (running) {
                            try {
                                // Simulate periodic work
                                doWork();
                                Thread.sleep(2000 + (long)(Math.random() * 1000));

                                // Occasionally cause pinning
                                if (Math.random() < 0.3) {
                                    synchronized (SHARED_LOCK) {
                                        Thread.sleep(50);
                                    }
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        System.out.printf("  [Worker %d] Stopped%n", workerId);
                    });
            backgroundWorkers.add(worker);
        }
    }

    /**
     * Creates short-lived threads with varying durations.
     */
    private static void createShortLivedThreads(int batch) {
        int count = 3 + (int)(Math.random() * 5); // 3-7 threads

        for (int i = 0; i < count; i++) {
            final int taskId = i;
            Thread.ofVirtual()
                    .name("task-" + batch + "-" + taskId)
                    .start(() -> {
                        try {
                            // Random duration: 100ms - 2s
                            long duration = 100 + (long)(Math.random() * 1900);
                            Thread.sleep(duration);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
        }
    }

    /**
     * Creates multiple threads that will be pinned.
     */
    private static void createPinningScenario() {
        Object localLock = new Object();

        // Create 2-4 threads that will be pinned
        int count = 2 + (int)(Math.random() * 3);

        for (int i = 0; i < count; i++) {
            final int pinnedId = i;
            Thread.ofVirtual()
                    .name("pinned-" + System.currentTimeMillis() % 10000 + "-" + pinnedId)
                    .start(() -> {
                        // This synchronized block causes PINNING
                        synchronized (localLock) {
                            try {
                                // Sleep while holding lock = guaranteed pinning
                                Thread.sleep(100 + (long)(Math.random() * 200));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
        }
    }

    private static void doWork() {
        // Simulate CPU work
        long sum = 0;
        for (int i = 0; i < 10000; i++) {
            sum += i;
        }
        if (sum < 0) System.out.println(sum);
    }
}
