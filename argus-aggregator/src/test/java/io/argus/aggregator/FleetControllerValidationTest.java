package io.argus.aggregator;

import io.argus.aggregator.http.AggregatorChannelHandler;
import io.argus.aggregator.http.FleetController;
import io.argus.aggregator.http.PrometheusMetricsExporter;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates {@link FleetController}'s defense layer: SSRF rejection on
 * {@code POST /fleet/targets}, podId path validation on
 * {@code GET /fleet/pod/...} and {@code DELETE /fleet/targets/...}, and
 * field-shape rules on registration.
 *
 * <p>Drives the controller through a real {@link AggregatorChannelHandler}
 * on an {@link EmbeddedChannel} so {@code ChannelHandlerContext} is a real
 * pipeline context (writing the response is the only side effect we assert).
 */
class FleetControllerValidationTest {

    private static EmbeddedChannel newChannel() {
        FleetRegistry registry = new FleetRegistry(60);
        ScrapeLoop loop = new ScrapeLoop(registry, 5, r -> {});
        PrometheusMetricsExporter prom = new PrometheusMetricsExporter(registry, loop);
        FleetController controller = new FleetController(registry, prom);
        return new EmbeddedChannel(new AggregatorChannelHandler(controller));
    }

    private static FullHttpResponse send(EmbeddedChannel ch, FullHttpRequest req) {
        ch.writeInbound(req);
        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp, "no outbound response produced");
        return resp;
    }

    private static FullHttpRequest postJson(String path, String body) {
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, path,
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        return req;
    }

    @Test
    void rejectsSsrfHostLoopback() {
        EmbeddedChannel ch = newChannel();
        FullHttpResponse resp = send(ch, postJson("/fleet/targets",
                "{\"podId\":\"n/p\",\"namespace\":\"n\",\"podName\":\"p\","
              + "\"host\":\"127.0.0.1\",\"port\":7070}"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        String body = resp.content().toString(CharsetUtil.UTF_8);
        assertTrue(body.contains("host rejected"), body);
    }

    @Test
    void rejectsSsrfHostImds() {
        EmbeddedChannel ch = newChannel();
        FullHttpResponse resp = send(ch, postJson("/fleet/targets",
                "{\"podId\":\"n/p\",\"namespace\":\"n\",\"podName\":\"p\","
              + "\"host\":\"169.254.169.254\",\"port\":80}"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
    }

    @Test
    void rejectsPodIdMismatch() {
        EmbeddedChannel ch = newChannel();
        FullHttpResponse resp = send(ch, postJson("/fleet/targets",
                "{\"podId\":\"n/SOMETHINGELSE\",\"namespace\":\"n\",\"podName\":\"p\","
              + "\"host\":\"10.0.0.1\",\"port\":7070}"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
    }

    @Test
    void acceptsClusterDnsHost() {
        EmbeddedChannel ch = newChannel();
        FullHttpResponse resp = send(ch, postJson("/fleet/targets",
                "{\"podId\":\"prod/payment-1\",\"namespace\":\"prod\",\"podName\":\"payment-1\","
              + "\"host\":\"10.42.0.5\",\"port\":7070}"));
        assertEquals(HttpResponseStatus.CREATED, resp.status());
    }

    @Test
    void rejectsInvalidPort() {
        EmbeddedChannel ch = newChannel();
        FullHttpResponse resp = send(ch, postJson("/fleet/targets",
                "{\"podId\":\"n/p\",\"namespace\":\"n\",\"podName\":\"p\","
              + "\"host\":\"10.0.0.1\",\"port\":70000}"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
    }

    @Test
    void getFleetPodRejectsTraversal() {
        EmbeddedChannel ch = newChannel();
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/fleet/pod/..%2F..%2Fetc%2Fpasswd");
        FullHttpResponse resp = send(ch, req);
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
    }

    @Test
    void deleteRejectsNullBytePodId() {
        EmbeddedChannel ch = newChannel();
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.DELETE,
                "/fleet/targets/n%2Fp%00");
        FullHttpResponse resp = send(ch, req);
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
    }
}
