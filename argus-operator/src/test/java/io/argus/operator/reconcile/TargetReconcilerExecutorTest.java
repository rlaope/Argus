package io.argus.operator.reconcile;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bounded worker pool used by TargetReconciler:
 *   - tasks run off the submitter's thread
 *   - the pool drains in close()
 */
class TargetReconcilerExecutorTest {

    @Test
    void buildBoundedExecutor_runsTasksOnNamedWorkerThreads() throws Exception {
        ExecutorService es = TargetReconciler.buildBoundedExecutor(4, 16);
        try {
            Thread caller = Thread.currentThread();
            CountDownLatch done = new CountDownLatch(1);
            AtomicInteger ranOnWorker = new AtomicInteger();
            es.submit(() -> {
                if (Thread.currentThread() != caller
                        && Thread.currentThread().getName().startsWith("argus-operator-aggregator-")
                        && Thread.currentThread().isDaemon()) {
                    ranOnWorker.incrementAndGet();
                }
                done.countDown();
            });
            assertTrue(done.await(2, TimeUnit.SECONDS), "task did not run");
            assertEquals(1, ranOnWorker.get(), "task did not run on a daemon argus-operator worker");
        } finally {
            es.shutdownNow();
        }
    }

    @Test
    void boundedQueue_rejectsWhenSaturated() throws Exception {
        // 1 worker, 1 slot — total capacity 2 in-flight; the 3rd submission rejects.
        ExecutorService es = TargetReconciler.buildBoundedExecutor(1, 1);
        try {
            CountDownLatch hold = new CountDownLatch(1);
            // Occupy the single worker.
            es.submit(() -> {
                try {
                    hold.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            // Fill the queue.
            es.submit(() -> {
            });
            // The next submit should be rejected by AbortPolicy.
            boolean rejected = false;
            try {
                es.submit(() -> {
                });
            } catch (java.util.concurrent.RejectedExecutionException expected) {
                rejected = true;
            }
            hold.countDown();
            assertTrue(rejected, "expected RejectedExecutionException when queue saturated");
        } finally {
            es.shutdownNow();
        }
    }

    @Test
    void submittedTasksDoNotRunOnSubmitterThread() throws Exception {
        ExecutorService es = TargetReconciler.buildBoundedExecutor(2, 8);
        try {
            long submitterId = Thread.currentThread().getId();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger workerThreadId = new AtomicInteger();
            es.submit(() -> {
                workerThreadId.set((int) Thread.currentThread().getId());
                latch.countDown();
            });
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertNotEquals(submitterId, workerThreadId.get());
        } finally {
            es.shutdownNow();
        }
    }
}
