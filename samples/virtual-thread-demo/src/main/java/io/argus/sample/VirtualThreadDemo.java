package io.argus.sample;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Virtual Thread Demo Application
 *
 * This sample demonstrates various virtual thread scenarios that Argus can monitor:
 * 1. Basic virtual thread creation and execution
 * 2. Concurrent HTTP requests using virtual threads
 * 3. Thread pinning scenarios (synchronized blocks)
 * 4. High-throughput virtual thread creation
 *
 * Run with Argus:
 *   ./gradlew :samples:virtual-thread-demo:runWithArgus
 */
public class VirtualThreadDemo {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("  Virtual Thread Demo - Argus Monitoring Example");
        System.out.println("=".repeat(60));
        System.out.println();

        // Demo 1: Basic Virtual Threads
        demoBasicVirtualThreads();

        Thread.sleep(1000);

        // Demo 2: Concurrent HTTP Requests
        demoConcurrentHttpRequests();

        Thread.sleep(1000);

        // Demo 3: Thread Pinning (synchronized)
        demoThreadPinning();

        Thread.sleep(1000);

        // Demo 4: High-throughput Virtual Threads
        demoHighThroughput();

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("  Demo completed! Check Argus output for captured events.");
        System.out.println("=".repeat(60));
    }

    /**
     * Demo 1: Basic virtual thread creation
     * Shows VIRTUAL_THREAD_START and VIRTUAL_THREAD_END events
     */
    private static void demoBasicVirtualThreads() throws Exception {
        System.out.println("[Demo 1] Basic Virtual Threads");
        System.out.println("-".repeat(40));

        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            final int taskId = i;
            Thread vt = Thread.startVirtualThread(() -> {
                System.out.printf("  Task %d started on %s%n", taskId, Thread.currentThread());
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.printf("  Task %d completed%n", taskId);
            });
            threads.add(vt);
        }

        for (Thread t : threads) {
            t.join();
        }

        System.out.println("  All basic tasks completed.");
        System.out.println();
    }

    /**
     * Demo 2: Concurrent HTTP requests with virtual threads
     * Shows efficient I/O handling with virtual threads
     */
    private static void demoConcurrentHttpRequests() throws Exception {
        System.out.println("[Demo 2] Concurrent HTTP Requests");
        System.out.println("-".repeat(40));

        String[] urls = {
                "https://httpbin.org/delay/1",
                "https://httpbin.org/get",
                "https://httpbin.org/headers",
                "https://httpbin.org/ip",
                "https://httpbin.org/user-agent"
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();

            for (String url : urls) {
                futures.add(executor.submit(() -> {
                    System.out.printf("  Fetching: %s on %s%n", url, Thread.currentThread());
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();

                    HttpResponse<String> response = HTTP_CLIENT.send(request,
                            HttpResponse.BodyHandlers.ofString());

                    return String.format("  [%d] %s (%d bytes)",
                            response.statusCode(),
                            url,
                            response.body().length());
                }));
            }

            System.out.println("  Results:");
            for (Future<String> future : futures) {
                try {
                    System.out.println(future.get());
                } catch (Exception e) {
                    System.out.println("  [ERROR] " + e.getMessage());
                }
            }
        }

        System.out.println();
    }

    /**
     * Demo 3: Thread pinning demonstration
     * Shows VIRTUAL_THREAD_PINNED events (critical for performance monitoring)
     *
     * WARNING: synchronized blocks can cause virtual thread pinning!
     */
    private static void demoThreadPinning() throws Exception {
        System.out.println("[Demo 3] Thread Pinning (synchronized blocks)");
        System.out.println("-".repeat(40));
        System.out.println("  WARNING: This demonstrates thread pinning - avoid in production!");

        Object lock = new Object();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            final int taskId = i;
            Thread vt = Thread.startVirtualThread(() -> {
                System.out.printf("  Task %d attempting to acquire lock...%n", taskId);

                // This synchronized block will CAUSE THREAD PINNING
                // Argus will capture VIRTUAL_THREAD_PINNED events
                synchronized (lock) {
                    System.out.printf("  Task %d acquired lock (PINNED to carrier thread)%n", taskId);
                    try {
                        Thread.sleep(200); // Sleep while holding lock = definite pinning
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.printf("  Task %d releasing lock%n", taskId);
                }
            });
            threads.add(vt);
        }

        for (Thread t : threads) {
            t.join();
        }

        System.out.println("  Thread pinning demo completed.");
        System.out.println("  >> Check Argus output for PINNED events with stack traces!");
        System.out.println();
    }

    /**
     * Demo 4: High-throughput virtual thread creation
     * Shows Argus handling many events efficiently
     */
    private static void demoHighThroughput() throws Exception {
        System.out.println("[Demo 4] High-throughput Virtual Threads");
        System.out.println("-".repeat(40));

        int threadCount = 1000;
        System.out.printf("  Creating %d virtual threads...%n", threadCount);

        long startTime = System.currentTimeMillis();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final int taskId = i;
                futures.add(executor.submit(() -> {
                    // Simulate some work
                    double result = 0;
                    for (int j = 0; j < 1000; j++) {
                        result += Math.sin(j) * Math.cos(j);
                    }
                    return result;
                }));
            }

            // Wait for all to complete
            for (Future<?> future : futures) {
                future.get();
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("  Completed %d tasks in %d ms%n", threadCount, elapsed);
        System.out.printf("  Throughput: %.2f tasks/second%n", threadCount * 1000.0 / elapsed);
        System.out.println();
    }
}
