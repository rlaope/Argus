package io.argus.aggregator;

import io.argus.aggregator.http.AggregatorChannelHandler;
import io.argus.aggregator.http.FleetController;
import io.argus.aggregator.http.ProfileController;
import io.argus.aggregator.http.PrometheusMetricsExporter;
import io.argus.aggregator.profile.ProfileStore;
import io.argus.aggregator.profile.ProfileStoreConfig;
import io.argus.aggregator.scrape.ScrapeLoop;
import io.argus.aggregator.store.FleetRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link ProfileController} through a real {@link AggregatorChannelHandler}
 * on an {@link EmbeddedChannel} (same harness as {@code FleetControllerValidationTest})
 * over a {@link ProfileStore} rooted at a {@link TempDir}.
 *
 * <p>Covers the ingest → query roundtrip (leaf counts sum to ingested totals) and
 * the differential flamegraph (per-frame deltas, including head-only and base-only
 * frames). JSON is inspected with substring / regex assertions rather than a parser
 * to stay dependency-free, matching the aggregator's hand-built JSON ethos.
 */
class ProfileControllerTest {

    private static EmbeddedChannel newChannel(Path dir) {
        FleetRegistry registry = new FleetRegistry(60);
        ScrapeLoop loop = new ScrapeLoop(registry, 5, r -> {});
        PrometheusMetricsExporter prom = new PrometheusMetricsExporter(registry, loop);
        ProfileStore store = new ProfileStore(ProfileStoreConfig.at(dir));
        FleetController controller = new FleetController(registry, prom, new ProfileController(store));
        return new EmbeddedChannel(new AggregatorChannelHandler(controller));
    }

    private static FullHttpResponse send(EmbeddedChannel ch, FullHttpRequest req) {
        ch.writeInbound(req);
        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp, "no outbound response produced");
        return resp;
    }

    private static FullHttpResponse post(EmbeddedChannel ch, String path, String body) {
        return send(ch, new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, path,
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8)));
    }

    private static FullHttpResponse get(EmbeddedChannel ch, String path) {
        return send(ch, new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path));
    }

    private static String bodyOf(FullHttpResponse resp) {
        return resp.content().toString(CharsetUtil.UTF_8);
    }

    /** Extracts the synthetic-root inclusive value from a query flamegraph JSON. */
    private static long rootValue(String json) {
        Matcher m = Pattern.compile("\\{\"name\":\"root\",\"value\":(-?\\d+),").matcher(json);
        assertTrue(m.find(), "no root node in: " + json);
        return Long.parseLong(m.group(1));
    }

    @Test
    void ingestThenQueryRoundtripSumsToIngestedTotals(@TempDir Path dir) {
        EmbeddedChannel ch = newChannel(dir);
        long ts = 1_716_854_400_000L; // fixed epoch millis (2024-05-28T00:00:00Z)

        // collapsed text uses \n (JSON-escaped) between records.
        String collapsed = "main;a;b 10\\nmain;a;c 5\\nmain;d 3";
        FullHttpResponse ingest = post(ch, "/profile/ingest",
                "{\"pod\":\"ns/pod-1\",\"service\":\"svc\",\"event\":\"cpu\","
              + "\"timestamp\":\"" + ts + "\",\"collapsed\":\"" + collapsed + "\"}");
        assertEquals(HttpResponseStatus.OK, ingest.status(), bodyOf(ingest));
        assertTrue(bodyOf(ingest).contains("\"samples\":18"), bodyOf(ingest));

        // Query a window covering ts.
        long from = ts - 1000;
        long to = ts + 60_000;
        FullHttpResponse query = get(ch,
                "/profile/query?pod=" + enc("ns/pod-1") + "&event=cpu&from=" + from + "&to=" + to);
        assertEquals(HttpResponseStatus.OK, query.status(), bodyOf(query));
        String json = bodyOf(query);

        // The synthetic root's inclusive value equals the ingested total (18).
        assertEquals(18L, rootValue(json), json);
        // Leaf counts present.
        assertTrue(json.contains("{\"name\":\"b\",\"value\":10,\"children\":[]}"), json);
        assertTrue(json.contains("{\"name\":\"c\",\"value\":5,\"children\":[]}"), json);
        assertTrue(json.contains("{\"name\":\"d\",\"value\":3,\"children\":[]}"), json);
    }

    @Test
    void queryEmptyWindowYieldsZeroRoot(@TempDir Path dir) {
        EmbeddedChannel ch = newChannel(dir);
        FullHttpResponse query = get(ch,
                "/profile/query?pod=" + enc("ns/pod-1") + "&event=cpu&from=0&to=1000");
        assertEquals(HttpResponseStatus.OK, query.status());
        assertEquals(0L, rootValue(bodyOf(query)));
    }

    @Test
    void diffComputesPerFrameDeltasAcrossWindows(@TempDir Path dir) {
        EmbeddedChannel ch = newChannel(dir);
        long baseTs = 1_716_854_400_000L;             // base window
        long headTs = baseTs + 3_600_000L;            // head window, 1h later

        // Base: main;a=10, main;gone=5
        post(ch, "/profile/ingest",
                "{\"pod\":\"ns/pod-1\",\"event\":\"cpu\",\"timestamp\":\"" + baseTs + "\","
              + "\"collapsed\":\"main;a 10\\nmain;gone 5\"}");
        // Head: main;a=6, main;new=8
        post(ch, "/profile/ingest",
                "{\"pod\":\"ns/pod-1\",\"event\":\"cpu\",\"timestamp\":\"" + headTs + "\","
              + "\"collapsed\":\"main;a 6\\nmain;new 8\"}");

        String q = "/profile/diff?pod=" + enc("ns/pod-1") + "&event=cpu"
                + "&baseFrom=" + (baseTs - 1000) + "&baseTo=" + (baseTs + 60_000)
                + "&headFrom=" + (headTs - 1000) + "&headTo=" + (headTs + 60_000);
        FullHttpResponse diff = get(ch, q);
        assertEquals(HttpResponseStatus.OK, diff.status(), bodyOf(diff));
        String json = bodyOf(diff);

        // Root: head=14, base=15, delta=-1.
        assertTrue(json.contains("\"name\":\"root\",\"value\":14,\"head\":14,\"base\":15,\"delta\":-1,"), json);
        // a shrank: base 10 -> head 6 (delta -4).
        assertTrue(json.contains("{\"name\":\"a\",\"value\":6,\"head\":6,\"base\":10,\"delta\":-4,"), json);
        // new only in head (delta +8).
        assertTrue(json.contains("{\"name\":\"new\",\"value\":8,\"head\":8,\"base\":0,\"delta\":8,"), json);
        // gone only in base (delta -5).
        assertTrue(json.contains("{\"name\":\"gone\",\"value\":0,\"head\":0,\"base\":5,\"delta\":-5,"), json);
    }

    @Test
    void ingestRejectsMissingPodOrEvent(@TempDir Path dir) {
        EmbeddedChannel ch = newChannel(dir);
        FullHttpResponse r1 = post(ch, "/profile/ingest",
                "{\"event\":\"cpu\",\"collapsed\":\"a 1\"}");
        assertEquals(HttpResponseStatus.BAD_REQUEST, r1.status());

        EmbeddedChannel ch2 = newChannel(dir);
        FullHttpResponse r2 = post(ch2, "/profile/ingest",
                "{\"pod\":\"ns/pod-1\",\"collapsed\":\"a 1\"}");
        assertEquals(HttpResponseStatus.BAD_REQUEST, r2.status());
    }

    @Test
    void queryRejectsMissingParams(@TempDir Path dir) {
        EmbeddedChannel ch = newChannel(dir);
        FullHttpResponse resp = get(ch, "/profile/query?pod=" + enc("ns/pod-1"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
