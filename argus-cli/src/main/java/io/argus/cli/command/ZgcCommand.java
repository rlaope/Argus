package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.jfr.JfrCaptureFailed;
import io.argus.cli.jfr.JfrCaptureSession;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.cli.util.CommandUtils;
import io.argus.cli.zgc.ZgcBaseline;
import io.argus.cli.zgc.ZgcDiagnosis;
import io.argus.cli.zgc.ZgcJfrCollector;
import io.argus.core.command.CommandGroup;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.openmbean.CompositeData;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * One-shot ZGC health verdict from a single live JFR capture, with optional
 * {@code --save}, {@code --diff}, and {@code --watch} modes for trend tracking.
 *
 * <p>Usage:
 * <pre>
 *   argus zgc &lt;PID&gt; [--duration=N]
 *   argus zgc &lt;PID&gt; --save=PATH
 *   argus zgc &lt;PID&gt; --diff=PATH
 *   argus zgc &lt;PID&gt; --watch[=N] [--interval=N]
 * </pre>
 */
public final class ZgcCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_DURATION_SEC = 30;
    private static final int MIN_DURATION_SEC = 5;
    private static final int MAX_DURATION_SEC = 120;
    private static final int MIN_INTERVAL_SEC = 10;
    private static final int MAX_INTERVAL_SEC = 300;
    private static final String JFR_RECORDING_NAME = "argus-zgc";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ISO_INSTANT;

    @Override public String name() { return "zgc"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public CommandMode mode() { return CommandMode.READ; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.zgc.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0 || !isPid(args[0])) {
            System.err.println("Usage: argus zgc <PID> [--duration=N] [--save=PATH] [--diff=PATH] [--watch[=N]] [--interval=N]");
            throw new CommandExitException(1);
        }

        long pid = Long.parseLong(args[0]);
        int  duration = DEFAULT_DURATION_SEC;
        Path saveTo   = null;
        Path diffWith = null;
        int  watchIterations = -1; // -1 = not in watch mode; 0 = unlimited
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
                watchIterations = 0; // unlimited
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

        // Clamp duration and interval
        if (duration < MIN_DURATION_SEC) duration = MIN_DURATION_SEC;
        if (duration > MAX_DURATION_SEC) duration = MAX_DURATION_SEC;
        if (interval < MIN_INTERVAL_SEC) interval = MIN_INTERVAL_SEC;
        if (interval > MAX_INTERVAL_SEC) interval = MAX_INTERVAL_SEC;

        boolean useColor = config.color();

        // ── Pre-check: is the target using ZGC? ─────────────────────────────
        TargetInfo info = inspectTarget(pid);
        if (!info.usingZgc) {
            System.out.println(messages.get("cli.zgc.not.using.zgc",
                    String.valueOf(pid),
                    info.gcAlgo.isEmpty() ? "unknown" : info.gcAlgo,
                    String.valueOf(pid)));
            throw new CommandExitException(1);
        }

        // Watch mode — loop N (or unlimited) iterations
        if (watchIterations >= 0) {
            runWatch(pid, info, watchIterations, interval, useColor, messages);
            return;
        }

        // Single-shot capture
        ZgcDiagnosis d = captureOnce(pid, info, duration, messages);

        // Save mode (write-through; still render the snapshot)
        if (saveTo != null) {
            try {
                ZgcBaseline.save(saveTo, d, (int) pid);
                System.out.println(messages.get("cli.zgc.save.success", saveTo.toAbsolutePath()));
            } catch (IOException e) {
                System.err.println(messages.get("cli.zgc.save.failed", e.getMessage()));
            }
        }

        // Diff mode: load baseline → compute diff → render → exit (no normal verdict)
        if (diffWith != null) {
            try {
                ZgcBaseline baseline = ZgcBaseline.load(diffWith);
                List<ZgcBaseline.DiffRow> rows = ZgcBaseline.diff(baseline, d);
                printDiff(baseline, d, rows, useColor, messages);
            } catch (IOException e) {
                System.err.println(messages.get("cli.zgc.diff.failed", e.getMessage()));
                throw new CommandExitException(1);
            }
            return;
        }

        printReport(pid, d, useColor, messages);
    }

    // ── Capture ──────────────────────────────────────────────────────────────

    /**
     * Runs a full JFR capture and returns a populated {@link ZgcDiagnosis}.
     * Prints the "capturing" message to stdout. Throws {@link CommandExitException} on failure.
     */
    private static ZgcDiagnosis captureOnce(long pid, TargetInfo info, int durationSec,
                                             Messages messages) {
        ZgcDiagnosis d = new ZgcDiagnosis();
        d.usingZgc         = true;
        d.generational     = info.generational;
        d.jvmVersion       = info.jvmVersion;
        d.maxHeapBytes     = info.maxHeapBytes;
        d.softMaxHeapBytes = info.softMaxHeapBytes;

        System.out.println(messages.get("cli.zgc.capturing", String.valueOf(durationSec)));

        try (JfrCaptureSession.Capture capture = JfrCaptureSession.capture(
                pid, JFR_RECORDING_NAME, "profile", durationSec, "argus-zgc-")) {

            ZgcJfrCollector.collect(capture.file(), d);

        } catch (JfrCaptureFailed e) {
            System.err.println(e.getMessage());
            throw new CommandExitException(2);
        } catch (IOException e) {
            System.err.println("Failed to capture live ZGC data for PID " + pid + ": " + e.getMessage());
            throw new CommandExitException(2);
        }

        return d;
    }

    /** Attempts to stop an existing JFR recording named argus-zgc; silently ignores failure. */
    private static void stopExistingRecording(long pid) {
        JcmdExecutor.runJcmd(pid, "JFR.stop", "name=" + JFR_RECORDING_NAME);
    }

    // ── Watch mode ───────────────────────────────────────────────────────────

    private static void runWatch(long pid, TargetInfo info, int maxIterations,
                                 int intervalSec, boolean useColor, Messages messages) {
        System.out.println(messages.get("cli.zgc.watch.banner", String.valueOf(intervalSec)));

        int    iterationCount  = 0;
        int    totalStalls     = 0;
        int    totalBreaches   = 0;
        ZgcDiagnosis previous  = null;

        // Shutdown hook: clean up JFR and print summary
        final int[] finalCount    = {0};
        final int[] finalStalls   = {0};
        final int[] finalBreaches = {0};

        Thread shutdownHook = new Thread(() -> {
            stopExistingRecording(pid);
            System.out.println();
            System.out.println(messages.get("cli.zgc.watch.summary",
                    String.valueOf(finalCount[0]),
                    String.valueOf(finalStalls[0]),
                    String.valueOf(finalBreaches[0])));
        }, "argus-zgc-watch-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            while (maxIterations == 0 || iterationCount < maxIterations) {
                ZgcDiagnosis current;
                try {
                    current = captureOnce(pid, info, intervalSec, messages);
                } catch (CommandExitException e) {
                    // JFR failure — print warning and retry next iteration
                    System.err.println("  [warn] JFR capture failed for iteration "
                            + (iterationCount + 1) + ", skipping.");
                    try { Thread.sleep(intervalSec * 1000L); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }

                iterationCount++;
                totalStalls   += current.stalls.size();
                totalBreaches += current.softMaxBreached ? 1 : 0;

                // Print per-iteration summary line
                printWatchLine(current, previous, iterationCount, intervalSec, useColor, messages);

                // Every 5th iteration print the full diagnosis table
                if (iterationCount % 5 == 0) {
                    System.out.println();
                    printReport(pid, current, useColor, messages);
                    System.out.println();
                }

                previous = current;

                // Update shutdown hook counters
                finalCount[0]    = iterationCount;
                finalStalls[0]   = totalStalls;
                finalBreaches[0] = totalBreaches;

                // captureOnce sleeps exactly intervalSec for the JFR recording; no additional sleep needed.
            }
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) {}
            stopExistingRecording(pid);
            System.out.println();
            System.out.println(messages.get("cli.zgc.watch.summary",
                    String.valueOf(iterationCount),
                    String.valueOf(totalStalls),
                    String.valueOf(totalBreaches)));
        }
    }

    private static void printWatchLine(ZgcDiagnosis current, ZgcDiagnosis previous,
                                       int iteration, int intervalSec,
                                       boolean useColor, Messages messages) {
        String reset  = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim    = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String yellow = AnsiStyle.style(useColor, AnsiStyle.YELLOW);
        String red    = AnsiStyle.style(useColor, AnsiStyle.RED);
        String green  = AnsiStyle.style(useColor, AnsiStyle.GREEN);

        String timestamp = TIME_FMT.format(Instant.now());

        // Heap committed
        String heapStr = RichRenderer.formatBytes(current.heapCommittedBytes);
        String heapDelta = "";
        String heapColor = reset;
        if (previous != null) {
            long delta = current.heapCommittedBytes - previous.heapCommittedBytes;
            if (delta != 0) {
                heapDelta = " (" + ZgcBaseline.formatBytesDelta(delta) + ")";
                heapColor = delta > 0 ? yellow : green;
                if (current.softMaxHeapBytes > 0
                        && current.heapCommittedBytes > current.softMaxHeapBytes) {
                    heapColor = yellow;
                    heapDelta += " ⚠";
                }
            }
        }

        // Cycles: "Nm/NM" (minor/major)
        String cyclesStr = current.minorCycles + "m/" + current.majorCycles + "M";

        // Stalls
        String stallStr;
        String stallColor = reset;
        int stallCount = current.stalls.size();
        if (stallCount == 0) {
            stallStr = "0";
        } else {
            stallColor = red;
            ZgcDiagnosis.Stall worst = current.stalls.stream()
                    .max(java.util.Comparator.comparingDouble(ZgcDiagnosis.Stall::durationMs))
                    .orElse(null);
            String workerLabel = (worst != null && worst.thread() != null && !worst.thread().isEmpty())
                    ? worst.thread() : "?";
            double maxMs = worst != null ? worst.durationMs() : 0.0;
            stallStr = stallCount + " ✘ (max " + String.format("%.0fms", maxMs)
                    + " in \"" + workerLabel + "\")";
        }

        // Mark End pause
        String markEndStr = String.format("%.2fms", current.pauseMarkEndMs);
        String markEndColor = reset;
        if (previous != null && previous.pauseMarkEndMs > 0) {
            double delta = current.pauseMarkEndMs - previous.pauseMarkEndMs;
            if (delta / previous.pauseMarkEndMs > 0.20) {
                markEndColor = yellow;
                markEndStr += " ⚠";
            }
        }

        // Assemble line
        StringBuilder sb = new StringBuilder();
        sb.append(dim).append('[').append(timestamp).append(']').append(reset);
        sb.append(" ZGC | committed ");
        sb.append(heapColor).append(heapStr).append(heapDelta).append(reset);
        sb.append(" | cycles ").append(dim).append(cyclesStr).append(reset);
        sb.append(" | stalls ").append(stallColor).append(stallStr).append(reset);
        sb.append(" | mark-end ").append(markEndColor).append(markEndStr).append(reset);

        System.out.println(sb);
    }

    // ── Diff rendering ───────────────────────────────────────────────────────

    private static void printDiff(ZgcBaseline baseline, ZgcDiagnosis current,
                                   List<ZgcBaseline.DiffRow> rows,
                                   boolean useColor, Messages messages) {
        String reset  = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim    = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String bold   = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String yellow = AnsiStyle.style(useColor, AnsiStyle.YELLOW, AnsiStyle.BOLD);
        String red    = AnsiStyle.style(useColor, AnsiStyle.RED, AnsiStyle.BOLD);

        // Header
        Instant now = Instant.now();
        long elapsedMinutes = Duration.between(baseline.capturedAt, now).toMinutes();
        String elapsedLabel = elapsedMinutes >= 60
                ? String.format("%.1f hr", elapsedMinutes / 60.0)
                : elapsedMinutes + " min";
        System.out.println(bold + messages.get("cli.zgc.diff.header",
                ISO_FMT.format(baseline.capturedAt),
                ISO_FMT.format(now),
                "+" + elapsedLabel) + reset);
        System.out.println(dim + "═".repeat(63) + reset);

        for (ZgcBaseline.DiffRow row : rows) {
            String label = rowLabel(row.label(), messages);
            String severityMarker;
            String rowColor;
            switch (row.severity()) {
                case REGRESSION: severityMarker = " ✘"; rowColor = red;    break;
                case WARN:       severityMarker = " ⚠"; rowColor = yellow; break;
                default:         severityMarker = "";   rowColor = reset;   break;
            }

            String deltaDisplay = row.delta();
            if ("NEW".equals(deltaDisplay)) {
                deltaDisplay = "(" + messages.get("cli.zgc.diff.regression.new") + ")";
            }

            String line = String.format("  %-22s %12s → %-12s  %s%s",
                    label,
                    row.baselineValue(),
                    row.currentValue(),
                    deltaDisplay,
                    severityMarker);
            System.out.println(rowColor + line + reset);
        }
        System.out.println();
    }

    private static String rowLabel(String key, Messages messages) {
        switch (key) {
            case "heapCommitted":   return messages.get("cli.zgc.diff.row.committed");
            case "minorCycles":     return messages.get("cli.zgc.diff.row.cycles.minor");
            case "majorCycles":     return messages.get("cli.zgc.diff.row.cycles.major");
            case "stallCount":      return messages.get("cli.zgc.diff.row.stalls");
            case "pauseMarkEnd":    return messages.get("cli.zgc.diff.row.markend");
            case "softMaxBreached": return messages.get("cli.zgc.diff.row.softmax");
            default:                return key;
        }
    }

    // ── Target inspection (JMX) ─────────────────────────────────────────────

    private static final class TargetInfo {
        final boolean usingZgc;
        final boolean generational;
        final String  gcAlgo;
        final String  jvmVersion;
        final long    maxHeapBytes;
        final long    softMaxHeapBytes;
        TargetInfo(boolean usingZgc, boolean generational, String gcAlgo, String jvmVersion,
                   long maxHeapBytes, long softMaxHeapBytes) {
            this.usingZgc = usingZgc;
            this.generational = generational;
            this.gcAlgo = gcAlgo;
            this.jvmVersion = jvmVersion;
            this.maxHeapBytes = maxHeapBytes;
            this.softMaxHeapBytes = softMaxHeapBytes;
        }
        boolean usingZgc() { return usingZgc; }
        boolean generational() { return generational; }
        String gcAlgo() { return gcAlgo; }
        String jvmVersion() { return jvmVersion; }
        long maxHeapBytes() { return maxHeapBytes; }
        long softMaxHeapBytes() { return softMaxHeapBytes; }
    }

    private static TargetInfo inspectTarget(long pid) {
        boolean usingZgc = false;
        boolean generational = false;
        String gcAlgo = "";
        String jvmVersion = "";
        long maxHeap = 0;
        long softMax = -1;

        String connectorAddr;
        try {
            var vm = com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
            try {
                connectorAddr = vm.getAgentProperties()
                        .getProperty("com.sun.management.jmxremote.localConnectorAddress");
                if (connectorAddr == null) {
                    vm.startLocalManagementAgent();
                    connectorAddr = vm.getAgentProperties()
                            .getProperty("com.sun.management.jmxremote.localConnectorAddress");
                }
            } finally {
                vm.detach();
            }
        } catch (Exception e) {
            return new TargetInfo(false, false, "", "", 0, -1);
        }

        if (connectorAddr == null) {
            return new TargetInfo(false, false, "", "", 0, -1);
        }

        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(connectorAddr))) {
            MBeanServerConnection mbs = connector.getMBeanServerConnection();

            Set<ObjectName> gcs = mbs.queryNames(
                    new ObjectName("java.lang:type=GarbageCollector,*"), null);
            StringBuilder algoNames = new StringBuilder();
            for (ObjectName on : gcs) {
                String n = on.getKeyProperty("name");
                if (n == null) continue;
                if (algoNames.length() > 0) algoNames.append(", ");
                algoNames.append(n);
                if (n.contains("ZGC")) usingZgc = true;
                if (n.contains("ZGC Major Cycles") || n.contains("ZGC Minor Cycles")) {
                    generational = true;
                }
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
                    if (max instanceof Number) maxHeap = ((Number) max).longValue();
                }
            } catch (Exception ignored) {}

            try {
                ObjectName diag = new ObjectName("com.sun.management:type=HotSpotDiagnostic");
                Object res = mbs.invoke(diag, "getVMOption",
                        new Object[]{"SoftMaxHeapSize"},
                        new String[]{"java.lang.String"});
                if (res instanceof CompositeData) {
                    CompositeData cd = (CompositeData) res;
                    Object v = cd.get("value");
                    if (v != null) {
                        try {
                            long parsed = Long.parseLong(v.toString().trim());
                            softMax = parsed > 0 ? parsed : -1;
                        } catch (NumberFormatException nfe) {
                            // leave -1
                        }
                    }
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            // JMX path failed — fall through with whatever we collected.
        }

        return new TargetInfo(usingZgc, generational, gcAlgo, jvmVersion, maxHeap, softMax);
    }

    // ── Render ──────────────────────────────────────────────────────────────

    static void printReport(long pid, ZgcDiagnosis d, boolean c, Messages messages) {
        ZgcDiagnosis.Verdict verdict = d.compute();

        String bold  = AnsiStyle.style(c, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(c, AnsiStyle.RESET);
        String dim   = AnsiStyle.style(c, AnsiStyle.DIM);
        String red   = AnsiStyle.style(c, AnsiStyle.RED);
        String yel   = AnsiStyle.style(c, AnsiStyle.YELLOW);
        String grn   = AnsiStyle.style(c, AnsiStyle.GREEN);

        String generationalLabel = d.generational
                ? messages.get("cli.zgc.generational.label.gen")
                : messages.get("cli.zgc.generational.label.nogen");
        String jdkLabel = d.jvmVersion.isEmpty() ? "JDK ?" : "JDK " + d.jvmVersion;
        String title = messages.get("cli.zgc.title", String.valueOf(pid), jdkLabel, generationalLabel);
        System.out.println(bold + title + reset);
        System.out.println(dim + "═".repeat(Math.min(WIDTH, Math.max(40, title.length()))) + reset);

        // Heap line
        String heapLine = "  " + bold + messages.get("cli.zgc.heap.label") + reset
                + "         committed " + RichRenderer.formatBytes(d.heapCommittedBytes)
                + " / soft " + (d.softMaxHeapBytes > 0
                        ? RichRenderer.formatBytes(d.softMaxHeapBytes) : "n/a")
                + " / max "  + RichRenderer.formatBytes(d.maxHeapBytes)
                + (d.softMaxBreached ? "  " + yel + "⚠" + reset : "");
        System.out.println(heapLine);

        // Cycles
        System.out.println("  " + bold + messages.get("cli.zgc.cycles.label") + reset
                + "       " + d.minorCycles + " minor, " + d.majorCycles + " major"
                + " (avg interval " + String.format("%.1fs", d.avgCycleIntervalSec)
                + ", duration " + String.format("%.1fs", d.avgCycleDurationSec) + ")");

        // STW
        System.out.println("  " + bold + messages.get("cli.zgc.stw.label") + reset
                + "   Mark Start " + String.format("%.2fms", d.pauseMarkStartMs)
                + " · Mark End " + String.format("%.2fms", d.pauseMarkEndMs)
                + " · Relocate Start " + String.format("%.2fms", d.pauseRelocateStartMs));

        // Allocation stalls
        System.out.println("  " + bold + messages.get("cli.zgc.stalls.label") + reset);
        if (d.stalls.isEmpty()) {
            System.out.println("               " + grn + "✓" + reset + " " + messages.get("cli.zgc.stalls.none"));
        } else {
            ZgcDiagnosis.Stall worst = d.stalls.get(0);
            for (ZgcDiagnosis.Stall s : d.stalls) {
                if (s.durationMs() > worst.durationMs()) worst = s;
            }
            String workerLabel = worst.thread() == null || worst.thread().isEmpty()
                    ? "?" : worst.thread();
            System.out.println("               " + red + "✘" + reset + " "
                    + messages.get("cli.zgc.stalls.count",
                            String.valueOf(d.stalls.size()),
                            worst.durationMs(),
                            workerLabel));
        }

        // Allocation hotspots (only when stalls detected AND alloc events present)
        if (!d.stallAllocHotspots.isEmpty()) {
            System.out.println("               "
                    + messages.get("cli.zgc.stall.alloc.header",
                            String.format("%,d", d.totalAllocEvents)));
            for (int i = 0; i < d.stallAllocHotspots.size(); i++) {
                ZgcDiagnosis.AllocHotspot h = d.stallAllocHotspots.get(i);
                System.out.printf("               %2d. %-70s %5.1f%%%n",
                        i + 1, h.frame(), h.pct());
            }
        } else if (!d.stalls.isEmpty()) {
            System.out.println("               " + dim
                    + messages.get("cli.zgc.stall.alloc.empty") + reset);
        }

        // SoftMax line
        if (d.softMaxHeapBytes > 0) {
            System.out.println("  " + bold + messages.get("cli.zgc.softmax.label") + reset + "      "
                    + (d.softMaxBreached
                            ? red + "✘" + reset + " " + messages.get("cli.zgc.softmax.breached")
                            : grn + "✓" + reset + " " + messages.get("cli.zgc.softmax.ok")));
        } else {
            System.out.println("  " + bold + messages.get("cli.zgc.softmax.label") + reset + "      "
                    + dim + messages.get("cli.zgc.softmax.disabled") + reset);
        }

        // Overlap
        System.out.println("  " + bold + messages.get("cli.zgc.overlap.label") + reset + "      "
                + (d.cycleOverlap
                        ? red + "✘" + reset + " " + messages.get("cli.zgc.overlap.yes")
                        : grn + "✓" + reset + " " + messages.get("cli.zgc.overlap.no")));

        System.out.println();

        // Verdict line
        String verdictColor;
        switch (verdict) {
            case HEALTHY:   verdictColor = grn; break;
            case WARNING:   verdictColor = yel; break;
            default:        verdictColor = red; break;
        }
        String verdictReason = verdictReason(verdict, d, messages);
        System.out.println(bold + messages.get("cli.zgc.verdict.label") + " " + reset
                + verdictColor + bold + verdict.name() + reset
                + (verdictReason.isEmpty() ? "" : "  — " + verdictReason));

        if (verdict != ZgcDiagnosis.Verdict.HEALTHY) {
            System.out.println(messages.get("cli.zgc.recommend.label"));
            for (String r : recommendations(d, messages)) {
                System.out.println("  • " + r);
            }
        }
    }

    private static String verdictReason(ZgcDiagnosis.Verdict v, ZgcDiagnosis d, Messages messages) {
        switch (v) {
            case HEALTHY:
                return messages.get("cli.zgc.verdict.healthy");
            case WARNING: {
                if (d.softMaxBreached && d.pauseMarkEndMs > 1.0) {
                    return messages.get("cli.zgc.verdict.warning.both");
                }
                if (d.softMaxBreached) return messages.get("cli.zgc.verdict.warning.softmax");
                return messages.get("cli.zgc.verdict.warning.pause");
            }
            default: {
                if (!d.stalls.isEmpty() && d.cycleOverlap) {
                    return messages.get("cli.zgc.verdict.unhealthy.stalls.overlap");
                }
                if (!d.stalls.isEmpty()) return d.softMaxBreached
                        ? messages.get("cli.zgc.verdict.unhealthy.stalls.softmax")
                        : messages.get("cli.zgc.verdict.unhealthy.stalls");
                return messages.get("cli.zgc.verdict.unhealthy.overlap");
            }
        }
    }

    private static List<String> recommendations(ZgcDiagnosis d, Messages messages) {
        var out = new java.util.ArrayList<String>();
        if (!d.stalls.isEmpty()) {
            out.add(messages.get("cli.zgc.rec.raise.xmx", String.valueOf(suggestedXmxGb(d))));
        }
        if (d.softMaxBreached) {
            out.add(messages.get("cli.zgc.rec.raise.softmax"));
        }
        if (!d.stalls.isEmpty() || d.cycleOverlap) {
            out.add(messages.get("cli.zgc.rec.profile"));
        }
        if (d.pauseMarkEndMs > 1.0 && d.stalls.isEmpty() && !d.cycleOverlap) {
            out.add(messages.get("cli.zgc.rec.mark.end"));
        }
        if (out.isEmpty()) {
            out.add(messages.get("cli.zgc.rec.profile"));
        }
        return out;
    }

    private static long suggestedXmxGb(ZgcDiagnosis d) {
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
