package io.argus.aggregator.http;

// SECURITY: like the rest of the aggregator HTTP surface, the profile
// endpoints are unauthenticated by design (alpha). They are reachable only
// over the in-cluster Service; agents push collapsed-stack samples to
// /profile/ingest and the frontend reads /profile/query + /profile/diff.
// DO NOT expose this port to untrusted callers. See FleetController's header.

import io.argus.aggregator.profile.FlameTree;
import io.argus.aggregator.profile.ProfileStore;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP routes for the continuous-profiling (W1) store:
 *
 * <ul>
 *   <li>{@code POST /profile/ingest} — accepts a flat JSON body
 *       {@code {"pod","service","event","timestamp","collapsed"}} where
 *       {@code collapsed} is async-profiler collapsed text ({@code "frame;frame N\n…"}),
 *       parses it to a {@code stack -> count} map and appends to the store.</li>
 *   <li>{@code GET /profile/query?pod=&event=&from=&to=} — merged flamegraph JSON
 *       built server-side from the merged collapsed map.</li>
 *   <li>{@code GET /profile/diff?pod=&event=&baseFrom=&baseTo=&headFrom=&headTo=} —
 *       differential flamegraph with a per-frame {@code delta = head - base}.</li>
 * </ul>
 *
 * <p>Hand-built JSON throughout (no Jackson), matching the aggregator's style.
 * Timestamps are epoch-millis (longs); {@code from}/{@code to} may be omitted and
 * default to a trailing 1h window ending now.
 */
public final class ProfileController {

    /** Cap on a single ingest body — 8 MB of collapsed text is generous. */
    private static final int MAX_COLLAPSED_BYTES = 8 * 1024 * 1024;
    /** Default query window when from/to are omitted: trailing 1 hour. */
    private static final long DEFAULT_WINDOW_MILLIS = 3_600_000L;

    private final ProfileStore store;

    public ProfileController(ProfileStore store) {
        this.store = store;
    }

    /** Returns true if the request matched a profile route and was handled. */
    public boolean dispatch(ChannelHandlerContext ctx, FullHttpRequest request,
                            String path, HttpMethod method, QueryStringDecoder decoder) {
        if (HttpMethod.POST.equals(method) && path.equals("/profile/ingest")) {
            handleIngest(ctx, request);
            return true;
        }
        if (HttpMethod.GET.equals(method) && path.equals("/profile/query")) {
            handleQuery(ctx, request, decoder.parameters());
            return true;
        }
        if (HttpMethod.GET.equals(method) && path.equals("/profile/diff")) {
            handleDiff(ctx, request, decoder.parameters());
            return true;
        }
        return false;
    }

    // ── Ingest ────────────────────────────────────────────────────────────────

    private void handleIngest(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = request.content().toString(CharsetUtil.UTF_8);
        if (body.length() > MAX_COLLAPSED_BYTES) {
            HttpResponses.sendError(ctx, request, 413, "body too large");
            return;
        }
        Map<String, String> parsed = JsonReader.parseFlatObject(body);
        if (parsed == null) {
            HttpResponses.sendError(ctx, request, 400, "invalid JSON body");
            return;
        }
        String pod = parsed.get("pod");
        String service = parsed.getOrDefault("service", "");
        String event = parsed.get("event");
        String collapsed = parsed.get("collapsed");
        if (pod == null || pod.isBlank()) {
            HttpResponses.sendError(ctx, request, 400, "missing 'pod'");
            return;
        }
        if (event == null || event.isBlank()) {
            HttpResponses.sendError(ctx, request, 400, "missing 'event'");
            return;
        }
        long timestamp;
        String ts = parsed.get("timestamp");
        if (ts == null || ts.isBlank()) {
            timestamp = System.currentTimeMillis();
        } else {
            try {
                timestamp = Long.parseLong(ts.trim());
            } catch (NumberFormatException e) {
                HttpResponses.sendError(ctx, request, 400, "invalid 'timestamp'");
                return;
            }
        }
        Map<String, Long> counts = parseCollapsed(collapsed);
        if (counts.isEmpty()) {
            HttpResponses.sendError(ctx, request, 400, "no collapsed samples");
            return;
        }
        store.append(pod, service, event, timestamp, counts);
        long total = 0L;
        for (long v : counts.values()) {
            total += v;
        }
        String json = "{\"accepted\":true,\"stacks\":" + counts.size()
                + ",\"samples\":" + total + "}";
        HttpResponses.sendJson(ctx, request, HttpResponseStatus.OK, json);
    }

    /**
     * Parses async-profiler collapsed text into a {@code stack -> count} map.
     * Each non-blank line is {@code "frame;frame;… <count>"} — the count is the
     * last whitespace-delimited token; the rest (with internal spaces preserved)
     * is the stack. Repeated stacks within the body are summed. Malformed lines
     * are skipped rather than failing the whole ingest.
     */
    static Map<String, Long> parseCollapsed(String collapsed) {
        Map<String, Long> out = new HashMap<>();
        if (collapsed == null || collapsed.isEmpty()) {
            return out;
        }
        for (String rawLine : collapsed.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            int sp = line.lastIndexOf(' ');
            if (sp <= 0 || sp == line.length() - 1) {
                continue;
            }
            String stack = line.substring(0, sp).strip();
            String countStr = line.substring(sp + 1).strip();
            if (stack.isEmpty()) {
                continue;
            }
            long count;
            try {
                count = Long.parseLong(countStr);
            } catch (NumberFormatException e) {
                continue;
            }
            if (count <= 0L) {
                continue;
            }
            out.merge(stack, count, Long::sum);
        }
        return out;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    private void handleQuery(ChannelHandlerContext ctx, FullHttpRequest request,
                             Map<String, List<String>> params) {
        String pod = firstParam(params, "pod");
        String event = firstParam(params, "event");
        if (pod == null || event == null) {
            HttpResponses.sendError(ctx, request, 400, "missing 'pod' or 'event'");
            return;
        }
        long now = System.currentTimeMillis();
        Long to = parseMillis(params, "to", now);
        if (to == null) {
            HttpResponses.sendError(ctx, request, 400, "invalid 'to'");
            return;
        }
        Long from = parseMillis(params, "from", to - DEFAULT_WINDOW_MILLIS);
        if (from == null) {
            HttpResponses.sendError(ctx, request, 400, "invalid 'from'");
            return;
        }
        Map<String, Long> merged = store.merged(pod, event,
                Instant.ofEpochMilli(from), Instant.ofEpochMilli(to));
        String tree = FlameTree.toJson(merged);
        String json = "{\"pod\":\"" + JsonWriter.escape(pod) + "\","
                + "\"event\":\"" + JsonWriter.escape(event) + "\","
                + "\"from\":" + from + ",\"to\":" + to + ","
                + "\"flamegraph\":" + tree + "}";
        HttpResponses.sendJson(ctx, request, HttpResponseStatus.OK, json);
    }

    // ── Diff ──────────────────────────────────────────────────────────────────

    private void handleDiff(ChannelHandlerContext ctx, FullHttpRequest request,
                            Map<String, List<String>> params) {
        String pod = firstParam(params, "pod");
        String event = firstParam(params, "event");
        if (pod == null || event == null) {
            HttpResponses.sendError(ctx, request, 400, "missing 'pod' or 'event'");
            return;
        }
        Long baseFrom = parseMillis(params, "baseFrom", null);
        Long baseTo = parseMillis(params, "baseTo", null);
        Long headFrom = parseMillis(params, "headFrom", null);
        Long headTo = parseMillis(params, "headTo", null);
        if (baseFrom == null || baseTo == null || headFrom == null || headTo == null) {
            HttpResponses.sendError(ctx, request, 400,
                    "missing/invalid baseFrom,baseTo,headFrom,headTo");
            return;
        }
        Map<String, Long> base = store.merged(pod, event,
                Instant.ofEpochMilli(baseFrom), Instant.ofEpochMilli(baseTo));
        Map<String, Long> head = store.merged(pod, event,
                Instant.ofEpochMilli(headFrom), Instant.ofEpochMilli(headTo));
        String tree = FlameTree.diffToJson(base, head);
        String json = "{\"pod\":\"" + JsonWriter.escape(pod) + "\","
                + "\"event\":\"" + JsonWriter.escape(event) + "\","
                + "\"baseFrom\":" + baseFrom + ",\"baseTo\":" + baseTo + ","
                + "\"headFrom\":" + headFrom + ",\"headTo\":" + headTo + ","
                + "\"flamegraph\":" + tree + "}";
        HttpResponses.sendJson(ctx, request, HttpResponseStatus.OK, json);
    }

    // ── Param helpers ───────────────────────────────────────────────────────────

    private static String firstParam(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String v = values.get(0);
        return (v == null || v.isEmpty()) ? null : v;
    }

    /**
     * Parses a millis param. Returns {@code fallback} when the param is absent,
     * the parsed long when present and valid, or {@code null} when present but
     * unparseable (so the caller can 400). When {@code fallback} is {@code null}
     * an absent param also yields {@code null}.
     */
    private static Long parseMillis(Map<String, List<String>> params, String name, Long fallback) {
        String v = firstParam(params, name);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
