package io.argus.aggregator.http;

import io.argus.aggregator.model.AlertEvent;
import io.argus.aggregator.model.MetricSample;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.Tile;
import io.argus.aggregator.model.TileColor;
import io.argus.aggregator.store.FleetRegistry;
import io.argus.aggregator.store.PodRingBuffer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Top-level HTTP request dispatcher for the aggregator. Implements the API
 * contract documented in {@code docs/aggregator-api.md}.
 */
public final class FleetController {

    private static final List<String> ALLOWED_SEVERITIES = List.of("critical", "warning", "info");

    private final FleetRegistry registry;
    private final PrometheusMetricsExporter prometheus;

    public FleetController(FleetRegistry registry, PrometheusMetricsExporter prometheus) {
        this.registry = registry;
        this.prometheus = prometheus;
    }

    /** Returns true if the request was handled. */
    public boolean dispatch(ChannelHandlerContext ctx, FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = decoder.path();
        HttpMethod method = request.method();

        try {
            if (HttpMethod.GET.equals(method) && path.equals("/fleet/list")) {
                handleFleetList(ctx, request, decoder.parameters());
                return true;
            }
            if (HttpMethod.GET.equals(method) && path.startsWith("/fleet/pod/")) {
                handleFleetPod(ctx, request, path.substring("/fleet/pod/".length()));
                return true;
            }
            if (HttpMethod.GET.equals(method) && path.equals("/fleet/summary")) {
                handleFleetSummary(ctx, request);
                return true;
            }
            if (HttpMethod.POST.equals(method) && path.equals("/fleet/targets")) {
                handleRegisterTarget(ctx, request);
                return true;
            }
            if (HttpMethod.DELETE.equals(method) && path.startsWith("/fleet/targets/")) {
                handleDeleteTarget(ctx, request, path.substring("/fleet/targets/".length()));
                return true;
            }
            if (HttpMethod.GET.equals(method) && path.equals("/fleet/alerts")) {
                handleFleetAlerts(ctx, request, decoder.parameters());
                return true;
            }
            if (HttpMethod.GET.equals(method) && path.equals("/metrics")) {
                handleMetrics(ctx, request);
                return true;
            }
            if (HttpMethod.GET.equals(method) && path.equals("/health")) {
                sendPlain(ctx, request, HttpResponseStatus.OK, "ok");
                return true;
            }
        } catch (Exception e) {
            sendError(ctx, request, 500, "internal error: " + e.getMessage());
            return true;
        }
        return false;
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    private void handleFleetList(ChannelHandlerContext ctx, FullHttpRequest request,
                                 Map<String, List<String>> params) {
        String namespace = firstParam(params, "namespace");
        String deployment = firstParam(params, "deployment");
        String colorStr = firstParam(params, "color");
        TileColor color = null;
        if (colorStr != null) {
            color = TileColor.fromWire(colorStr);
            if (color == null) {
                sendError(ctx, request, 400, "invalid color: " + colorStr);
                return;
            }
        }

        List<PodTarget> targets = registry.listTargets();
        List<Tile> tiles = new ArrayList<>();
        for (PodTarget t : targets) {
            Tile tile = TileBuilder.build(t, registry);
            if (namespace != null && !namespace.equals(t.namespace())) continue;
            if (deployment != null && !deployment.equals(t.deployment())) continue;
            if (color != null && tile.color() != color) continue;
            tiles.add(tile);
        }
        String body = JsonWriter.tileList(tiles, targets.size(), tiles.size());
        sendJson(ctx, request, HttpResponseStatus.OK, body);
    }

    private void handleFleetPod(ChannelHandlerContext ctx, FullHttpRequest request, String encodedId) {
        String podId = URLDecoder.decode(encodedId, StandardCharsets.UTF_8);
        PodTarget target = registry.get(podId);
        if (target == null) {
            sendError(ctx, request, 404, "pod '" + podId + "' not registered");
            return;
        }
        Tile tile = TileBuilder.build(target, registry);
        List<AlertEvent> alerts = registry.activeAlertsForPod(podId);
        PodRingBuffer buf = registry.buffer(podId);
        List<MetricSample> samples = buf != null ? buf.snapshot() : List.of();
        long retentionSeconds = buf != null ? buf.retentionSeconds() : 0L;
        String body = JsonWriter.podDetail(tile, alerts, retentionSeconds, samples);
        sendJson(ctx, request, HttpResponseStatus.OK, body);
    }

    private void handleFleetSummary(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = JsonWriter.summary(SummaryComputer.compute(registry));
        sendJson(ctx, request, HttpResponseStatus.OK, body);
    }

    private void handleRegisterTarget(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = request.content().toString(CharsetUtil.UTF_8);
        Map<String, String> parsed = JsonReader.parseFlatObject(body);
        if (parsed == null) {
            sendError(ctx, request, 400, "invalid JSON body");
            return;
        }
        String podId = parsed.get("podId");
        String namespace = parsed.get("namespace");
        String podName = parsed.get("podName");
        String deployment = parsed.getOrDefault("deployment", "");
        String host = parsed.get("host");
        String portStr = parsed.get("port");
        if (podId == null || namespace == null || podName == null || host == null || portStr == null) {
            sendError(ctx, request, 400, "missing required field");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            sendError(ctx, request, 400, "invalid port");
            return;
        }
        if (port <= 0 || port > 65535) {
            sendError(ctx, request, 400, "invalid port");
            return;
        }
        FleetRegistry.RegistrationResult result = registry.register(
                podId, namespace, podName, deployment, host, port);
        String responseBody = JsonWriter.registrationResponse(
                podId, result.target().registeredAt(), result.updated());
        HttpResponseStatus status = result.updated() ? HttpResponseStatus.OK : HttpResponseStatus.CREATED;
        sendJson(ctx, request, status, responseBody);
    }

    private void handleDeleteTarget(ChannelHandlerContext ctx, FullHttpRequest request, String encodedId) {
        String podId = URLDecoder.decode(encodedId, StandardCharsets.UTF_8);
        registry.deregister(podId);
        sendNoContent(ctx, request);
    }

    private void handleFleetAlerts(ChannelHandlerContext ctx, FullHttpRequest request,
                                   Map<String, List<String>> params) {
        String podId = firstParam(params, "podId");
        String severity = firstParam(params, "severity");
        if (severity != null && !ALLOWED_SEVERITIES.contains(severity)) {
            sendError(ctx, request, 400, "invalid severity: " + severity);
            return;
        }
        List<AlertEvent> all = registry.activeAlerts();
        List<AlertEvent> filtered = new ArrayList<>();
        for (AlertEvent e : all) {
            if (podId != null && !e.podId().equals(podId)) continue;
            if (severity != null && !severity.equalsIgnoreCase(e.severity())) continue;
            filtered.add(e);
        }
        String body = JsonWriter.alertList(filtered, all.size());
        sendJson(ctx, request, HttpResponseStatus.OK, body);
    }

    private void handleMetrics(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = prometheus.render();
        sendText(ctx, request, HttpResponseStatus.OK, body, "text/plain; version=0.0.4; charset=utf-8");
    }

    // ── Response helpers ────────────────────────────────────────────────────

    private static void sendJson(ChannelHandlerContext ctx, FullHttpRequest request,
                                 HttpResponseStatus status, String json) {
        sendText(ctx, request, status, json, "application/json");
    }

    private static void sendPlain(ChannelHandlerContext ctx, FullHttpRequest request,
                                  HttpResponseStatus status, String content) {
        sendText(ctx, request, status, content, "text/plain; charset=utf-8");
    }

    private static void sendText(ChannelHandlerContext ctx, FullHttpRequest request,
                                 HttpResponseStatus status, String content, String contentType) {
        ByteBuf buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, buf);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        ChannelFuture future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static void sendNoContent(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        ChannelFuture future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static void sendError(ChannelHandlerContext ctx, FullHttpRequest request,
                                  int code, String message) {
        HttpResponseStatus status = HttpResponseStatus.valueOf(code);
        sendJson(ctx, request, status, JsonWriter.error(code, message));
    }

    private static String firstParam(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        if (values == null || values.isEmpty()) return null;
        String v = values.get(0);
        return (v == null || v.isEmpty()) ? null : v;
    }
}
