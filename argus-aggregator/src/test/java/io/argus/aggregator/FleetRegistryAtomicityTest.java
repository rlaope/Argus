package io.argus.aggregator;

import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.TileMetrics;
import io.argus.aggregator.store.FleetRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency / atomicity tests for {@link FleetRegistry}. Stress
 * register+scrape on the same {@code podId} from many threads and assert
 * that the registry never loses the registration or interleaves the
 * read-modify-write incorrectly.
 */
class FleetRegistryAtomicityTest {

    @Test
    void registerThenScrapeNeverLoses() throws Exception {
        FleetRegistry reg = new FleetRegistry(60);
        reg.register("ns/pod", "ns", "pod", "dep", "10.0.0.1", 7070);

        int threads = 16;
        int itersPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int seed = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < itersPerThread; j++) {
                        if ((seed + j) % 2 == 0) {
                            reg.register("ns/pod", "ns", "pod", "dep",
                                    "10.0.0." + ((j % 250) + 1), 7000 + (j % 100));
                        } else {
                            reg.recordScrape("ns/pod",
                                    new TileMetrics(50.0, 1.0, 30.0, 100, false),
                                    (j % 3) != 0);
                        }
                        if (reg.get("ns/pod") == null) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "lost registration or threw under concurrent compute");

        PodTarget t = reg.get("ns/pod");
        assertNotNull(t);
        assertEquals("ns/pod", t.podId());
        assertNotNull(t.registeredAt());
    }

    @Test
    void registerPreservesOriginalRegisteredAt() {
        FleetRegistry reg = new FleetRegistry(60);
        var first = reg.register("ns/pod", "ns", "pod", "dep", "10.0.0.1", 7070);
        Instant originalAt = first.target().registeredAt();
        assertFalse(first.updated());

        var second = reg.register("ns/pod", "ns", "pod", "dep", "10.0.0.99", 9999);
        assertTrue(second.updated());
        assertEquals(originalAt, second.target().registeredAt(),
                "update must preserve original registeredAt");
        assertEquals("http://10.0.0.99:9999", second.target().scrapeUrl());
    }

    @Test
    void recordScrapeAfterDeregisterIsNoop() {
        FleetRegistry reg = new FleetRegistry(60);
        reg.register("ns/pod", "ns", "pod", "dep", "10.0.0.1", 7070);
        reg.deregister("ns/pod");
        // Should not throw; should not resurrect the target.
        reg.recordScrape("ns/pod",
                new TileMetrics(50.0, 1.0, 30.0, 100, false), true);
        assertNull(reg.get("ns/pod"));
        assertNull(reg.latestMetrics("ns/pod"));
    }
}
