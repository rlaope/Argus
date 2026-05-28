package io.argus.aggregator.http;

// SECURITY: All endpoints in this controller are unauthenticated by design
// (alpha). The operator + frontend reach the aggregator over an in-cluster
// K8s Service only. DO NOT expose this port to the public internet or to
// untrusted callers. Production deployments must enforce a NetworkPolicy
// that restricts ingress to the operator + frontend service accounts. The
// /fleet/targets POST/DELETE endpoints accept arbitrary scrape targets —
// exposure is an SSRF vector. See docs/aggregator-api.md "Security warning"
// section.

import io.argus.aggregator.model.AlertEvent;
import io.argus.aggregator.model.MetricSample;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.Tile;
import io.argus.aggregator.model.TileColor;
import io.argus.aggregator.model.TileMetrics;
import io.argus.aggregator.store.FleetRegistry;
import io.argus.aggregator.store.PodRingBuffer;
import io.argus.core.net.HostAllowlist;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Top-level HTTP request dispatcher for the aggregator. Implements the API
 * contract documented in {@code docs/aggregator-api.md}.
 */
public final class FleetController {

    private static final System.Logger LOG = System.getLogger(FleetController.class.getName());
    private static final List<String> ALLOWED_SEVERITIES = List.of("critical", "warning", "info");
    private static final int MAX_PODID_LENGTH = 253;

    /**
     * Allowlist of paths that may be proxied through {@code GET|POST /pod/{id}/{path}}.
     * Each pattern is anchored (matches the full remaining path after the podId segment,
     * including any querystring that was stripped before matching). Tightly scoped to
     * known argus-server endpoints; everything else returns 403.
     */
    private static final List<Pattern> PROXY_ALLOWLIST = List.of(
            Pattern.compile("/metrics"),
            Pattern.compile("/config"),
            Pattern.compile("/health"),
            Pattern.compile("/active-threads"),
            Pattern.compile("/thread-dump"),
            Pattern.compile("/gc-analysis"),
            Pattern.compile("/cpu-metrics"),
            Pattern.compile("/allocation-analysis"),
            Pattern.compile("/metaspace-metrics"),
            Pattern.compile("/method-profiling"),
            Pattern.compile("/contention-analysis"),
            Pattern.compile("/pinning-analysis"),
            Pattern.compile("/carrier-threads"),
            Pattern.compile("/correlation"),
            Pattern.compile("/flame-graph"),
            Pattern.compile("/api/commands"),
            Pattern.compile("/api/doctor"),
            Pattern.compile("/api/process"),
            Pattern.compile("/api/exec"),
            Pattern.compile("/threads/\\d+/events"),
            Pattern.compile("/threads/\\d+/dump")
    );

    private final FleetRegistry registry;
    private final PrometheusMetricsExporter prometheus;
    private final PodHttpClient podClient;
    private final ProfileController profile;

    public FleetController(FleetRegistry registry, PrometheusMetricsExporter prometheus) {
        this(registry, prometheus, new PodHttpClient(), null);
    }

    public FleetController(FleetRegistry registry, PrometheusMetricsExporter prometheus,
                           PodHttpClient podClient) {
        this(registry, prometheus, podClient, null);
    }

    public FleetController(FleetRegistry registry, PrometheusMetricsExporter prometheus,
                           ProfileController profile) {
        this(registry, prometheus, new PodHttpClient(), profile);
    }

    public FleetController(FleetRegistry registry, PrometheusMetricsExporter prometheus,
                           PodHttpClient podClient, ProfileController profile) {
        this.registry = registry;
        this.prometheus = prometheus;
        this.podClient = podClient;
        this.profile = profile;
    }

    /** Returns true if the request was handled. */
    public boolean dispatch(ChannelHandlerContext ctx, FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = decoder.path();
        HttpMethod method = request.method();

        try {
            // ── Continuous-profiling routes (W1) ─────────────────────────────
            if (profile != null && profile.dispatch(ctx, request, path, method, decoder)) {
                return true;
            }
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
            // ── Console proxy routes ────────────────────────────────────────
            // These let the browser console pick a pod from the fleet and run
            // diagnostic commands against it without kubectl exec. The
            // aggregator forwards to each pod's argus-server using the
            // host:port that the operator already registered (and that
            // HostAllowlist already validated at registration time).
            if (HttpMethod.GET.equals(method) && path.equals("/api/pods")) {
                handleListPods(ctx, request);
                return true;
            }
            if (HttpMethod.GET.equals(method) && path.startsWith("/api/pods/")
                    && path.endsWith("/commands")) {
                String mid = path.substring("/api/pods/".length(),
                        path.length() - "/commands".length());
                handleProxyCommands(ctx, request, mid);
                return true;
            }
            if (HttpMethod.POST.equals(method) && path.equals("/api/exec")) {
                handleProxyExec(ctx, request, decoder.parameters());
                return true;
            }
            // ── Generic pod-path proxy ──────────────────────────────────────
            // GET|POST /pod/{encodedPodId}/{remaining-path}
            // Lets the dashboard fetch any allowlisted endpoint on a specific
            // pod without the browser needing to know the pod's IP/port.
            if ((HttpMethod.GET.equals(method) || HttpMethod.POST.equals(method))
                    && path.startsWith("/pod/")) {
                // Pass the raw URI (not the decoded path) so the percent-encoded
                // podId segment (e.g. "ns%2Fp") reaches handlePodProxy intact.
                handlePodProxy(ctx, request, request.uri(), method, decoder.rawQuery());
                return true;
            }
        } catch (Exception e) {
            sendError(ctx, request, 500, "internal error");
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
        Map<String, Integer> alertCounts = registry.alertCountsByPod();
        Map<String, Set<String>> alertSeverities = registry.alertSeveritiesByPod();
        List<Tile> tiles = new ArrayList<>();
        for (PodTarget t : targets) {
            if (namespace != null && !namespace.equals(t.namespace())) continue;
            if (deployment != null && !deployment.equals(t.deployment())) continue;
            TileMetrics metrics = registry.latestMetrics(t.podId());
            if (metrics == null) metrics = TileMetrics.empty();
            int alertCount = alertCounts.getOrDefault(t.podId(), 0);
            Set<String> sev = alertSeverities.getOrDefault(t.podId(), Collections.emptySet());
            Tile tile = TileBuilder.buildWith(t, metrics, alertCount, sev);
            if (color != null && tile.color() != color) continue;
            tiles.add(tile);
        }
        String body = JsonWriter.tileList(tiles, targets.size(), tiles.size());
        sendJson(ctx, request, HttpResponseStatus.OK, body);
    }

    private void handleFleetPod(ChannelHandlerContext ctx, FullHttpRequest request, String encodedId) {
        String podId = decodeAndValidatePodId(encodedId);
        if (podId == null) {
            sendError(ctx, request, 400, "invalid podId");
            return;
        }
        PodTarget target = registry.get(podId);
        if (target == null) {
            sendError(ctx, request, 404, "pod not registered");
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
        // podId shape: must equal "<namespace>/<podName>" and be a sane K8s id length
        if (!podId.equals(namespace + "/" + podName)) {
            sendError(ctx, request, 400, "podId must equal '<namespace>/<podName>'");
            return;
        }
        if (podId.length() > MAX_PODID_LENGTH || podId.indexOf('\0') >= 0 || podId.contains("..")) {
            sendError(ctx, request, 400, "podId fails sanity check");
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
        // SECURITY: SSRF defense — reject hosts that point at loopback,
        // link-local, cloud metadata IPs, or other forbidden ranges. The
        // aggregator scrapes whatever it accepts here, so an unrestricted
        // host field would let any caller turn the aggregator into an
        // attacker-controlled outbound HTTP fetcher.
        String reason = HostAllowlist.rejectionReason(host);
        if (reason != null) {
            sendError(ctx, request, 400, "host rejected: " + reason);
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
        String podId = decodeAndValidatePodId(encodedId);
        if (podId == null) {
            sendError(ctx, request, 400, "invalid podId");
            return;
        }
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

    // ── Console proxy handlers ──────────────────────────────────────────────

    /**
     * Lightweight pod list for the console picker. Returns identity fields only
     * (no metrics) so the picker stays cheap even on large fleets.
     */
    private void handleListPods(ChannelHandlerContext ctx, FullHttpRequest request) {
        List<PodTarget> targets = registry.listTargets();
        StringBuilder sb = new StringBuilder(256 + targets.size() * 128);
        sb.append("{\"pods\":[");
        for (int i = 0; i < targets.size(); i++) {
            PodTarget t = targets.get(i);
            if (i > 0) sb.append(',');
            sb.append('{')
              .append("\"podId\":\"").append(JsonWriter.escape(t.podId())).append("\",")
              .append("\"namespace\":\"").append(JsonWriter.escape(t.namespace())).append("\",")
              .append("\"podName\":\"").append(JsonWriter.escape(t.podName())).append("\",")
              .append("\"deployment\":\"").append(JsonWriter.escape(t.deployment())).append("\",")
              .append("\"scrapeOk\":").append(t.scrapeOk())
              .append('}');
        }
        sb.append("],\"count\":").append(targets.size()).append('}');
        sendJson(ctx, request, HttpResponseStatus.OK, sb.toString());
    }

    /** Proxies {@code GET /api/commands} on the selected pod's argus-server. */
    private void handleProxyCommands(ChannelHandlerContext ctx, FullHttpRequest request,
                                     String encodedPodId) {
        String podId = decodeAndValidatePodId(encodedPodId);
        if (podId == null) {
            sendError(ctx, request, 400, "invalid podId");
            return;
        }
        PodTarget target = registry.get(podId);
        if (target == null) {
            sendError(ctx, request, 404, "pod not registered");
            return;
        }
        try {
            PodHttpClient.Response resp = podClient.get(target.scrapeUrl(), "/api/commands");
            // Pass status + body through verbatim — pod argus-server already
            // emits JSON, and curating happens via supportsWebConsole on its
            // side. We don't re-parse so the wire shape stays decoupled.
            sendJson(ctx, request, safeStatus(resp.status()), resp.body());
        } catch (PodHttpClient.ProxyException e) {
            // Log the detail (incl. pod IP) server-side for diagnostics; keep
            // the client-facing message free of internal addressing.
            LOG.log(System.Logger.Level.WARNING,
                    () -> "console proxy: pod unreachable [" + e.getMessage() + "]");
            sendError(ctx, request, 502, "pod unreachable");
        }
    }

    /**
     * Clamp upstream status to a valid HTTP code. Netty's {@code valueOf}
     * is permissive about out-of-range ints and will happily emit invalid
     * lines like {@code HTTP/1.1 0 Unknown Status} — intermediaries reject
     * those, hiding the underlying error from the user.
     */
    private static HttpResponseStatus safeStatus(int upstream) {
        if (upstream >= 100 && upstream <= 599) {
            return HttpResponseStatus.valueOf(upstream);
        }
        return HttpResponseStatus.BAD_GATEWAY;
    }

    /**
     * Proxies {@code GET /api/exec?cmd=…} on the selected pod's argus-server.
     * Method is POST on the aggregator side so the browser does not cache the
     * response; the upstream call is GET because argus-server's exec endpoint
     * accepts both and GET is the simpler path.
     */
    private void handleProxyExec(ChannelHandlerContext ctx, FullHttpRequest request,
                                 Map<String, List<String>> params) {
        String podId = firstParam(params, "pod");
        String cmd = firstParam(params, "cmd");
        if (podId == null || cmd == null) {
            sendError(ctx, request, 400, "missing 'pod' or 'cmd' parameter");
            return;
        }
        String validated = decodeAndValidatePodId(podId);
        if (validated == null) {
            sendError(ctx, request, 400, "invalid podId");
            return;
        }
        // Defense-in-depth: cmd is appended to a URL, so reject obviously bad
        // input early. Curated command ids are lowercase ASCII; lock the
        // proxy filter to the same alphabet so what reaches the upstream
        // request line is bounded to [a-z0-9_-]. URLEncoder is a no-op on
        // those bytes, ruling out CRLF/NUL/space smuggling.
        if (cmd.length() > 64 || !cmd.matches("[a-z0-9_-]+")) {
            sendError(ctx, request, 400, "invalid cmd");
            return;
        }
        PodTarget target = registry.get(validated);
        if (target == null) {
            sendError(ctx, request, 404, "pod not registered");
            return;
        }
        try {
            PodHttpClient.Response resp = podClient.get(
                    target.scrapeUrl(),
                    "/api/exec?cmd=" + java.net.URLEncoder.encode(
                            cmd, java.nio.charset.StandardCharsets.UTF_8));
            sendJson(ctx, request, safeStatus(resp.status()), resp.body());
        } catch (PodHttpClient.ProxyException e) {
            // Log the detail (incl. pod IP) server-side for diagnostics; keep
            // the client-facing message free of internal addressing.
            LOG.log(System.Logger.Level.WARNING,
                    () -> "console proxy: pod unreachable [" + e.getMessage() + "]");
            sendError(ctx, request, 502, "pod unreachable");
        }
    }

    /**
     * Generic pod-path proxy: {@code GET|POST /pod/{encodedPodId}/{remaining-path}}.
     *
     * <ol>
     *   <li>Decodes and validates the first path segment after {@code /pod/} via
     *       {@link #decodeAndValidatePodId} (rejects NULL bytes, {@code ..}, wrong
     *       slash count, length cap).</li>
     *   <li>Looks up the {@link PodTarget} in the registry; returns 404 if absent.</li>
     *   <li>Matches the remaining path against {@link #PROXY_ALLOWLIST}; returns 403
     *       if not matched.</li>
     *   <li>For {@code /api/exec} applies the same cmd-regex check as
     *       {@link #handleProxyExec}.</li>
     *   <li>Forwards the request (GET or POST) to the pod and streams back the
     *       response with a clamped status code.</li>
     * </ol>
     */
    private void handlePodProxy(ChannelHandlerContext ctx, FullHttpRequest request,
                                String rawUri, HttpMethod method, String rawQuery) {
        // rawUri is the unmodified request.uri(), e.g. "/pod/ns%2Fp/metrics?foo=bar"
        // Strip querystring before parsing path segments so '?' doesn't leak into remaining.
        String fullPath = rawUri;
        int qmark = fullPath.indexOf('?');
        if (qmark >= 0) fullPath = fullPath.substring(0, qmark);

        // fullPath is now /pod/{encodedPodId}/{remaining}
        // Strip leading /pod/
        String afterPrefix = fullPath.substring("/pod/".length()); // "encodedPodId/remaining"

        // Split on the first '/' to separate encoded podId from remaining path
        int sep = afterPrefix.indexOf('/');
        String encodedPodId;
        String remaining;
        if (sep < 0) {
            // No slash after podId — remaining path is empty, which won't match
            // any allowlist entry; still validate podId and return 403 rather than
            // a misleading 400.
            encodedPodId = afterPrefix;
            remaining = "";
        } else {
            encodedPodId = afterPrefix.substring(0, sep);
            remaining = afterPrefix.substring(sep); // includes leading '/'
        }

        String podId = decodeAndValidatePodId(encodedPodId);
        if (podId == null) {
            sendError(ctx, request, 400, "invalid podId");
            return;
        }

        PodTarget target = registry.get(podId);
        if (target == null) {
            sendError(ctx, request, 404, "pod not registered");
            return;
        }

        // Allowlist check — match against the path portion only (no querystring)
        boolean allowed = false;
        for (Pattern p : PROXY_ALLOWLIST) {
            if (p.matcher(remaining).matches()) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            sendError(ctx, request, 403, "path not proxyable");
            return;
        }

        // Extra validation for /api/exec: enforce the same cmd regex as
        // handleProxyExec so the general proxy cannot be used to bypass it.
        if (remaining.equals("/api/exec")) {
            QueryStringDecoder qs = rawQuery != null && !rawQuery.isEmpty()
                    ? new QueryStringDecoder("/?" + rawQuery)
                    : new QueryStringDecoder("/");
            String cmd = firstParam(qs.parameters(), "cmd");
            if (cmd == null || cmd.length() > 64 || !cmd.matches("[a-z0-9_-]+")) {
                sendError(ctx, request, 400, "invalid cmd");
                return;
            }
        }

        // Build the forwarded path with original querystring
        String forwardPath = rawQuery != null && !rawQuery.isEmpty()
                ? remaining + "?" + rawQuery
                : remaining;

        String forwardUrl = target.scrapeUrl() + forwardPath;
        LOG.log(System.Logger.Level.DEBUG, () -> "pod proxy: forwarding " + method + " " + forwardUrl);

        try {
            PodHttpClient.Response resp;
            if (HttpMethod.POST.equals(method)) {
                String body = request.content().toString(CharsetUtil.UTF_8);
                String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
                if (contentType == null) contentType = "application/json";
                resp = podClient.post(target.scrapeUrl(), forwardPath, body, contentType);
            } else {
                resp = podClient.get(target.scrapeUrl(), forwardPath);
            }
            sendText(ctx, request, safeStatus(resp.status()), resp.body(), resp.contentType());
        } catch (PodHttpClient.ProxyException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "pod proxy: pod unreachable [" + e.getMessage() + "]");
            sendError(ctx, request, 502, "pod unreachable");
        }
    }

    // ── Validation helpers ──────────────────────────────────────────────────

    /**
     * Decodes a percent-encoded {@code podId} path segment and applies
     * defense-in-depth sanity checks. Returns {@code null} when the input is
     * unsafe (the caller will respond {@code 400}).
     */
    private static String decodeAndValidatePodId(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        String decoded;
        try {
            decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.length() > MAX_PODID_LENGTH) return null;
        if (decoded.indexOf('\0') >= 0) return null;
        if (decoded.contains("..")) return null;
        // Enforce the documented shape: exactly one '/' separating namespace
        // and podName, neither side empty. handleRegisterTarget already
        // checks this on the write side; the proxy/read path was relying on
        // the registry to miss-then-404 for malformed ids, but the doc
        // contract promises 400. Keep them aligned.
        int slash = decoded.indexOf('/');
        if (slash <= 0 || slash != decoded.lastIndexOf('/') || slash == decoded.length() - 1) {
            return null;
        }
        return decoded;
    }

    // ── Response helpers ────────────────────────────────────────────────────

    private static void sendJson(ChannelHandlerContext ctx, FullHttpRequest request,
                                 HttpResponseStatus status, String json) {
        HttpResponses.sendJson(ctx, request, status, json);
    }

    private static void sendPlain(ChannelHandlerContext ctx, FullHttpRequest request,
                                  HttpResponseStatus status, String content) {
        HttpResponses.sendPlain(ctx, request, status, content);
    }

    private static void sendText(ChannelHandlerContext ctx, FullHttpRequest request,
                                 HttpResponseStatus status, String content, String contentType) {
        HttpResponses.sendText(ctx, request, status, content, contentType);
    }

    private static void sendNoContent(ChannelHandlerContext ctx, FullHttpRequest request) {
        HttpResponses.sendNoContent(ctx, request);
    }

    private static void sendError(ChannelHandlerContext ctx, FullHttpRequest request,
                                  int code, String message) {
        HttpResponses.sendError(ctx, request, code, message);
    }

    private static String firstParam(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        if (values == null || values.isEmpty()) return null;
        String v = values.get(0);
        return (v == null || v.isEmpty()) ? null : v;
    }
}
