package io.argus.aggregator;

import io.argus.aggregator.profile.ProfileStore;
import io.argus.aggregator.profile.ProfileStoreConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfileStoreTest {

    private static ProfileStoreConfig config(Path dir) {
        // 60s window, 24h retention — defaults rooted at the temp dir.
        return ProfileStoreConfig.at(dir);
    }

    @Test
    void mergeSumsAcrossWindowsInRange(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(config(dir));
        long t0 = Instant.parse("2026-05-28T00:00:00Z").toEpochMilli();
        long t1 = t0 + Duration.ofSeconds(90).toMillis(); // next window

        store.append("pod-a", "svc", "cpu", t0, Map.of("main;a;b", 10L, "main;c", 5L));
        store.append("pod-a", "svc", "cpu", t1, Map.of("main;a;b", 3L, "main;d", 7L));

        Map<String, Long> merged = store.merged("pod-a", "cpu",
                Instant.ofEpochMilli(t0), Instant.ofEpochMilli(t1 + 60_000));

        assertEquals(13L, merged.get("main;a;b"), "same stack across windows sums");
        assertEquals(5L, merged.get("main;c"));
        assertEquals(7L, merged.get("main;d"));
    }

    @Test
    void mergeSumsRepeatedAppendsWithinSameWindow(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(config(dir));
        long t = Instant.parse("2026-05-28T00:00:00Z").toEpochMilli();

        store.append("pod-a", "svc", "cpu", t, Map.of("main;x", 4L));
        store.append("pod-a", "svc", "cpu", t + 1000, Map.of("main;x", 6L)); // same 60s window

        Map<String, Long> merged = store.merged("pod-a", "cpu",
                Instant.ofEpochMilli(t), Instant.ofEpochMilli(t + 60_000));
        assertEquals(10L, merged.get("main;x"));
    }

    @Test
    void mergeFiltersByPodAndEventAndRange(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(config(dir));
        long t = Instant.parse("2026-05-28T00:00:00Z").toEpochMilli();

        store.append("pod-a", "svc", "cpu", t, Map.of("s", 1L));
        store.append("pod-b", "svc", "cpu", t, Map.of("s", 2L));   // other pod
        store.append("pod-a", "svc", "alloc", t, Map.of("s", 4L)); // other event
        long far = t + Duration.ofHours(2).toMillis();
        store.append("pod-a", "svc", "cpu", far, Map.of("s", 8L)); // out of range

        Map<String, Long> merged = store.merged("pod-a", "cpu",
                Instant.ofEpochMilli(t), Instant.ofEpochMilli(t + 60_000));
        assertEquals(1L, merged.get("s"), "only pod-a/cpu in-range counted");
    }

    @Test
    void evictOlderThanRemovesOldWindows(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(config(dir));
        long oldT = Instant.parse("2026-05-28T00:00:00Z").toEpochMilli();
        long newT = oldT + Duration.ofHours(3).toMillis();

        store.append("pod-a", "svc", "cpu", oldT, Map.of("old", 1L));
        store.append("pod-a", "svc", "cpu", newT, Map.of("new", 2L));

        store.evictOlderThan(Instant.ofEpochMilli(oldT + Duration.ofHours(1).toMillis()));

        Map<String, Long> all = store.merged("pod-a", "cpu",
                Instant.ofEpochMilli(oldT), Instant.ofEpochMilli(newT + 60_000));
        assertNull(all.get("old"), "old window evicted");
        assertEquals(2L, all.get("new"), "new window retained");
    }

    @Test
    void survivesRestartByReindexingFromDisk(@TempDir Path dir) {
        long t = Instant.parse("2026-05-28T00:00:00Z").toEpochMilli();
        ProfileStore first = new ProfileStore(config(dir));
        first.append("pod-a", "svc", "cpu", t, Map.of("main;a;b", 11L, "main;c", 22L));

        // Simulate process restart: brand-new store over the same dir.
        ProfileStore reloaded = new ProfileStore(config(dir));
        Map<String, Long> merged = reloaded.merged("pod-a", "cpu",
                Instant.ofEpochMilli(t), Instant.ofEpochMilli(t + 60_000));

        assertEquals(11L, merged.get("main;a;b"));
        assertEquals(22L, merged.get("main;c"));
    }

    @Test
    void roundTripsSpecialCharsInPodAndStack(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(config(dir));
        long t = Instant.parse("2026-05-28T00:00:00Z").toEpochMilli();
        String pod = "ns/pod with space~tilde";
        String stack = "Main.run;com.x.Y.<init>;a/b";

        store.append(pod, "my svc", "cpu", t, Map.of(stack, 9L));

        ProfileStore reloaded = new ProfileStore(config(dir));
        Map<String, Long> merged = reloaded.merged(pod, "cpu",
                Instant.ofEpochMilli(t), Instant.ofEpochMilli(t + 60_000));
        assertEquals(9L, merged.get(stack));
    }
}
