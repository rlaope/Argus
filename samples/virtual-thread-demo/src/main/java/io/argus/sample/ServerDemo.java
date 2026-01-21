package io.argus.sample;

import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server Demo - Long-running application for WebSocket testing.
 *
 * This demo runs continuously, generating virtual thread events at regular intervals.
 * Use this to test the WebSocket server connection.
 *
 * Run with:
 *   ./gradlew :samples:virtual-thread-demo:runServerDemo
 *
 * Then connect via WebSocket:
 *   wscat -c ws://localhost:9202/events
 *
 * Or check health:
 *   curl http://localhost:9202/health
 */
public class ServerDemo {

    private static final AtomicInteger taskCounter = new AtomicInteger(0);
    private static volatile boolean running = true;

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Argus Server Demo - Long-running Virtual Thread Generator");
        System.out.println("=".repeat(60));
        System.out.println();
        System.out.println("Server endpoints:");
        System.out.println("  - WebSocket: ws://localhost:9202/events");
        System.out.println("  - Health:    http://localhost:9202/health");
        System.out.println();
        System.out.println("Press Enter to stop...");
        System.out.println();

        // Schedule periodic virtual thread creation
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // Generate events every 2 seconds
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;

            int batch = taskCounter.incrementAndGet();
            System.out.printf("[Batch %d] Creating virtual threads...%n", batch);

            // Create some virtual threads
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 5; i++) {
                    final int taskId = i;
                    executor.submit(() -> {
                        // Simulate work
                        try {
                            Thread.sleep(50 + (long)(Math.random() * 100));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            }

            // Occasionally create a pinning scenario
            if (batch % 5 == 0) {
                System.out.printf("[Batch %d] Creating pinning scenario...%n", batch);
                createPinningScenario();
            }

        }, 0, 2, TimeUnit.SECONDS);

        // Wait for Enter key
        try (Scanner scanner = new Scanner(System.in)) {
            scanner.nextLine();
        }

        running = false;
        scheduler.shutdown();
        System.out.println("Shutting down...");
    }

    private static void createPinningScenario() {
        Object lock = new Object();

        Thread.startVirtualThread(() -> {
            synchronized (lock) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }
}
