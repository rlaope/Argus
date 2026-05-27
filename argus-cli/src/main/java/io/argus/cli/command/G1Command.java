package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.jfr.JfrCaptureFailed;
import io.argus.cli.jfr.JfrCaptureSession;
import io.argus.cli.jmx.JmxAttachment;
import io.argus.cli.jmx.JmxAttachmentException;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.diagnostics.jcmd.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.diagnostics.g1.G1Baseline;
import io.argus.diagnostics.g1.G1Diagnosis;
import io.argus.diagnostics.g1.G1JfrCollector;
import io.argus.core.command.CommandGroup;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot G1GC health verdict from a single live JFR capture, with optional
 * {@code --save}, {@code --diff}, and {@code --watch} modes for trend tracking.
 *
 * <p>Mirrors {@link ZgcCommand}: same modes, same JFR-capture path, same diff
 * rendering shape — but G1-specific signals (region mix, mixed-cycle effectiveness,
 * humongous, IHOP timing, evacuation failure).
 *
 * <p>Usage:
 * <pre>
 *   argus g1 &lt;PID&gt; [--duration=N]
 *   argus g1 &lt;PID&gt; --save=PATH
 *   argus g1 &lt;PID&gt; --diff=PATH
 *   argus g1 &lt;PID&gt; --watch[=N] [--interval=N]
 * </pre>
 */
public final class G1Command implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_DURATION_SEC = 30;
    private static final int MIN_DURATION_SEC = 5;
    private static final int MAX_DURATION_SEC = 120;
    private static final int MIN_INTERVAL_SEC = 10;
    private static final int MAX_INTERVAL_SEC = 300;
    private static final String JFR_RECORDING_NAME = "argus-g1";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_INSTANT;

    private static final Pattern REGION_SIZE_FLAG =
            Pattern.compile("-XX:G1HeapRegionSize=(\\d+)([KMG]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAX_PAUSE_FLAG =
            Pattern.compile("-XX:MaxGCPauseMillis=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IHOP_FLAG =
            Pattern.compile("-XX:InitiatingHeapOccupancyPercent=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADAPTIVE_IHOP_FLAG =
            Pattern.compile("-XX:([+-])G1UseAdaptiveIHOP", Pattern.CASE_INSENSITIVE);

    @Override public String name() { return "g1"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public CommandMode mode() { return CommandMode.READ; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.g1.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0 || !isPid(args[0])) {
            System.err.println("Usage: argus g1 <PID> [--duration=N] [--save=PATH] [--diff=PATH] [--watch[=N]] [--interval=N]");
            throw new CommandExitException(1);
        }

        long pid = Long.parseLong(args[0]);
        int  duration = DEFAULT_DURATION_SEC;
        Path saveTo   = null;
        Path diffWith = null;
        int  watchIterations = -1;
        int  interval = DEFAULT_DURATION_SEC;

        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--duration=")) {
                try { duration = Integer.parseInt(a.substring(11)); }
                catch (NumberFormatException ignored) {}
            } else if (a.startsWith("--save=")) {
                saveTo = Path.of(a.substring("--save=".length()));
            } else if (a.equals("--save") && i + 1 < args.length) {
                saveTo = Path.of(args[++i]);
            } else if (a.startsWith("--diff=")) {
                diffWith = Path.of(a.substring("--diff=".length()));
            } else if (a.equals("--diff") && i + 1 < args.length) {
                diffWith = Path.of(args[++i]);
            } else if (a.equals("--watch")) {
                watchIterations = 0;
            } else if (a.startsWith("--watch=")) {
                try {
                    watchIterations = Integer.parseInt(a.substring("--watch=".length()));
                    if (watchIterations < 1) watchIterations = 0;
                } catch (NumberFormatException ignored) {
                    watchIterations = 0;
                }
            } else if (a.startsWith("--interval=")) {
                try { interval = Integer.parseInt(a.substring("--interval=".length())); }
                catch (NumberFormatException ignored) {}
            }
        }

        if (duration < MIN_DURATION_SEC) duration = MIN_DURATION_SEC;
        if (duration > MAX_DURATION_SEC) duration = MAX_DURATION_SEC;
        if (interval < MIN_INTERVAL_SEC) interval = MIN_INTERVAL_SEC;
        if (interval > MAX_INTERVAL_SEC) interval = MAX_INTERVAL_SEC;

        boolean useColor = config.color();

        // ── Pre-check: is the target using G1? ──────────────────────────────
        TargetInfo info = inspectTarget(pid);
        if (!info.usingG1) {
            System.out.println(messages.get("cli.g1.not.using.g1",
                    String.valueOf(pid),
                    info.gcAlgo.isEmpty() ? "unknown" : info.gcAlgo,
                    String.valueOf(pid)));
            throw new CommandExitException(1);
        }

        if (watchIterations >= 0) {
            runWatch(pid, info, watchIterations, interval, useColor, messages);
            return;
        }

        G1Diagnosis d = captureOnce(pid, info, duration, messages);

        if (saveTo != null) {
            try {
                G1Baseline.save(saveTo, d, (int) pid);
                System.out.println(messages.get("cli.g1.save.success", saveTo.toAbsolutePath()));
            } catch (IOException e) {
                System.err.println(messages.get("cli.g1.save.failed", e.getMessage()));
            }
        }

        if (diffWith != null) {
            try {
                G1Baseline baseline = G1Baseline.load(diffWith);
                List<G1Baseline.DiffRow> rows = G1Baseline.diff(baseline, d);
                printDiff(baseline, d, rows, useColor, messages);
            } catch (IOException e) {
                System.err.println(messages.get("cli.g1.diff.failed", e.getMessage()));
                throw new CommandExitException(1);
            }
            return;
        }

        printReport(pid, d, useColor, messages);
    }

    // ── Capture ──────────────────────────────────────────────────────────────

    private static G1Diagnosis captureOnce(long pid, TargetInfo info, int durationSec, Messages messages) {
        G1Diagnosis d = new G1Diagnosis();
        d.usingG1            = true;
        d.jvmVersion         = info.jvmVersion;
        d.regionSizeMb       = info.regionSizeMb;
        d.targetPauseMs      = info.targetPauseMs;
        d.ihopPercent        = info.ihopPercent;
        d.adaptiveIhop       = info.adaptiveIhop;
        d.maxHeapBytes       = info.maxHeapBytes;
        d.heapCommittedBytes = info.heapCommittedBytes;

        System.out.println(messages.get("cli.g1.capturing", String.valueOf(durationSec)));

        try (JfrCaptureSession.Capture capture = JfrCaptureSession.capture(
                pid, JFR_RECORDING_NAME, "profile", durationSec, "argus-g1-")) {
            G1JfrCollector.collect(capture.file(), d);
        } catch (JfrCaptureFailed e) {
            System.err.println(e.getMessage());
            throw new CommandExitException(2);
        } catch (IOException e) {
            System.err.println("Failed to capture live G1 data for PID " + pid + ": " + e.getMessage());
            throw new CommandExitException(2);
        }
        return d;
    }

    private static void stopExistingRecording(long pid) {
        JcmdExecutor.runJcmd(pid, "JFR.stop", "name=" + JFR_RECORDING_NAME);
    }

    // ── Watch mode ───────────────────────────────────────────────────────────

    private static void runWatch(long pid, TargetInfo info, int maxIterations,
                                 int intervalSec, boolean useColor, Messages messages) {
        System.out.println(messages.get("cli.g1.watch.banner", String.valueOf(intervalSec)));

        int totalFull = 0; int totalEvacFail = 0; int totalHumCycles = 0;
        int iterationCount = 0;
        G1Diagnosis previous = null;

        final int[] finalCount    = {0};
        final int[] finalFull     = {0};
        final int[] finalEvacFail = {0};
        final int[] finalHumCycles = {0};

        Thread shutdownHook = new Thread(() -> {
            stopExistingRecording(pid);
            System.out.println();
            System.out.println(messages.get("cli.g1.watch.summary",
                    String.valueOf(finalCount[0]),
                    String.valueOf(finalFull[0]),
                    String.valueOf(finalEvacFail[0]),
                    String.valueOf(finalHumCycles[0])));
        }, "argus-g1-watch-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            while (maxIterations == 0 || iterationCount < maxIterations) {
                G1Diagnosis current;
                try {
                    current = captureOnce(pid, info, intervalSec, messages);
                } catch (CommandExitException e) {
                    System.err.println("  [warn] JFR capture failed for iteration "
                            + (iterationCount + 1) + ", skipping.");
                    try { Thread.sleep(intervalSec * 1000L); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }

                iterationCount++;
                if (current.fullGcSeen) totalFull++;
                if (current.evacuationFailureSeen) totalEvacFail++;
                totalHumCycles += current.humongousAllocationCycles;

                printWatchLine(current, previous, intervalSec, useColor, messages);

                if (iterationCount % 5 == 0) {
                    System.out.println();
                    printReport(pid, current, useColor, messages);
                    System.out.println();
                }

                previous = current;
                finalCount[0]     = iterationCount;
                finalFull[0]      = totalFull;
                finalEvacFail[0]  = totalEvacFail;
                finalHumCycles[0] = totalHumCycles;
            }
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) {}
            stopExistingRecording(pid);
            System.out.println();
            System.out.println(messages.get("cli.g1.watch.summary",
                    String.valueOf(iterationCount),
                    String.valueOf(totalFull),
                    String.valueOf(totalEvacFail),
                    String.valueOf(totalHumCycles)));
        }
    }

    private static void printWatchLine(G1Diagnosis current, G1Diagnosis previous,
                                       int intervalSec, boolean useColor, Messages messages) {
        String reset  = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim    = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String yellow = AnsiStyle.style(useColor, AnsiStyle.YELLOW);
        String red    = AnsiStyle.style(useColor, AnsiStyle.RED);
        String green  = AnsiStyle.style(useColor, AnsiStyle.GREEN);

        String timestamp = TIME_FMT.format(Instant.now());

        String cyclesStr = current.youngCycles + "y/" + current.mixedCycles + "m/"
                + current.concurrentCycles + "c";

        String pauseColor = reset;
        String pauseStr = String.format("%.1fms", current.maxPauseMs);
        if (previous != null && previous.maxPauseMs > 0
                && current.maxPauseMs / previous.maxPauseMs > 1.5) {
            pauseColor = yellow;
        }

        String evacColor = current.evacuationFailureSeen ? red : reset;
        String evacStr   = current.evacuationFailureSeen ? "✘" : "✓";

        String fullColor = current.fullGcSeen ? red : (current.humongousAllocationCycles > 0 ? yellow : green);
        String fullStr   = current.fullGcSeen
                ? "Full GC"
                : (current.humongousAllocationCycles > 0
                        ? "humongous " + current.humongousAllocationCycles
                        : "ok");

        StringBuilder sb = new StringBuilder();
        sb.append(dim).append('[').append(timestamp).append(']').append(reset);
        sb.append(" G1 | cycles ").append(dim).append(cyclesStr).append(reset);
        sb.append(" | max-pause ").append(pauseColor).append(pauseStr).append(reset);
        sb.append(" | evac ").append(evacColor).append(evacStr).append(reset);
        sb.append(" | ").append(fullColor).append(fullStr).append(reset);

        System.out.println(sb);
    }

    // ── Diff rendering ───────────────────────────────────────────────────────

    private static void printDiff(G1Baseline baseline, G1Diagnosis current,
                                   List<G1Baseline.DiffRow> rows,
                                   boolean useColor, Messages messages) {
        String reset  = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim    = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String bold   = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String yellow = AnsiStyle.style(useColor, AnsiStyle.YELLOW, AnsiStyle.BOLD);
        String red    = AnsiStyle.style(useColor, AnsiStyle.RED, AnsiStyle.BOLD);

        Instant now = Instant.now();
        long elapsedMinutes = Duration.between(baseline.capturedAt, now).toMinutes();
        String elapsedLabel = elapsedMinutes >= 60
                ? String.format("%.1f hr", elapsedMinutes / 60.0)
                : elapsedMinutes + " min";
        System.out.println(bold + messages.get("cli.g1.diff.header",
                ISO_FMT.format(baseline.capturedAt),
                ISO_FMT.format(now),
                "+" + elapsedLabel) + reset);
        System.out.println(dim + "═".repeat(63) + reset);

        for (G1Baseline.DiffRow row : rows) {
            String label = rowLabel(row.label(), messages);
            String marker; String color;
            switch (row.severity()) {
                case REGRESSION: marker = " ✘"; color = red;    break;
                case WARN:       marker = " ⚠"; color = yellow; break;
                default:         marker = "";   color = reset;   break;
            }
            String deltaDisplay = row.delta();
            if ("NEW".equals(deltaDisplay)) {
                deltaDisplay = "(" + messages.get("cli.g1.diff.regression.new") + ")";
            }
            String line = String.format("  %-22s %14s → %-14s  %s%s",
                    label, row.baselineValue(), row.currentValue(), deltaDisplay, marker);
            System.out.println(color + line + reset);
        }
        System.out.println();
    }

    private static String rowLabel(String key, Messages messages) {
        switch (key) {
            case "heapCommitted":      return messages.get("cli.g1.diff.row.committed");
            case "fullGcSeen":         return messages.get("cli.g1.diff.row.fullgc");
            case "evacuationFailure":  return messages.get("cli.g1.diff.row.evac");
            case "mixedStarvation":    return messages.get("cli.g1.diff.row.mixed");
            case "humongousCycles":    return messages.get("cli.g1.diff.row.humongous");
            case "maxPause":           return messages.get("cli.g1.diff.row.maxpause");
            case "minMmu":             return messages.get("cli.g1.diff.row.mmu");
            case "ihopMistimed":       return messages.get("cli.g1.diff.row.ihop");
            default:                   return key;
        }
    }

    // ── Target inspection (JMX) ─────────────────────────────────────────────

    private static final class TargetInfo {
        final boolean usingG1;
        final String  gcAlgo;
        final String  jvmVersion;
        final long    heapCommittedBytes;
        final long    maxHeapBytes;
        final int     regionSizeMb;
        final int     targetPauseMs;
        final int     ihopPercent;
        final boolean adaptiveIhop;
        TargetInfo(boolean usingG1, String gcAlgo, String jvmVersion,
                   long heapCommittedBytes, long maxHeapBytes,
                   int regionSizeMb, int targetPauseMs, int ihopPercent, boolean adaptiveIhop) {
            this.usingG1 = usingG1;
            this.gcAlgo = gcAlgo;
            this.jvmVersion = jvmVersion;
            this.heapCommittedBytes = heapCommittedBytes;
            this.maxHeapBytes = maxHeapBytes;
            this.regionSizeMb = regionSizeMb;
            this.targetPauseMs = targetPauseMs;
            this.ihopPercent = ihopPercent;
            this.adaptiveIhop = adaptiveIhop;
        }
    }

    private static TargetInfo inspectTarget(long pid) {
        boolean usingG1 = false;
        String gcAlgo = "";
        String jvmVersion = "";
        long heapCommitted = 0;
        long maxHeap = 0;
        int regionSizeMb = 0;
        int targetPauseMs = 200; // JVM default
        int ihopPercent = 45;    // JVM default
        boolean adaptiveIhop = true; // JVM default since JDK 15

        String connectorAddr;
        try {
            connectorAddr = JmxAttachment.resolveConnectorAddress(pid);
        } catch (JmxAttachmentException e) {
            return new TargetInfo(false, "", "", 0, 0, 0, 200, 45, true);
        }

        try (JMXConnector connector =
                JMXConnectorFactory.connect(new JMXServiceURL(connectorAddr))) {
            MBeanServerConnection mbs = connector.getMBeanServerConnection();

            Set<ObjectName> gcs = mbs.queryNames(
                    new ObjectName("java.lang:type=GarbageCollector,*"), null);
            StringBuilder algoNames = new StringBuilder();
            for (ObjectName on : gcs) {
                String n = on.getKeyProperty("name");
                if (n == null) continue;
                if (algoNames.length() > 0) algoNames.append(", ");
                algoNames.append(n);
                if (n.contains("G1")) usingG1 = true;
            }
            gcAlgo = algoNames.toString();

            try {
                ObjectName runtime = new ObjectName("java.lang:type=Runtime");
                Object spec = mbs.getAttribute(runtime, "SpecVersion");
                if (spec != null) jvmVersion = spec.toString();
            } catch (Exception ignored) {}

            try {
                ObjectName mem = new ObjectName("java.lang:type=Memory");
                Object usage = mbs.getAttribute(mem, "HeapMemoryUsage");
                if (usage instanceof CompositeData) {
                    CompositeData cd = (CompositeData) usage;
                    Object max = cd.get("max");
                    Object committed = cd.get("committed");
                    if (max instanceof Number) maxHeap = ((Number) max).longValue();
                    if (committed instanceof Number) heapCommitted = ((Number) committed).longValue();
                }
            } catch (Exception ignored) {}

            // VM flags (Runtime.InputArguments) to read G1 tunables.
            try {
                ObjectName runtime = new ObjectName("java.lang:type=Runtime");
                Object args = mbs.getAttribute(runtime, "InputArguments");
                if (args instanceof String[]) {
                    for (String flag : (String[]) args) {
                        Matcher rm = REGION_SIZE_FLAG.matcher(flag);
                        if (rm.find()) {
                            long v = Long.parseLong(rm.group(1));
                            String unit = rm.group(2);
                            if ("K".equalsIgnoreCase(unit)) regionSizeMb = (int) Math.max(1, v / 1024);
                            else if ("G".equalsIgnoreCase(unit)) regionSizeMb = (int) (v * 1024);
                            else if ("M".equalsIgnoreCase(unit)) regionSizeMb = (int) v;
                            else regionSizeMb = (int) Math.max(1, v / (1024 * 1024)); // bytes
                        }
                        Matcher pm = MAX_PAUSE_FLAG.matcher(flag);
                        if (pm.find()) {
                            try { targetPauseMs = Integer.parseInt(pm.group(1)); }
                            catch (NumberFormatException ignored2) {}
                        }
                        Matcher im = IHOP_FLAG.matcher(flag);
                        if (im.find()) {
                            try { ihopPercent = Integer.parseInt(im.group(1)); }
                            catch (NumberFormatException ignored2) {}
                        }
                        Matcher am = ADAPTIVE_IHOP_FLAG.matcher(flag);
                        if (am.find()) {
                            adaptiveIhop = "+".equals(am.group(1));
                        }
                    }
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            // JMX path failed — fall through with whatever we collected.
        }

        return new TargetInfo(usingG1, gcAlgo, jvmVersion, heapCommitted, maxHeap,
                regionSizeMb, targetPauseMs, ihopPercent, adaptiveIhop);
    }

    // ── Render ──────────────────────────────────────────────────────────────

    static void printReport(long pid, G1Diagnosis d, boolean c, Messages messages) {
        G1Diagnosis.Verdict verdict = d.compute();

        String bold  = AnsiStyle.style(c, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(c, AnsiStyle.RESET);
        String dim   = AnsiStyle.style(c, AnsiStyle.DIM);
        String red   = AnsiStyle.style(c, AnsiStyle.RED);
        String yel   = AnsiStyle.style(c, AnsiStyle.YELLOW);
        String grn   = AnsiStyle.style(c, AnsiStyle.GREEN);

        String jdkLabel = d.jvmVersion.isEmpty() ? "JDK ?" : "JDK " + d.jvmVersion;
        String adaptiveLabel = d.adaptiveIhop
                ? messages.get("cli.g1.adaptive.on")
                : messages.get("cli.g1.adaptive.off");
        String title = messages.get("cli.g1.title", String.valueOf(pid), jdkLabel, adaptiveLabel);
        System.out.println(bold + title + reset);
        System.out.println(dim + "═".repeat(Math.min(WIDTH, Math.max(40, title.length()))) + reset);

        // Identity
        String identityLine = "  " + bold + messages.get("cli.g1.identity.label") + reset
                + "     target " + d.targetPauseMs + "ms"
                + " · region " + (d.regionSizeMb > 0 ? d.regionSizeMb + "MB" : "default")
                + " · IHOP " + d.ihopPercent + "%";
        System.out.println(identityLine);

        // Heap
        String heapLine = "  " + bold + messages.get("cli.g1.heap.label") + reset
                + "         committed " + RichRenderer.formatBytes(d.heapCommittedBytes)
                + " / max " + RichRenderer.formatBytes(d.maxHeapBytes);
        System.out.println(heapLine);

        // Regions
        if (d.totalRegions > 0) {
            System.out.println("  " + bold + messages.get("cli.g1.regions.label") + reset
                    + "      eden " + d.edenRegions + " · survivor " + d.survivorRegions
                    + " · old " + d.oldRegions
                    + (d.humongousRegions > 0
                            ? " · " + yel + "humongous " + d.humongousRegions + reset
                            : " · humongous 0")
                    + " / total " + d.totalRegions);
        }

        // Cycles
        System.out.println("  " + bold + messages.get("cli.g1.cycles.label") + reset
                + "       " + d.youngCycles + " young, " + d.mixedCycles + " mixed, "
                + d.concurrentCycles + " concurrent, " + d.fullGcCycles + " full"
                + " (avg young " + String.format("%.2fms", d.avgYoungPauseMs)
                + (d.mixedCycles > 0 ? ", avg mixed " + String.format("%.2fms", d.avgMixedPauseMs) : "")
                + ", max " + String.format("%.2fms", d.maxPauseMs) + ")");

        // Evacuation
        System.out.println("  " + bold + messages.get("cli.g1.evac.label") + reset
                + "   " + (d.evacuationFailureSeen
                        ? red + "✘" + reset + " " + messages.get("cli.g1.evac.failed",
                                String.valueOf(d.evacuationFailures))
                        : grn + "✓" + reset + " " + messages.get("cli.g1.evac.ok"))
                + dim + " · young " + RichRenderer.formatBytes(d.bytesCopiedYoung)
                + " · old " + RichRenderer.formatBytes(d.bytesCopiedOld) + reset);

        // MMU
        if (d.avgMmuPercent > 0) {
            System.out.println("  " + bold + messages.get("cli.g1.mmu.label") + reset
                    + "         min " + String.format("%.1f%%", d.minMmuPercent)
                    + ", avg " + String.format("%.1f%%", d.avgMmuPercent));
        }

        // IHOP
        if (d.predictedIhopPercent > 0 || d.actualIhopPercent > 0) {
            String ihopMark = d.ihopMistimed ? yel + "⚠" + reset + " " : "";
            System.out.println("  " + bold + messages.get("cli.g1.ihop.label") + reset
                    + "        " + ihopMark
                    + "predicted " + String.format("%.1f%%", d.predictedIhopPercent)
                    + " / actual " + String.format("%.1f%%", d.actualIhopPercent));
        }

        // Humongous
        if (d.humongousAllocationCycles > 0) {
            System.out.println("  " + bold + messages.get("cli.g1.humongous.label") + reset
                    + "   " + yel + "⚠" + reset + " "
                    + messages.get("cli.g1.humongous.cycles",
                            String.valueOf(d.humongousAllocationCycles)));
            if (!d.humongousHotspots.isEmpty()) {
                System.out.println("               "
                        + messages.get("cli.g1.humongous.alloc.header",
                                String.format("%,d", d.totalHumongousAllocEvents)));
                for (int i = 0; i < d.humongousHotspots.size(); i++) {
                    G1Diagnosis.HumongousHotspot h = d.humongousHotspots.get(i);
                    System.out.printf("               %2d. %-60s %s%n",
                            i + 1, h.frame(),
                            "(" + h.count() + " allocs, max " + RichRenderer.formatBytes(h.maxBytes()) + ")");
                }
            }
        }

        // Mixed starvation
        if (d.mixedStarvation) {
            System.out.println("  " + bold + messages.get("cli.g1.mixed.label") + reset
                    + "      " + yel + "⚠" + reset + " " + messages.get("cli.g1.mixed.starved"));
        }

        // Full GC
        if (d.fullGcSeen) {
            System.out.println("  " + bold + messages.get("cli.g1.full.label") + reset
                    + "       " + red + "✘" + reset + " "
                    + messages.get("cli.g1.full.seen", String.valueOf(d.fullGcCycles)));
        }

        System.out.println();

        // Verdict
        String verdictColor;
        switch (verdict) {
            case HEALTHY:   verdictColor = grn; break;
            case WARNING:   verdictColor = yel; break;
            default:        verdictColor = red; break;
        }
        String verdictReason = verdictReason(verdict, d, messages);
        System.out.println(bold + messages.get("cli.g1.verdict.label") + " " + reset
                + verdictColor + bold + verdict.name() + reset
                + (verdictReason.isEmpty() ? "" : "  — " + verdictReason));

        if (verdict != G1Diagnosis.Verdict.HEALTHY) {
            System.out.println(messages.get("cli.g1.recommend.label"));
            for (String r : recommendations(d, messages)) {
                System.out.println("  • " + r);
            }
        }
    }

    private static String verdictReason(G1Diagnosis.Verdict v, G1Diagnosis d, Messages messages) {
        switch (v) {
            case HEALTHY:
                return messages.get("cli.g1.verdict.healthy");
            case WARNING: {
                if (d.mixedStarvation) return messages.get("cli.g1.verdict.warning.mixed");
                if (d.ihopMistimed)    return messages.get("cli.g1.verdict.warning.ihop");
                if (d.humongousAllocationCycles > 0)
                    return messages.get("cli.g1.verdict.warning.humongous");
                return messages.get("cli.g1.verdict.warning.pause");
            }
            default: {
                if (d.fullGcSeen && d.evacuationFailureSeen)
                    return messages.get("cli.g1.verdict.unhealthy.full.evac");
                if (d.fullGcSeen)
                    return messages.get("cli.g1.verdict.unhealthy.full");
                return messages.get("cli.g1.verdict.unhealthy.evac");
            }
        }
    }

    private static List<String> recommendations(G1Diagnosis d, Messages messages) {
        var out = new java.util.ArrayList<String>();
        if (d.fullGcSeen) {
            out.add(messages.get("cli.g1.rec.full",
                    String.valueOf(suggestedXmxGb(d))));
        }
        if (d.evacuationFailureSeen) {
            out.add(messages.get("cli.g1.rec.evac"));
        }
        if (d.humongousAllocationCycles > 0) {
            int suggestMb = Math.max(8, d.regionSizeMb * 2);
            if (suggestMb < 8) suggestMb = 8;
            out.add(messages.get("cli.g1.rec.humongous", String.valueOf(suggestMb)));
        }
        if (d.mixedStarvation) {
            out.add(messages.get("cli.g1.rec.mixed", String.valueOf(Math.max(30, d.ihopPercent - 5))));
        }
        if (d.ihopMistimed) {
            out.add(messages.get("cli.g1.rec.ihop"));
        }
        if (out.isEmpty()) {
            out.add(messages.get("cli.g1.rec.profile"));
        }
        return out;
    }

    private static long suggestedXmxGb(G1Diagnosis d) {
        long curMaxBytes = d.maxHeapBytes > 0 ? d.maxHeapBytes : d.heapCommittedBytes;
        long curGb = Math.max(1, (curMaxBytes + (1L << 30) - 1) / (1L << 30));
        return Math.max(curGb + 1, (long) Math.ceil(curGb * 1.25));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isPid(String arg) {
        if (arg == null || arg.isEmpty()) return false;
        for (int i = 0; i < arg.length(); i++) {
            if (!Character.isDigit(arg.charAt(i))) return false;
        }
        return true;
    }
}
