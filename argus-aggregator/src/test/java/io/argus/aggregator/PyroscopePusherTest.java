package io.argus.aggregator;

import io.argus.aggregator.profile.ProfileStore;
import io.argus.aggregator.profile.ProfileStoreConfig;
import io.argus.aggregator.profile.PyroscopeProfileType;
import io.argus.aggregator.profile.PyroscopePushConfig;
import io.argus.aggregator.profile.PyroscopePusher;
import io.argus.aggregator.profile.PyroscopeTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PyroscopePusherTest {

    /** Records every request instead of hitting a server. */
    private static final class RecordingTransport implements PyroscopeTransport {
        final List<PushRequest> requests = new ArrayList<>();
        int status = 200;

        @Override
        public int send(PushRequest request) {
            requests.add(request);
            return status;
        }
    }

    /** Always throws, to exercise failure degradation. */
    private static final class ThrowingTransport implements PyroscopeTransport {
        int calls = 0;

        @Override
        public int send(PushRequest request) throws IOException {
            calls++;
            throw new IOException("connection refused");
        }
    }

    private static final long TS = Instant.parse("2026-05-28T00:00:00Z").toEpochMilli();

    // ── AC2: event → Pyroscope profile-type mapping ────────────────────────────

    @Test
    void mapsEventTypesToPyroscopeProfileTypes() {
        assertEquals("process_cpu", PyroscopeProfileType.forEvent("cpu"));
        assertEquals("memory:alloc_space:bytes", PyroscopeProfileType.forEvent("alloc"));
        assertEquals("mutex:contentions:count", PyroscopeProfileType.forEvent("lock"));
        assertEquals("wall", PyroscopeProfileType.forEvent("wall"));
        // Unknown types pass through verbatim.
        assertEquals("itimer", PyroscopeProfileType.forEvent("itimer"));
    }

    // ── AC1/AC2: request URL, label set, and folded body shape ──────────────────

    @Test
    void buildsIngestUrlLabelSetAndFoldedBody(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(ProfileStoreConfig.at(dir));
        PyroscopePushConfig cfg = new PyroscopePushConfig("http://pyroscope:4040/", "us-east");
        RecordingTransport transport = new RecordingTransport();
        PyroscopePusher pusher = new PyroscopePusher(store, cfg, transport);

        pusher.appendAndPush("ns/pod-a", "checkout", "cpu", TS,
                Map.of("main;work", 7L));

        assertEquals(1, transport.requests.size(), "one push for one window");
        PyroscopeTransport.PushRequest req = transport.requests.get(0);

        long from = TS; // window start (TS is already aligned to 00:00:00)
        long until = from + 60_000;
        assertTrue(req.url().startsWith("http://pyroscope:4040/ingest?name="),
                "URL: " + req.url());
        assertTrue(req.url().contains("&from=" + from), "from in URL: " + req.url());
        assertTrue(req.url().contains("&until=" + until), "until in URL: " + req.url());
        assertTrue(req.url().contains("&format=folded"), "format=folded: " + req.url());

        // Decoded label set: service.<type>{service_name=...,pod=...,region=...}
        String name = java.net.URLDecoder.decode(
                req.url().replaceAll(".*[?&]name=([^&]*).*", "$1"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(name.startsWith("checkout.process_cpu{"), "app name + type: " + name);
        assertTrue(name.contains("service_name=\"checkout\""), "service_name label: " + name);
        assertTrue(name.contains("pod=\"ns/pod-a\""), "pod label: " + name);
        assertTrue(name.contains("region=\"us-east\""), "region label: " + name);

        assertEquals("main;work 7\n", req.foldedBody(), "folded body: stack<space>count");
    }

    @Test
    void omitsRegionLabelWhenUnset(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(ProfileStoreConfig.at(dir));
        PyroscopePushConfig cfg = new PyroscopePushConfig("http://pyroscope:4040", null);
        RecordingTransport transport = new RecordingTransport();
        PyroscopePusher pusher = new PyroscopePusher(store, cfg, transport);

        pusher.appendAndPush("ns/pod-a", "svc", "alloc", TS, Map.of("a;b", 3L));

        String name = java.net.URLDecoder.decode(
                transport.requests.get(0).url().replaceAll(".*[?&]name=([^&]*).*", "$1"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(name.startsWith("svc.memory:alloc_space:bytes{"), name);
        assertFalse(name.contains("region="), "no region label when unset: " + name);
    }

    // ── AC3: gating — endpoint unset ⇒ zero push attempts ───────────────────────

    @Test
    void doesNotPushWhenEndpointUnset(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(ProfileStoreConfig.at(dir));
        PyroscopePushConfig cfg = new PyroscopePushConfig(null, null);
        RecordingTransport transport = new RecordingTransport();
        PyroscopePusher pusher = new PyroscopePusher(store, cfg, transport);

        assertFalse(pusher.pushEnabled());
        pusher.appendAndPush("ns/pod-a", "svc", "cpu", TS, Map.of("main;a", 5L));

        assertEquals(0, transport.requests.size(), "no push when endpoint unset");
        assertEquals(0L, pusher.pushAttempts(), "zero push attempts");

        // But the local store still recorded the sample.
        Map<String, Long> merged = store.merged("ns/pod-a", "cpu",
                Instant.ofEpochMilli(TS), Instant.ofEpochMilli(TS + 60_000));
        assertEquals(5L, merged.get("main;a"), "local store still written");
    }

    // ── AC4: push failure degrades to local store, never crashes the loop ───────

    @Test
    void survivesFailingTransportAndKeepsLocalStore(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(ProfileStoreConfig.at(dir));
        PyroscopePushConfig cfg = new PyroscopePushConfig("http://pyroscope:4040", null);
        ThrowingTransport transport = new ThrowingTransport();
        PyroscopePusher pusher = new PyroscopePusher(store, cfg, transport);

        // Must not throw despite the transport always throwing.
        assertDoesNotThrow(() ->
                pusher.appendAndPush("ns/pod-a", "svc", "cpu", TS, Map.of("main;a", 9L)));

        assertEquals(1, transport.calls, "push was attempted");
        assertEquals(1L, pusher.pushAttempts());
        assertEquals(1L, pusher.pushFailures(), "failure counted");
        assertEquals(0L, pusher.pushSuccesses());

        // Local store retained the sample despite the push failure.
        Map<String, Long> merged = store.merged("ns/pod-a", "cpu",
                Instant.ofEpochMilli(TS), Instant.ofEpochMilli(TS + 60_000));
        assertEquals(9L, merged.get("main;a"), "profile degraded to local store");
    }

    @Test
    void countsNon2xxAsFailureButDoesNotThrow(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(ProfileStoreConfig.at(dir));
        PyroscopePushConfig cfg = new PyroscopePushConfig("http://pyroscope:4040", null);
        RecordingTransport transport = new RecordingTransport();
        transport.status = 503;
        PyroscopePusher pusher = new PyroscopePusher(store, cfg, transport);

        assertDoesNotThrow(() ->
                pusher.appendAndPush("ns/pod-a", "svc", "cpu", TS, Map.of("main;a", 1L)));

        assertEquals(1L, pusher.pushAttempts());
        assertEquals(1L, pusher.pushFailures(), "5xx counted as failure");
        assertEquals(0L, pusher.pushSuccesses());
    }
}
