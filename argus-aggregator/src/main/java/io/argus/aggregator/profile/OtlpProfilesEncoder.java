package io.argus.aggregator.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts collapsed-stack profiles into the OTLP <strong>Profiles</strong> signal
 * shape ({@code ProfilesData}) and emits it as OTLP/JSON, hand-coded in the same
 * dependency-free style as {@code io.argus.server.metrics.OtlpJsonBuilder} /
 * {@code OtlpSpanBuilder}.
 *
 * <p>The OTLP Profiles signal is the fourth OpenTelemetry signal. It reached
 * <em>Alpha</em> (2026-03) and is <strong>pre-GA</strong>; its wire shape can still
 * change. To keep the pre-GA spec from destabilising the default build this encoder
 * is gated behind the experimental flag {@value #FLAG_ENABLED} (default
 * {@code false}) — {@link #encode} returns {@code null} (a no-op) unless the flag is
 * explicitly set, and nothing in the default build wires it.
 *
 * <h2>ProfilesData model (the pprof-derived shape)</h2>
 * The OTLP Profiles message reuses the pprof interning model: a single
 * <em>string table</em>, a <em>function</em> table and a <em>location</em> table that
 * index into the string table, and a list of <em>samples</em> where each sample is a
 * value plus an ordered list of location indices (leaf-first, pprof convention). We
 * model exactly that — {@link ProfilesData}, {@link Function}, {@link Location},
 * {@link Sample} — without any protobuf/pprof library, matching the project's
 * zero-heavy-dependency ethos.
 *
 * <h2>Losslessness</h2>
 * Because we never depend on a pprof codec, we prove losslessness the honest,
 * dependency-free way: {@link #toProfilesData} interns collapsed stacks into the
 * model and {@link #toCollapsed} reconstructs the original {@code stack -> count}
 * map; a unit test asserts the round-trip is identity. (End-to-end ingest by a live
 * OTel Collector profiles pipeline is a separate, documented manual step that needs
 * external infrastructure.)
 *
 * <h2>Trace linkage (#245)</h2>
 * When a {@link ProfileTraceContext} is present, every sample carries
 * {@code trace_id} / {@code span_id} attribute references so a backend can correlate
 * the profile with the trace timeline produced by the GC-pause↔trace work. When the
 * context is absent the attributes are omitted.
 */
public final class OtlpProfilesEncoder {

    /** Experimental, pre-GA gate. Default {@code false}; nothing wires it by default. */
    public static final String FLAG_ENABLED = "argus.profiles.otlp.enabled";

    /** Semantic-convention attribute keys for trace linkage on a sample. */
    static final String ATTR_TRACE_ID = "trace_id";
    static final String ATTR_SPAN_ID = "span_id";

    private OtlpProfilesEncoder() {
    }

    /** {@code true} only when the experimental flag is explicitly enabled. */
    public static boolean isEnabled() {
        return Boolean.getBoolean(FLAG_ENABLED);
    }

    /**
     * Encodes one collapsed-stack profile to an OTLP/JSON {@code ProfilesData}
     * payload, or returns {@code null} (no-op) when the experimental flag
     * {@value #FLAG_ENABLED} is disabled.
     *
     * @param collapsed {@code stack -> sampleCount}; semicolon-delimited frames,
     *                  leaf last (as async-profiler / {@link ProfileStore#merged} emit)
     * @param trace     active trace context, or {@link ProfileTraceContext#none()}
     * @return OTLP/JSON profiles payload, or {@code null} when disabled
     */
    public static String encode(Map<String, Long> collapsed, ProfileTraceContext trace) {
        if (!isEnabled()) {
            return null;
        }
        return encodeUnconditionally(collapsed, trace);
    }

    /**
     * Encodes regardless of the flag, for callers that gate emission themselves
     * (and for deterministic tests that must not toggle a global system property).
     * The standard entry point ({@link #encode}) stays gated behind
     * {@value #FLAG_ENABLED}.
     */
    public static String encodeUnconditionally(Map<String, Long> collapsed, ProfileTraceContext trace) {
        return toJson(toProfilesData(collapsed), trace == null ? ProfileTraceContext.none() : trace);
    }

    // ── collapsed → ProfilesData ─────────────────────────────────────────────

    /**
     * Interns the collapsed stacks into the pprof-style {@link ProfilesData} model
     * (string / function / location tables + samples). Stacks are stored leaf-first
     * per pprof convention; counts {@code <= 0} are skipped. Iteration order is
     * preserved (the input map's order) so encoding is deterministic.
     */
    public static ProfilesData toProfilesData(Map<String, Long> collapsed) {
        ProfilesData data = new ProfilesData();
        // Index 0 of the string table is the empty string, per pprof convention.
        data.internString("");
        if (collapsed == null) {
            return data;
        }
        for (Map.Entry<String, Long> e : collapsed.entrySet()) {
            long count = e.getValue() == null ? 0L : e.getValue();
            if (count <= 0L) {
                continue;
            }
            List<String> frames = splitFrames(e.getKey()); // root-first
            // pprof stores locations leaf-first; reverse.
            int[] locationIds = new int[frames.size()];
            for (int i = 0; i < frames.size(); i++) {
                String frame = frames.get(frames.size() - 1 - i);
                locationIds[i] = data.internLocation(frame);
            }
            data.samples.add(new Sample(locationIds, count));
        }
        return data;
    }

    // ── ProfilesData → collapsed (the round-trip seam) ───────────────────────

    /**
     * Reconstructs the original {@code stack -> count} collapsed map from a
     * {@link ProfilesData}. Inverse of {@link #toProfilesData}: re-orders each
     * sample's leaf-first locations back to root-first, resolves frame names from
     * the function/string tables, and sums counts per identical stack.
     */
    public static Map<String, Long> toCollapsed(ProfilesData data) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (data == null) {
            return out;
        }
        for (Sample s : data.samples) {
            StringBuilder stack = new StringBuilder();
            // locationIds are leaf-first; walk in reverse to print root-first.
            for (int i = s.locationIds.length - 1; i >= 0; i--) {
                Location loc = data.locations.get(s.locationIds[i]);
                Function fn = data.functions.get(loc.functionId);
                String name = data.stringTable.get(fn.nameStringId);
                if (stack.length() > 0) {
                    stack.append(';');
                }
                stack.append(name);
            }
            out.merge(stack.toString(), s.value, Long::sum);
        }
        return out;
    }

    // ── ProfilesData → OTLP/JSON ─────────────────────────────────────────────

    private static String toJson(ProfilesData data, ProfileTraceContext trace) {
        long nowNano = System.currentTimeMillis() * 1_000_000L;
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"resourceProfiles\":[{");
        appendResource(sb);
        sb.append(",\"scopeProfiles\":[{");
        sb.append("\"scope\":{\"name\":\"io.argus.profiles\",\"version\":\"")
                .append(escape(version())).append("\"},");
        sb.append("\"profiles\":[{");
        // sampleType: a single CPU/sample-count axis (unit "count").
        sb.append("\"sampleType\":[{\"type\":\"samples\",\"unit\":\"count\"}],");
        appendStringTable(sb, data);
        sb.append(',');
        appendFunctions(sb, data);
        sb.append(',');
        appendLocations(sb, data);
        sb.append(',');
        appendSamples(sb, data, trace);
        sb.append(",\"timeNanos\":\"").append(nowNano).append("\"");
        sb.append("}]}]}]}");
        return sb.toString();
    }

    private static void appendResource(StringBuilder sb) {
        sb.append("\"resource\":{\"attributes\":[");
        appendStringAttribute(sb, "service.name", "argus");
        sb.append(',');
        appendStringAttribute(sb, "telemetry.sdk.name", "argus");
        sb.append(',');
        appendStringAttribute(sb, "telemetry.sdk.language", "java");
        sb.append("]}");
    }

    private static void appendStringTable(StringBuilder sb, ProfilesData data) {
        sb.append("\"stringTable\":[");
        for (int i = 0; i < data.stringTable.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(data.stringTable.get(i))).append('"');
        }
        sb.append(']');
    }

    private static void appendFunctions(StringBuilder sb, ProfilesData data) {
        sb.append("\"function\":[");
        for (int i = 0; i < data.functions.size(); i++) {
            if (i > 0) sb.append(',');
            Function fn = data.functions.get(i);
            sb.append("{\"id\":\"").append(i)
              .append("\",\"name\":\"").append(fn.nameStringId).append("\"}");
        }
        sb.append(']');
    }

    private static void appendLocations(StringBuilder sb, ProfilesData data) {
        sb.append("\"location\":[");
        for (int i = 0; i < data.locations.size(); i++) {
            if (i > 0) sb.append(',');
            Location loc = data.locations.get(i);
            sb.append("{\"id\":\"").append(i)
              .append("\",\"line\":[{\"functionIndex\":\"").append(loc.functionId)
              .append("\"}]}");
        }
        sb.append(']');
    }

    private static void appendSamples(StringBuilder sb, ProfilesData data, ProfileTraceContext trace) {
        sb.append("\"sample\":[");
        boolean linked = trace != null && trace.isPresent();
        for (int i = 0; i < data.samples.size(); i++) {
            if (i > 0) sb.append(',');
            Sample s = data.samples.get(i);
            sb.append("{\"locationIndex\":[");
            for (int j = 0; j < s.locationIds.length; j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(s.locationIds[j]).append('"');
            }
            sb.append("],\"value\":[\"").append(s.value).append("\"]");
            if (linked) {
                sb.append(",\"attributes\":[");
                boolean firstAttr = true;
                if (trace.traceId() != null) {
                    appendStringAttribute(sb, ATTR_TRACE_ID, trace.traceId());
                    firstAttr = false;
                }
                if (trace.spanId() != null) {
                    if (!firstAttr) sb.append(',');
                    appendStringAttribute(sb, ATTR_SPAN_ID, trace.spanId());
                }
                sb.append(']');
            }
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendStringAttribute(StringBuilder sb, String key, String value) {
        sb.append("{\"key\":\"").append(escape(key))
          .append("\",\"value\":{\"stringValue\":\"").append(escape(value)).append("\"}}");
    }

    private static String version() {
        String v = OtlpProfilesEncoder.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    /** Splits a collapsed stack into frames (root-first), dropping empty segments. */
    private static List<String> splitFrames(String stack) {
        List<String> out = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return out;
        }
        int start = 0;
        for (int i = 0; i < stack.length(); i++) {
            if (stack.charAt(i) == ';') {
                if (i > start) {
                    out.add(stack.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < stack.length()) {
            out.add(stack.substring(start));
        }
        return out;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ── pprof-derived model types ────────────────────────────────────────────

    /**
     * The OTLP Profiles {@code ProfilesData} body: interned string / function /
     * location tables plus the sample list. Mutable while interning; treat as
     * read-only after {@link #toProfilesData} returns.
     */
    public static final class ProfilesData {
        final List<String> stringTable = new ArrayList<>();
        final List<Function> functions = new ArrayList<>();
        final List<Location> locations = new ArrayList<>();
        final List<Sample> samples = new ArrayList<>();

        /** frame name -> location index (one location per distinct frame name). */
        private final Map<String, Integer> locationByFrame = new LinkedHashMap<>();
        /** string -> string-table index. */
        private final Map<String, Integer> stringIndex = new LinkedHashMap<>();

        int internString(String s) {
            String key = s == null ? "" : s;
            Integer idx = stringIndex.get(key);
            if (idx != null) {
                return idx;
            }
            int newIdx = stringTable.size();
            stringTable.add(key);
            stringIndex.put(key, newIdx);
            return newIdx;
        }

        int internLocation(String frame) {
            Integer existing = locationByFrame.get(frame);
            if (existing != null) {
                return existing;
            }
            int nameId = internString(frame);
            int functionId = functions.size();
            functions.add(new Function(nameId));
            int locationId = locations.size();
            locations.add(new Location(functionId));
            locationByFrame.put(frame, locationId);
            return locationId;
        }

        public List<String> stringTable() {
            return stringTable;
        }

        public List<Sample> samples() {
            return samples;
        }
    }

    /** A pprof function: its name interned into the string table. */
    public static final class Function {
        final int nameStringId;

        Function(int nameStringId) {
            this.nameStringId = nameStringId;
        }
    }

    /** A pprof location: a single line pointing at one function. */
    public static final class Location {
        final int functionId;

        Location(int functionId) {
            this.functionId = functionId;
        }
    }

    /** A pprof sample: leaf-first location indices and a single count value. */
    public static final class Sample {
        final int[] locationIds;
        final long value;

        Sample(int[] locationIds, long value) {
            this.locationIds = locationIds;
            this.value = value;
        }

        public long value() {
            return value;
        }
    }
}
