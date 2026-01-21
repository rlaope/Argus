package io.argus.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

/**
 * Thread Simulation - 10 Virtual Threads running continuously.
 *
 * This demo creates 10 virtual threads that stay alive for monitoring.
 * Use this to test Argus dashboard Thread View.
 *
 * Run with:
 *   ./gradlew :samples:virtual-thread-simulation:runSimulation
 *
 * Run with auto-shutdown (for testing):
 *   ./gradlew :samples:virtual-thread-simulation:runSimulation -Dduration=10
 *
 * Test with curl:
 *   curl http://localhost:8080/health
 *   curl http://localhost:8080/metrics
 *
 * Dashboard:
 *   http://localhost:8080/
 */
public class ThreadSimulation {

    private static final int THREAD_COUNT = 10;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Argus Thread Simulation - 10 Virtual Threads");
        System.out.println("=".repeat(60));
        System.out.println();
        System.out.println("Endpoints:");
        System.out.println("  - Dashboard: http://localhost:8080/");
        System.out.println("  - Metrics:   http://localhost:8080/metrics");
        System.out.println("  - Health:    http://localhost:8080/health");
        System.out.println("  - WebSocket: ws://localhost:8080/events");
        System.out.println();
        System.out.println("Test with curl:");
        System.out.println("  curl http://localhost:8080/metrics | jq");
        System.out.println();

        CountDownLatch startLatch = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        // Create 10 virtual threads
        for (int i = 1; i <= THREAD_COUNT; i++) {
            final int threadNum = i;
            Thread vt = Thread.ofVirtual()
                    .name("worker-" + threadNum)
                    .start(() -> {
                        try {
                            startLatch.await();
                            System.out.printf("[Thread %d] Started%n", threadNum);

                            while (running) {
                                // Simulate periodic work
                                doWork(threadNum);
                                Thread.sleep(1000);
                            }

                            System.out.printf("[Thread %d] Stopping%n", threadNum);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            threads.add(vt);
        }

        // Start all threads
        System.out.printf("Starting %d virtual threads...%n", THREAD_COUNT);
        startLatch.countDown();

        // Check for auto-shutdown duration
        int duration = Integer.getInteger("duration", 0);

        if (duration > 0) {
            System.out.printf("Auto-shutdown in %d seconds...%n", duration);
            System.out.println();
            try {
                Thread.sleep(duration * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            System.out.println();
            System.out.println("Press Enter to stop...");
            System.out.println();

            // Wait for Enter key
            try (Scanner scanner = new Scanner(System.in)) {
                scanner.nextLine();
            }
        }

        // Shutdown
        System.out.println("Shutting down...");
        running = false;

        // Wait for all threads to finish
        for (Thread t : threads) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("Done.");
    }

    private static void doWork(int threadNum) {
        // Simulate some CPU work
        long sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += i * threadNum;
        }
        // Prevent optimization
        if (sum < 0) System.out.println(sum);
    }
}
