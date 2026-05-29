package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.cli.rightsize.RightsizeRecommender;
import io.argus.cli.rightsize.RightsizeRecommender.Observation;
import io.argus.cli.rightsize.RightsizeRecommender.Recommendation;
import io.argus.core.command.CommandGroup;
import io.argus.diagnostics.doctor.JvmSnapshot;
import io.argus.diagnostics.doctor.JvmSnapshotCollector;

/**
 * Right-sizes a JVM for FinOps: recommends {@code -Xmx}/{@code -Xms}, container memory
 * request/limit, and a CPU request derived from the observed heap high-water mark, the
 * post-GC live-set floor, allocation/promotion pressure, and the metaspace + direct-buffer
 * footprint.
 *
 * <p>The recommendation math lives in {@link RightsizeRecommender} (no JVM-attach
 * dependency, unit-testable). This command only collects the {@link Observation} from a
 * live process and renders the result. The output always shows its inputs and the safety
 * factor — never a black box — and never recommends an {@code -Xmx} below the live-set floor.
 *
 * <p>Usage:
 * <pre>
 * argus rightsize &lt;pid&gt;                 # rich, human-readable recommendation
 * argus rightsize &lt;pid&gt; --format=json    # structured recommendation object
 * argus rightsize &lt;pid&gt; --limit=2g       # supply the current container limit for OOMKill check
 * </pre>
 */
public final class RightsizeCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final long MIB = 1024L * 1024L;

    @Override public String name() { return "rightsize"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.rightsize.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        long pid = 0;
        long currentLimitBytes = 0;

        for (String arg : args) {
            if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--limit=")) {
                currentLimitBytes = parseSize(arg.substring("--limit=".length()));
            } else if (!arg.startsWith("--")) {
                try { pid = Long.parseLong(arg); } catch (NumberFormatException ignored) {}
            }
        }

        JvmSnapshot s = JvmSnapshotCollector.collect(pid);
        Observation obs = buildObservation(s, currentLimitBytes);
        Recommendation rec = RightsizeRecommender.recommend(obs);

        if (json) {
            System.out.println(toJson(rec));
            return;
        }

        if (rec.refused()) {
            printRefusal(rec, messages, useColor);
            return;
        }
        printRich(rec, messages, useColor);
    }

    /**
     * Builds the right-sizing observation from a JVM snapshot, reusing the analyzers Argus
     * already collects. The post-GC live-set floor is approximated by the old-generation
     * pool's used bytes (after a collection this pool holds the surviving live set); when no
     * old-gen pool is visible we fall back to the heap-used figure, which is a safe (never
     * lower) over-estimate of the live set so the floor guarantee still holds.
     */
    private Observation buildObservation(JvmSnapshot s, long currentLimitBytes) {
        long heapHwm = s.heapUsed();
        long liveSet = estimateLiveSetFloor(s);
        long metaspace = metaspaceBytes(s);
        long directBuffers = directBufferBytes(s);
        long codeCache = s.codeCacheUsedKb() * 1024L;

        // Allocation rate: a defensible, observable proxy from the snapshot — the heap is
        // (re)allocated roughly once per GC cycle, so churn ≈ heapMax * cyclesPerSecond.
        // This is an estimate from live-process telemetry (no GC log required) and is used
        // only to inform the CPU request, never the heap-sizing floor.
        double cyclesPerSec = s.uptimeMs() > 0 ? (double) s.totalGcCount() / (s.uptimeMs() / 1000.0) : 0.0;
        double allocRate = s.heapMax() > 0 ? s.heapMax() * cyclesPerSec : 0.0;
        // Promotion rate proxy: the surviving live set promoted per cycle.
        double promoRate = liveSet * cyclesPerSec;

        return new Observation(
                heapHwm,
                liveSet,
                s.heapMax(),
                currentLimitBytes,
                allocRate,
                promoRate,
                metaspace,
                directBuffers,
                codeCache,
                s.threadCount(),
                s.uptimeMs(),
                s.totalGcCount());
    }

    /** Old-gen pool used after GC ≈ live-set floor; falls back to heap-used (never lower). */
    private long estimateLiveSetFloor(JvmSnapshot s) {
        long oldGen = 0;
        for (JvmSnapshot.PoolInfo p : s.memoryPools().values()) {
            String n = p.name().toLowerCase();
            if ((n.contains("old") || n.contains("tenured")) && "HEAP".equalsIgnoreCase(p.type())) {
                oldGen = Math.max(oldGen, p.used());
            }
        }
        return oldGen > 0 ? oldGen : s.heapUsed();
    }

    private long metaspaceBytes(JvmSnapshot s) {
        long meta = 0;
        for (JvmSnapshot.PoolInfo p : s.memoryPools().values()) {
            if (p.name().toLowerCase().contains("metaspace")) {
                meta += p.used();
            }
        }
        return meta;
    }

    /** Direct/native buffer footprint: prefer NMT "Other"/direct, else sum buffer pools. */
    private long directBufferBytes(JvmSnapshot s) {
        long buffers = 0;
        for (JvmSnapshot.BufferInfo b : s.bufferPools()) {
            if (b.name().toLowerCase().contains("direct")) {
                buffers += b.used();
            }
        }
        return buffers;
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private void printRefusal(Recommendation rec, Messages messages, boolean c) {
        System.out.print(RichRenderer.brandedHeader(c, "rightsize",
                messages.get("rightsize.subtitle")));
        System.out.println(RichRenderer.boxHeader(c, messages.get("rightsize.title"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.YELLOW) + "[!] "
                        + messages.get("rightsize.refused") + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine("  " + rec.refusalReason(), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(c, messages.get("rightsize.keep.observing"), WIDTH));
    }

    private void printRich(Recommendation rec, Messages messages, boolean c) {
        System.out.print(RichRenderer.brandedHeader(c, "rightsize",
                messages.get("rightsize.subtitle")));
        System.out.println(RichRenderer.boxHeader(c, messages.get("rightsize.title"), WIDTH,
                "floor:" + mib(rec.liveSetFloorBytes()) + "MiB",
                "safety:" + rec.xmxSafetyFactor() + "x"));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Recommendations
        line(c, messages.get("rightsize.label.xmx"),
                "-Xmx" + mibFlag(rec.recommendedXmxBytes()), AnsiStyle.GREEN);
        line(c, messages.get("rightsize.label.xms"),
                "-Xms" + mibFlag(rec.recommendedXmsBytes()), AnsiStyle.GREEN);
        line(c, messages.get("rightsize.label.mem.request"),
                mib(rec.recommendedContainerRequestBytes()) + " MiB", AnsiStyle.CYAN);
        line(c, messages.get("rightsize.label.mem.limit"),
                mib(rec.recommendedContainerLimitBytes()) + " MiB", AnsiStyle.CYAN);
        line(c, messages.get("rightsize.label.cpu.request"),
                String.format("%.2f cores", rec.recommendedCpuRequestCores()), AnsiStyle.CYAN);
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // OOMKill risk flag
        if (rec.oomKillRisk()) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.RED) + "[OOMKILL RISK] "
                            + AnsiStyle.style(c, AnsiStyle.RESET) + rec.oomKillReason(), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
        }

        // Inputs + safety factor — never a black box.
        System.out.println(RichRenderer.boxSeparator(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD)
                        + messages.get("rightsize.inputs.header") + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        input(c, messages.get("rightsize.input.hwm"), mib(rec.inputHeapHighWaterMarkBytes()) + " MiB");
        input(c, messages.get("rightsize.input.floor"), mib(rec.inputPostGcLiveSetBytes()) + " MiB");
        input(c, messages.get("rightsize.input.metaspace"), mib(rec.inputMetaspaceBytes()) + " MiB");
        input(c, messages.get("rightsize.input.directbuf"), mib(rec.inputDirectBufferBytes()) + " MiB");
        input(c, messages.get("rightsize.input.codecache"), mib(rec.inputCodeCacheBytes()) + " MiB");
        input(c, messages.get("rightsize.input.threads"), String.valueOf(rec.inputThreadCount()));
        input(c, messages.get("rightsize.input.allocrate"),
                String.format("%.0f MiB/s", rec.inputAllocationRateBytesPerSec() / MIB));
        input(c, messages.get("rightsize.input.promorate"),
                String.format("%.0f MiB/s", rec.inputPromotionRateBytesPerSec() / MIB));
        input(c, messages.get("rightsize.input.safety"), rec.xmxSafetyFactor() + "x above the live-set floor");
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                        + messages.get("rightsize.honesty.note") + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxFooter(c, "rightsize", WIDTH));
    }

    private void line(boolean c, String label, String value, String color) {
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + pad(label) + AnsiStyle.style(c, AnsiStyle.RESET)
                        + AnsiStyle.style(c, color) + value + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
    }

    private void input(boolean c, String label, String value) {
        System.out.println(RichRenderer.boxLine(
                "    " + AnsiStyle.style(c, AnsiStyle.DIM) + pad(label) + value
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
    }

    private static String pad(String label) {
        String l = label + ":";
        return l.length() >= 24 ? l + " " : l + " ".repeat(24 - l.length());
    }

    // ── JSON ────────────────────────────────────────────────────────────────

    private static String toJson(Recommendation r) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"refused\":").append(r.refused());
        if (r.refused()) {
            sb.append(",\"refusalReason\":\"").append(RichRenderer.escapeJson(r.refusalReason())).append('"');
        } else {
            sb.append(",\"recommendedXmxBytes\":").append(r.recommendedXmxBytes());
            sb.append(",\"recommendedXmsBytes\":").append(r.recommendedXmsBytes());
            sb.append(",\"recommendedContainerRequestBytes\":").append(r.recommendedContainerRequestBytes());
            sb.append(",\"recommendedContainerLimitBytes\":").append(r.recommendedContainerLimitBytes());
            sb.append(",\"recommendedCpuRequestCores\":").append(String.format("%.2f", r.recommendedCpuRequestCores()));
            sb.append(",\"oomKillRisk\":").append(r.oomKillRisk());
            if (r.oomKillReason() != null) {
                sb.append(",\"oomKillReason\":\"").append(RichRenderer.escapeJson(r.oomKillReason())).append('"');
            }
        }
        sb.append(",\"liveSetFloorBytes\":").append(r.liveSetFloorBytes());
        sb.append(",\"xmxSafetyFactor\":").append(r.xmxSafetyFactor());
        sb.append(",\"inputs\":{");
        sb.append("\"heapHighWaterMarkBytes\":").append(r.inputHeapHighWaterMarkBytes());
        sb.append(",\"postGcLiveSetBytes\":").append(r.inputPostGcLiveSetBytes());
        sb.append(",\"currentXmxBytes\":").append(r.inputCurrentXmxBytes());
        sb.append(",\"currentContainerLimitBytes\":").append(r.inputCurrentContainerLimitBytes());
        sb.append(",\"metaspaceBytes\":").append(r.inputMetaspaceBytes());
        sb.append(",\"directBufferBytes\":").append(r.inputDirectBufferBytes());
        sb.append(",\"codeCacheBytes\":").append(r.inputCodeCacheBytes());
        sb.append(",\"threadCount\":").append(r.inputThreadCount());
        sb.append(",\"allocationRateBytesPerSec\":").append(String.format("%.0f", r.inputAllocationRateBytesPerSec()));
        sb.append(",\"promotionRateBytesPerSec\":").append(String.format("%.0f", r.inputPromotionRateBytesPerSec()));
        sb.append('}');
        sb.append('}');
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static long mib(long bytes) { return bytes / MIB; }

    /** Renders an -Xmx/-Xms flag value, using g when cleanly divisible, else m. */
    private static String mibFlag(long bytes) {
        long m = bytes / MIB;
        if (m >= 1024 && m % 1024 == 0) return (m / 1024) + "g";
        return m + "m";
    }

    /** Parses sizes like {@code 2g}, {@code 512m}, {@code 1024k}, or raw bytes. Returns 0 on garbage. */
    static long parseSize(String v) {
        if (v == null || v.isEmpty()) return 0;
        String t = v.trim().toLowerCase();
        long mult = 1;
        char last = t.charAt(t.length() - 1);
        switch (last) {
            case 'g': mult = 1024L * 1024 * 1024; t = t.substring(0, t.length() - 1); break;
            case 'm': mult = 1024L * 1024; t = t.substring(0, t.length() - 1); break;
            case 'k': mult = 1024L; t = t.substring(0, t.length() - 1); break;
            default: break;
        }
        try {
            return (long) (Double.parseDouble(t) * mult);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
