package io.argus.cli.provider.jdk;

import io.argus.cli.model.GcResult;
import io.argus.cli.provider.GcProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GcProvider that uses {@code jcmd GC.heap_info} and {@code jcmd VM.info} to collect GC data.
 */
public final class JdkGcProvider implements GcProvider {

    // Matches lines like: "total 262144K, used 131072K"
    private static final Pattern HEAP_TOTAL_USED = Pattern.compile(
            "total\\s+(\\d+)K.*used\\s+(\\d+)K", Pattern.CASE_INSENSITIVE);

    // Matches committed in lines like: "committed 51200K"
    private static final Pattern COMMITTED = Pattern.compile(
            "committed\\s+(\\d+)K", Pattern.CASE_INSENSITIVE);

    // GC collector name patterns in VM.info output
    private static final Pattern COLLECTOR_LINE = Pattern.compile(
            "(G1|ZGC|Shenandoah|Parallel|Serial|CMS)\\s+GC", Pattern.CASE_INSENSITIVE);

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
        List<GcResult.CollectorInfo> collectors = new ArrayList<>();

        // Parse GC.heap_info for heap usage
        try {
            String heapInfo = JcmdExecutor.execute(pid, "GC.heap_info");
            for (String line : heapInfo.split("\n")) {
                Matcher m = HEAP_TOTAL_USED.matcher(line);
                if (m.find() && heapUsed == 0) {
                    heapCommitted = parseLong(m.group(1)) * 1024L;
                    heapUsed = parseLong(m.group(2)) * 1024L;
                }
                Matcher cm = COMMITTED.matcher(line);
                if (cm.find() && heapCommitted == 0) {
                    heapCommitted = parseLong(cm.group(1)) * 1024L;
                }
            }
        } catch (RuntimeException ignored) {}

        // Parse VM.info to detect GC collector names
        try {
            String vmInfo = JcmdExecutor.execute(pid, "VM.info");
            for (String line : vmInfo.split("\n")) {
                Matcher m = COLLECTOR_LINE.matcher(line);
                if (m.find()) {
                    String name = m.group(0).trim();
                    // Avoid duplicates
                    boolean found = false;
                    for (GcResult.CollectorInfo c : collectors) {
                        if (c.name().equalsIgnoreCase(name)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        collectors.add(new GcResult.CollectorInfo(name, 0L, 0.0));
                    }
                }
            }
        } catch (RuntimeException ignored) {}

        return new GcResult(
                0L,          // totalEvents — not available via jcmd heap_info
                0.0,         // totalPauseMs — not available without JFR
                0.0,         // overheadPercent — not available without JFR
                "",          // lastCause — not available without JFR
                heapUsed,
                heapCommitted,
                List.copyOf(collectors)
        );
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
