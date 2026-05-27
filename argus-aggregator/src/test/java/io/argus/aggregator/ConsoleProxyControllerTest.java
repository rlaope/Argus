package io.argus.aggregator;

import io.argus.aggregator.http.AggregatorChannelHandler;
import io.argus.aggregator.http.FleetController;
import io.argus.aggregator.http.PodHttpClient;
import io.argus.aggregator.http.PrometheusMetricsExporter;
import io.argus.aggregator.scrape.ScrapeLoop;
import io.argus.aggregator.store.FleetRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the {@code /api/pods}, {@code /api/pods/{id}/commands}, and
 * {@code POST /api/exec} routes — the proxy that lets the browser console
 * pick a pod from the fleet and run diagnostic commands against it.
 *
 * <p>A stub {@link PodHttpClient} captures forwarded URLs and returns canned
 * responses, so the test does not bind a socket.
 */
class ConsoleProxyControllerTest {

    /** Records every forwarded {@code baseUrl + path} and returns a canned response. */
    private static final class StubPodClient extends PodHttpClient {
        final List<String> calls = new ArrayList<>();
        final List<String> postCalls = new ArrayList<>();
        int status = 200;
        String body = "{}";
        boolean throwError = false;

        @Override
        public Response get(String baseUrl, String path) throws ProxyException {
            calls.add(baseUrl + path);
            if (throwError) throw new ProxyException("ConnectException: refused", new RuntimeException());
            return new Response(status, body);
        }

        @Override
        public Response post(String baseUrl, String path, String body, String contentType)
                throws ProxyException {
            String url = baseUrl + path;
            calls.add(url);
            postCalls.add(url);
            if (throwError) throw new ProxyException("ConnectException: refused", new RuntimeException());
            return new Response(status, this.body);
        }
    }

    private static final class Harness {
        final FleetRegistry registry = new FleetRegistry(60);
        final StubPodClient stub = new StubPodClient();
        final EmbeddedChannel channel;

        Harness() {
            ScrapeLoop loop = new ScrapeLoop(registry, 5, r -> {});
            PrometheusMetricsExporter prom = new PrometheusMetricsExporter(registry, loop);
            FleetController controller = new FleetController(registry, prom, stub);
            channel = new EmbeddedChannel(new AggregatorChannelHandler(controller));
        }

        void registerPod(String podId, String ns, String podName, String host, int port) {
            registry.register(podId, ns, podName, "app", host, port);
        }

        FullHttpResponse send(FullHttpRequest req) {
            channel.writeInbound(req);
            FullHttpResponse resp = channel.readOutbound();
            assertNotNull(resp, "no outbound response produced");
            return resp;
        }
    }

    private static FullHttpRequest get(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    private static FullHttpRequest post(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri);
    }

    // ── /api/pods ──────────────────────────────────────────────────────────

    @Test
    void listPodsReturnsEmptyJsonWhenNothingRegistered() {
        Harness h = new Harness();
        FullHttpResponse resp = h.send(get("/api/pods"));
        assertEquals(HttpResponseStatus.OK, resp.status());
        String body = resp.content().toString(CharsetUtil.UTF_8);
        assertTrue(body.contains("\"pods\":[]"), body);
        assertTrue(body.contains("\"count\":0"), body);
    }

    @Test
    void listPodsExposesIdentityFieldsOnly() {
        Harness h = new Harness();
        h.registerPod("prod/payment-1", "prod", "payment-1", "10.42.0.5", 9202);
        FullHttpResponse resp = h.send(get("/api/pods"));
        assertEquals(HttpResponseStatus.OK, resp.status());
        String body = resp.content().toString(CharsetUtil.UTF_8);
        assertTrue(body.contains("\"podId\":\"prod/payment-1\""), body);
        assertTrue(body.contains("\"namespace\":\"prod\""), body);
        assertTrue(body.contains("\"podName\":\"payment-1\""), body);
        assertTrue(body.contains("\"deployment\":\"app\""), body);
        // Identity-only — must not leak host/port/scrapeUrl/registeredAt.
        assertFalse(body.contains("10.42.0.5"), "host must not be exposed: " + body);
        assertFalse(body.contains("\"port\""), "port must not be exposed: " + body);
        assertFalse(body.contains("scrapeUrl"), "scrapeUrl must not be exposed: " + body);
    }

    // ── /api/pods/{id}/commands ────────────────────────────────────────────

    @Test
    void commandsProxiesToPodCommandsEndpoint() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        h.stub.body = "{\"commands\":[{\"id\":\"gc\"}]}";

        FullHttpResponse resp = h.send(get("/api/pods/ns%2Fp/commands"));
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals("{\"commands\":[{\"id\":\"gc\"}]}",
                resp.content().toString(CharsetUtil.UTF_8));
        assertEquals(List.of("http://10.0.0.1:9202/api/commands"), h.stub.calls);
    }

    @Test
    void commandsReturns404WhenPodNotRegistered() {
        Harness h = new Harness();
        FullHttpResponse resp = h.send(get("/api/pods/ns%2Funknown/commands"));
        assertEquals(HttpResponseStatus.NOT_FOUND, resp.status());
        assertTrue(h.stub.calls.isEmpty(), "must not forward to unknown pod");
    }

    @Test
    void commandsReturns502WhenPodUnreachable() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        h.stub.throwError = true;
        FullHttpResponse resp = h.send(get("/api/pods/ns%2Fp/commands"));
        assertEquals(HttpResponseStatus.BAD_GATEWAY, resp.status());
        assertTrue(resp.content().toString(CharsetUtil.UTF_8).contains("pod unreachable"));
    }

    @Test
    void commandsRejectsTraversalPodId() {
        Harness h = new Harness();
        FullHttpResponse resp = h.send(get("/api/pods/..%2Fetc%2Fpasswd/commands"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        assertTrue(h.stub.calls.isEmpty(), "must not forward on invalid podId");
    }

    // ── POST /api/exec ─────────────────────────────────────────────────────

    @Test
    void execProxiesGetToPodExec() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        h.stub.body = "{\"command\":\"gc\",\"output\":\"ok\"}";

        FullHttpResponse resp = h.send(post("/api/exec?pod=ns%2Fp&cmd=gc"));
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals("{\"command\":\"gc\",\"output\":\"ok\"}",
                resp.content().toString(CharsetUtil.UTF_8));
        assertEquals(List.of("http://10.0.0.1:9202/api/exec?cmd=gc"), h.stub.calls);
    }

    @Test
    void execSurfacesWebConsoleRejectedInErrorField() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        h.stub.body = "{\"command\":\"gcrun\",\"error\":\"Command 'gcrun' is not available via the web console.\"}";
        FullHttpResponse resp = h.send(post("/api/exec?pod=ns%2Fp&cmd=gcrun"));
        assertEquals(HttpResponseStatus.OK, resp.status());
        String body = resp.content().toString(CharsetUtil.UTF_8);
        assertTrue(body.contains("\"error\""), body);
        assertTrue(body.contains("not available via the web console"), body);
    }

    @Test
    void execRejectsMissingPodParam() {
        Harness h = new Harness();
        FullHttpResponse resp = h.send(post("/api/exec?cmd=gc"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        assertTrue(h.stub.calls.isEmpty());
    }

    @Test
    void execRejectsMissingCmdParam() {
        Harness h = new Harness();
        FullHttpResponse resp = h.send(post("/api/exec?pod=ns%2Fp"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
    }

    @Test
    void execRejectsCmdWithShellMetacharacters() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        FullHttpResponse resp = h.send(post("/api/exec?pod=ns%2Fp&cmd=gc%3Bls"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        assertTrue(h.stub.calls.isEmpty(), "must not forward injected cmd");
    }

    @Test
    void execReturns404ForUnknownPod() {
        Harness h = new Harness();
        FullHttpResponse resp = h.send(post("/api/exec?pod=ns%2Funknown&cmd=gc"));
        assertEquals(HttpResponseStatus.NOT_FOUND, resp.status());
    }

    @Test
    void execReturns502WhenPodUnreachable() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        h.stub.throwError = true;
        FullHttpResponse resp = h.send(post("/api/exec?pod=ns%2Fp&cmd=gc"));
        assertEquals(HttpResponseStatus.BAD_GATEWAY, resp.status());
    }

    @Test
    void execRejectsPodIdWithoutSeparator() {
        Harness h = new Harness();
        FullHttpResponse resp = h.send(post("/api/exec?pod=nosep&cmd=gc"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        assertTrue(h.stub.calls.isEmpty());
    }

    @Test
    void execRejectsPodIdWithMultipleSeparators() {
        Harness h = new Harness();
        FullHttpResponse resp = h.send(post("/api/exec?pod=a%2Fb%2Fc&cmd=gc"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        assertTrue(h.stub.calls.isEmpty());
    }

    @Test
    void execClampsUpstreamStatusToBadGateway() {
        // Pod returns an out-of-range status code; aggregator clamps to 502
        // so intermediaries don't reject the response line.
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        h.stub.status = 999;
        h.stub.body = "{}";
        FullHttpResponse resp = h.send(post("/api/exec?pod=ns%2Fp&cmd=gc"));
        assertEquals(HttpResponseStatus.BAD_GATEWAY, resp.status());
    }

    // ── /pod/{id}/{path} generic proxy ────────────────────────────────────

    @Test
    void podProxyGetMetricsForwardsToCorrectUrl() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        h.stub.body = "{\"jvm\":\"ok\"}";

        FullHttpResponse resp = h.send(get("/pod/ns%2Fp/metrics"));
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals("{\"jvm\":\"ok\"}", resp.content().toString(CharsetUtil.UTF_8));
        assertEquals(List.of("http://10.0.0.1:9202/metrics"), h.stub.calls);
    }

    @Test
    void podProxyGetGcAnalysisForwardsCorrectly() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        h.stub.body = "{\"gcEvents\":[]}";

        FullHttpResponse resp = h.send(get("/pod/ns%2Fp/gc-analysis"));
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals(List.of("http://10.0.0.1:9202/gc-analysis"), h.stub.calls);
    }

    @Test
    void podProxyReturns403ForNonAllowlistedPath() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);

        FullHttpResponse resp = h.send(get("/pod/ns%2Fp/secret-internal-path"));
        assertEquals(HttpResponseStatus.FORBIDDEN, resp.status());
        assertTrue(resp.content().toString(CharsetUtil.UTF_8).contains("path not proxyable"));
        assertTrue(h.stub.calls.isEmpty(), "must not forward to non-allowlisted path");
    }

    @Test
    void podProxyReturns404WhenPodNotRegistered() {
        Harness h = new Harness();

        FullHttpResponse resp = h.send(get("/pod/ns%2Funknown/metrics"));
        assertEquals(HttpResponseStatus.NOT_FOUND, resp.status());
        assertTrue(resp.content().toString(CharsetUtil.UTF_8).contains("pod not registered"));
        assertTrue(h.stub.calls.isEmpty(), "must not forward for unregistered pod");
    }

    @Test
    void podProxyReturns400ForPathTraversalInPodId() {
        Harness h = new Harness();

        FullHttpResponse resp = h.send(get("/pod/..%2Fetc%2Fpasswd/metrics"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        assertTrue(h.stub.calls.isEmpty(), "must not forward on path traversal podId");
    }

    @Test
    void podProxyPostJfrStartForwardsAsPost() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        h.stub.body = "{\"started\":true}";
        h.stub.status = 202;

        FullHttpResponse resp = h.send(post("/pod/ns%2Fp/api/jfr/start"));
        assertEquals(HttpResponseStatus.valueOf(202), resp.status());
        assertEquals(List.of("http://10.0.0.1:9202/api/jfr/start"), h.stub.postCalls);
    }

    @Test
    void podProxyGetThreadsNumericIdPassesAllowlist() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);
        h.stub.body = "{\"events\":[]}";

        FullHttpResponse resp = h.send(get("/pod/ns%2Fp/threads/123/events"));
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals(List.of("http://10.0.0.1:9202/threads/123/events"), h.stub.calls);
    }

    @Test
    void podProxyGetThreadsNonNumericIdReturns403() {
        Harness h = new Harness();
        h.registerPod("ns/p", "ns", "p", "10.0.0.1", 9202);

        FullHttpResponse resp = h.send(get("/pod/ns%2Fp/threads/abc/events"));
        assertEquals(HttpResponseStatus.FORBIDDEN, resp.status());
        assertTrue(h.stub.calls.isEmpty(), "must not forward non-numeric thread id");
    }
}
