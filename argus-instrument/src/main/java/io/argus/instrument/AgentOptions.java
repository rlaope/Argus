package io.argus.instrument;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The wire contract between the Argus CLI and the attached agent.
 *
 * <p>The CLI never references agent classes at compile time; it hands the agent
 * a single options string via {@code VirtualMachine.loadAgent(jar, options)}.
 * This class is the canonical encoder/decoder for that string so both sides
 * agree on its shape. The format is a flat {@code key=value;key=value} list:
 *
 * <pre>
 *   mode=watch;spec=com.acme.Foo#bar;port=51234;nonce=ab12...;enabled=true;
 *   maxHits=100;timeoutMs=60000;maxValueLen=256;maxArgs=16;maxDepth=20;maxEventsPerSecond=500
 * </pre>
 *
 * <p>Values never contain {@code ;} or {@code =} (class/method names and hex
 * nonces are safe), so the naive split is unambiguous; the encoder rejects any
 * value that would violate that.
 */
public final class AgentOptions {

    private final InstrumentMode mode;
    private final MethodSpec spec;
    private final int port;
    private final String nonce;
    private final boolean enabled;
    private final CaptureCaps caps;

    private AgentOptions(InstrumentMode mode, MethodSpec spec, int port,
                         String nonce, boolean enabled, CaptureCaps caps) {
        this.mode = mode;
        this.spec = spec;
        this.port = port;
        this.nonce = nonce;
        this.enabled = enabled;
        this.caps = caps;
    }

    public static AgentOptions of(InstrumentMode mode, MethodSpec spec, int port,
                                  String nonce, boolean enabled, CaptureCaps caps) {
        if (mode == null || spec == null || nonce == null || caps == null) {
            throw new IllegalArgumentException("mode, spec, nonce and caps are required");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        return new AgentOptions(mode, spec, port, nonce, enabled, caps);
    }

    public InstrumentMode mode() {
        return mode;
    }

    public MethodSpec spec() {
        return spec;
    }

    public int port() {
        return port;
    }

    public String nonce() {
        return nonce;
    }

    public boolean enabled() {
        return enabled;
    }

    public CaptureCaps caps() {
        return caps;
    }

    /** Encodes this option set into the {@code key=value;...} wire string. */
    public String encode() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("mode", mode.token());
        kv.put("spec", spec.toString());
        kv.put("port", Integer.toString(port));
        kv.put("nonce", nonce);
        kv.put("enabled", Boolean.toString(enabled));
        kv.put("maxHits", Integer.toString(caps.maxHits()));
        kv.put("timeoutMs", Long.toString(caps.timeoutMs()));
        kv.put("maxValueLen", Integer.toString(caps.maxValueLen()));
        kv.put("maxArgs", Integer.toString(caps.maxArgs()));
        kv.put("maxDepth", Integer.toString(caps.maxDepth()));
        kv.put("maxEventsPerSecond", Integer.toString(caps.maxEventsPerSecond()));

        StringBuilder sb = new StringBuilder(128);
        boolean first = true;
        for (Map.Entry<String, String> e : kv.entrySet()) {
            String value = e.getValue();
            if (value.indexOf(';') >= 0 || value.indexOf('=') >= 0) {
                throw new IllegalArgumentException(
                        "option value for '" + e.getKey() + "' contains a reserved character");
            }
            if (!first) {
                sb.append(';');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(value);
        }
        return sb.toString();
    }

    /**
     * Decodes a wire string produced by {@link #encode()}.
     *
     * @throws IllegalArgumentException on any missing or malformed field
     */
    public static AgentOptions parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("empty agent options");
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (String pair : raw.split(";")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("malformed option pair: '" + pair + "'");
            }
            kv.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }

        InstrumentMode mode = InstrumentMode.fromString(require(kv, "mode"));
        MethodSpec spec = MethodSpec.parse(require(kv, "spec"));
        int port = parseIntField(kv, "port");
        String nonce = require(kv, "nonce");
        boolean enabled = Boolean.parseBoolean(kv.getOrDefault("enabled", "false"));

        CaptureCaps.Builder caps = new CaptureCaps.Builder();
        if (kv.containsKey("maxHits")) {
            caps.maxHits(parseIntField(kv, "maxHits"));
        }
        if (kv.containsKey("timeoutMs")) {
            caps.timeoutMs(parseLongField(kv, "timeoutMs"));
        }
        if (kv.containsKey("maxValueLen")) {
            caps.maxValueLen(parseIntField(kv, "maxValueLen"));
        }
        if (kv.containsKey("maxArgs")) {
            caps.maxArgs(parseIntField(kv, "maxArgs"));
        }
        if (kv.containsKey("maxDepth")) {
            caps.maxDepth(parseIntField(kv, "maxDepth"));
        }
        if (kv.containsKey("maxEventsPerSecond")) {
            caps.maxEventsPerSecond(parseIntField(kv, "maxEventsPerSecond"));
        }

        return of(mode, spec, port, nonce, enabled, caps.build());
    }

    private static String require(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required option: " + key);
        }
        return v;
    }

    private static int parseIntField(Map<String, String> kv, String key) {
        try {
            return Integer.parseInt(require(kv, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option '" + key + "' is not an integer: " + kv.get(key));
        }
    }

    private static long parseLongField(Map<String, String> kv, String key) {
        try {
            return Long.parseLong(require(kv, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option '" + key + "' is not a long: " + kv.get(key));
        }
    }
}
