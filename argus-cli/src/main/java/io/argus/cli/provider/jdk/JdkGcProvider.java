package io.argus.cli.provider.jdk;

import io.argus.diagnostics.jcmd.JcmdExecutor;
import io.argus.diagnostics.jcmd.JdkParseUtils;

import io.argus.cli.model.GcResult;
import io.argus.diagnostics.model.GcUtilResult;
import io.argus.cli.provider.GcProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GcProvider that uses {@code jcmd GC.heap_info} for heap usage and
 * {@code jstat -gcutil} for per-collector counts and pause times.
 *
 * <p>jcmd GC.heap_info does not expose collection counts or aggregate pause
 * time, so the canonical source for those is jstat (matches the approach
 * used by {@link io.argus.diagnostics.doctor.JvmSnapshotCollector}).
 */
public final class JdkGcProvider implements GcProvider {

    // Matches lines like: "total 262144K, used 131072K"
    private static final Pattern HEAP_TOTAL_USED = Pattern.compile(
            "total\\s+(\\d+)K.*used\\s+(\\d+)K", Pattern.CASE_INSENSITIVE);

    // Matches committed in lines like: "committed 51200K"
    private static final Pattern COMMITTED = Pattern.compile(
            "committed\\s+(\\d+)K", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String source() {
        return "jdk";
    }

    @Override
    public GcResult getGcInfo(long pid) {
        long heapUsed = 0L;
        long heapCommitted = 0L;

        // Parse GC.heap_info for heap usage
        try {
            String heapInfo = JcmdExecutor.execute(pid, "GC.heap_info");
            for (String line : heapInfo.split("\n")) {
                Matcher m = HEAP_TOTAL_USED.matcher(line);
                if (m.find() && heapUsed == 0) {
                    heapCommitted = JdkParseUtils.parseLong(m.group(1)) * 1024L;
                    heapUsed = JdkParseUtils.parseLong(m.group(2)) * 1024L;
                }
                Matcher cm = COMMITTED.matcher(line);
                if (cm.find() && heapCommitted == 0) {
                    heapCommitted = JdkParseUtils.parseLong(cm.group(1)) * 1024L;
                }
            }
        } catch (RuntimeException ignored) {}

        // GC counts and pause times from jstat -gcutil — the canonical source for
        // YGC/YGCT/FGC/FGCT/GCT. jcmd GC.heap_info does not report these.
        long totalEvents = 0L;
        double totalPauseMs = 0.0;
        double overheadPercent = 0.0;
        List<GcResult.CollectorInfo> collectors = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("jstat", "-gcutil", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            GcUtilResult gcutil = io.argus.diagnostics.jcmd.JdkGcUtilProvider.parseOutput(output);
            totalEvents = gcutil.ygc() + gcutil.fgc();
            totalPauseMs = gcutil.gct() * 1000.0;
            collectors.add(new GcResult.CollectorInfo(
                    "Young Generation", gcutil.ygc(), gcutil.ygct() * 1000.0));
            collectors.add(new GcResult.CollectorInfo(
                    "Old Generation", gcutil.fgc(), gcutil.fgct() * 1000.0));
        } catch (Exception ignored) {}

        // Compute overhead = GCT / uptime. Pull uptime from jcmd VM.uptime.
        try {
            String uptimeOut = JcmdExecutor.execute(pid, "VM.uptime");
            for (String line : uptimeOut.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                try {
                    double uptimeSec = Double.parseDouble(t.split("\\s+")[0]);
                    if (uptimeSec > 0) {
                        overheadPercent = (totalPauseMs / 1000.0) / uptimeSec * 100.0;
                    }
                    break;
                } catch (NumberFormatException ignored2) {}
            }
        } catch (RuntimeException ignored) {}

        return new GcResult(
                totalEvents,
                totalPauseMs,
                overheadPercent,
                "",          // lastCause — not available without JFR
                heapUsed,
                heapCommitted,
                List.copyOf(collectors)
        );
    }

}
