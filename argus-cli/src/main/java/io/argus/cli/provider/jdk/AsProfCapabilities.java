package io.argus.cli.provider.jdk;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static capability table for async-profiler v4.4 events, keyed by host OS/arch.
 *
 * <p>The event support matrix is derived from the async-profiler README "Profiling Events" section:
 * https://github.com/async-profiler/async-profiler/blob/v4.4/README.md#profiling-events
 *
 * <p>asprof v4.4 does not emit a machine-readable capability list at startup, so we maintain this
 * static table rather than attempting to parse {@code asprof --version} output (which only reports
 * the version string, not supported events).
 */
public final class AsProfCapabilities {

    /** Version of the bundled async-profiler binary. */
    public static final String ASPROF_VERSION = "4.4";

    /** Describes one profilable event. */
    public static final class EventInfo {
        private final String name;
        private final String description;
        private final boolean supported;
        private final String unsupportedReason; // null when supported

        EventInfo(String name, String description, boolean supported, String unsupportedReason) {
            this.name = name;
            this.description = description;
            this.supported = supported;
            this.unsupportedReason = unsupportedReason;
        }

        public String name()               { return name; }
        public String description()        { return description; }
        public boolean isSupported()       { return supported; }
        public String unsupportedReason()  { return unsupportedReason; }
    }

    /** Supported asprof output formats (same across all platforms). */
    public static final List<String> OUTPUT_FORMATS = Collections.unmodifiableList(Arrays.asList(
            "collapsed", "flamegraph (HTML)", "jfr", "tree", "text"
    ));

    /**
     * Returns the human-readable platform string for display (e.g. {@code "darwin-arm64"}).
     * Matches the platform keys used by {@link AsProfDownloader#detectPlatform()} but adds the
     * arch suffix for macOS so users see the actual architecture.
     */
    public static String displayPlatform() {
        String os   = System.getProperty("os.name",  "").toLowerCase();
        String arch = System.getProperty("os.arch",  "").toLowerCase();
        boolean isMac   = os.contains("mac") || os.contains("darwin");
        boolean isArm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean isX64   = arch.equals("amd64")   || arch.equals("x86_64");

        if (isMac) {
            return isArm64 ? "darwin-arm64" : "darwin-x64";
        }
        if (os.contains("linux")) {
            boolean musl = AsProfDownloader.detectPlatform() != null
                    && AsProfDownloader.detectPlatform().contains("musl");
            String osTag = musl ? "linux-musl" : "linux";
            String archTag = isArm64 ? "arm64" : (isX64 ? "x64" : arch);
            return osTag + "-" + archTag;
        }
        return System.getProperty("os.name", "unknown").toLowerCase();
    }

    /**
     * Returns an ordered map of event name → {@link EventInfo} for the current host.
     * The map preserves insertion order so callers can iterate in display order.
     */
    public static Map<String, EventInfo> forCurrentHost() {
        String os   = System.getProperty("os.name",  "").toLowerCase();
        String arch = System.getProperty("os.arch",  "").toLowerCase();
        boolean isMac   = os.contains("mac") || os.contains("darwin");
        boolean isLinux = os.contains("linux");
        boolean isArm64 = arch.equals("aarch64") || arch.equals("arm64");

        // PMU / hardware-counter events require Linux + perf_events (CONFIG_PERF_EVENTS).
        // On macOS (both x64 and arm64) async-profiler 4.4 does not expose them through
        // any backend. On macOS arm64 Apple Silicon does not expose PMU counters via
        // async-profiler's perf bridge at all.
        boolean pmuAvailable = isLinux;
        String pmuReason = isMac && isArm64
                ? "Not available on darwin-arm64 (Linux + perf_events only)"
                : isMac
                ? "Not available on darwin (Linux + perf_events only)"
                : null; // linux — available (deferred to asprof for actual check)

        Map<String, EventInfo> map = new LinkedHashMap<>();
        map.put("cpu",        new EventInfo("cpu",        "Wall-clock CPU sampling (default)",       true, null));
        map.put("alloc",      new EventInfo("alloc",      "Allocation profiling via TLAB events",     true, null));
        map.put("lock",       new EventInfo("lock",       "Lock contention via JVMTI",                true, null));
        map.put("wall",       new EventInfo("wall",       "Wall-clock sampling (all threads)",        true, null));
        map.put("nativemem",  new EventInfo("nativemem",  "Native memory allocation",                 true, null));
        map.put("nativelock", new EventInfo("nativelock", "Native lock contention",                   true, null));
        map.put("PMU",        new EventInfo("PMU",        "Hardware counters (cycles, cache-misses, …)", pmuAvailable, pmuReason));
        return map;
    }

    /**
     * Returns a compact comma-separated list of supported event names for the current host.
     * Used by the {@code argus doctor} summary line.
     */
    public static String supportedEventList() {
        StringBuilder sb = new StringBuilder();
        for (EventInfo e : forCurrentHost().values()) {
            if (e.isSupported()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.name());
            }
        }
        return sb.toString();
    }

    private AsProfCapabilities() {}
}
