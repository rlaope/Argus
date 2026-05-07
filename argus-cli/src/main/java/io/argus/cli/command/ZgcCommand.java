package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One-shot ZGC health verdict from a single 30-second live JFR capture.
 *
 * <p>Usage: {@code argus zgc <PID> [--duration=N]} where {@code N} is between 5 and 120 seconds.
 *
 * <p>Pre-checks the target JVM via JMX to confirm ZGC is the active GC, captures
 * a JFR profile recording, parses ZGC-specific events, and prints a verdict
 * (HEALTHY / WARNING / UNHEALTHY) with concrete tuning recommendations.
 */
public final class ZgcCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int DEFAULT_DURATION_SEC = 30;
    private static final int MIN_DURATION_SEC = 5;
    private static final int MAX_DURATION_SEC = 120;
    private static final String JFR_RECORDING_NAME = "argus-zgc";

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
            System.err.println("Usage: argus zgc <PID> [--duration=N]");
            throw new CommandExitException(1);
        }

        long pid = Long.parseLong(args[0]);
        int duration = DEFAULT_DURATION_SEC;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--duration=")) {
                try { duration = Integer.parseInt(a.substring(11)); }
                catch (NumberFormatException ignored) {}
            }
        }
        if (duration < MIN_DURATION_SEC) duration = MIN_DURATION_SEC;
        if (duration > MAX_DURATION_SEC) duration = MAX_DURATION_SEC;

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

        ZgcDiagnosis d = new ZgcDiagnosis();
        d.usingZgc        = true;
        d.generational    = info.generational;
        d.jvmVersion      = info.jvmVersion;
        d.maxHeapBytes    = info.maxHeapBytes;
        d.softMaxHeapBytes = info.softMaxHeapBytes;

        // ── Capture JFR ─────────────────────────────────────────────────────
        Path tmpFile = null;
        try {
            tmpFile = Files.createTempFile("argus-zgc-" + pid + "-", ".jfr");
            String jfrPath = tmpFile.toAbsolutePath().toString();

            System.out.println(messages.get("cli.zgc.capturing", String.valueOf(duration)));

            String startOut = runJcmd(pid, "JFR.start",
                    "name=" + JFR_RECORDING_NAME,
                    "settings=profile");
            if (startOut == null
                    || startOut.contains("Could not")
                    || startOut.toLowerCase().contains("error")) {
                System.err.println("JFR.start failed: "
                        + (startOut != null ? startOut.trim() : "no output"));
                throw new CommandExitException(2);
            }

            try {
                Thread.sleep((long) duration * 1000L + 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while waiting for JFR recording.");
                throw new CommandExitException(2);
            }

            String dumpOut = runJcmd(pid, "JFR.dump",
                    "name=" + JFR_RECORDING_NAME,
                    "filename=" + jfrPath);
            if (dumpOut == null
                    || dumpOut.contains("Could not")
                    || dumpOut.toLowerCase().contains("error")) {
                System.err.println("JFR.dump failed: "
                        + (dumpOut != null ? dumpOut.trim() : "no output"));
                throw new CommandExitException(2);
            }
            runJcmd(pid, "JFR.stop", "name=" + JFR_RECORDING_NAME);

            if (!Files.exists(tmpFile) || Files.size(tmpFile) == 0) {
                System.err.println("JFR file is empty. The JVM may not support JFR recording.");
                throw new CommandExitException(2);
            }

            try {
                ZgcJfrCollector.collect(tmpFile, d);
            } catch (IOException e) {
                System.err.println("Failed to parse JFR recording: " + e.getMessage());
                throw new CommandExitException(2);
            }

            printReport(pid, d, useColor, messages);

        } catch (IOException e) {
            System.err.println("Failed to capture live ZGC data for PID " + pid + ": " + e.getMessage());
            throw new CommandExitException(2);
        } finally {
            if (tmpFile != null) {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        }
    }

    // ── Target inspection (JMX) ─────────────────────────────────────────────

    private record TargetInfo(
            boolean usingZgc,
            boolean generational,
            String  gcAlgo,
            String  jvmVersion,
            long    maxHeapBytes,
            long    softMaxHeapBytes) {}

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
            // Cannot attach — the user will see "not using ZGC" with empty algo.
            return new TargetInfo(false, false, "", "", 0, -1);
        }

        if (connectorAddr == null) {
            return new TargetInfo(false, false, "", "", 0, -1);
        }

        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(connectorAddr))) {
            MBeanServerConnection mbs = connector.getMBeanServerConnection();

            // Active GC collectors → name pattern "java.lang:type=GarbageCollector,name=*".
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

            // Runtime → spec/version.
            try {
                ObjectName runtime = new ObjectName("java.lang:type=Runtime");
                Object spec = mbs.getAttribute(runtime, "SpecVersion");
                if (spec != null) jvmVersion = spec.toString();
            } catch (Exception ignored) {}

            // Heap max from MemoryMXBean.
            try {
                ObjectName mem = new ObjectName("java.lang:type=Memory");
                Object usage = mbs.getAttribute(mem, "HeapMemoryUsage");
                if (usage instanceof CompositeData cd) {
                    Object max = cd.get("max");
                    if (max instanceof Number n) maxHeap = n.longValue();
                }
            } catch (Exception ignored) {}

            // SoftMaxHeapSize via HotSpotDiagnostic.getVMOption (returns CompositeData).
            try {
                ObjectName diag = new ObjectName("com.sun.management:type=HotSpotDiagnostic");
                Object res = mbs.invoke(diag, "getVMOption",
                        new Object[]{"SoftMaxHeapSize"},
                        new String[]{"java.lang.String"});
                if (res instanceof CompositeData cd) {
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

    private static void printReport(long pid, ZgcDiagnosis d, boolean c, Messages messages) {
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
        String verdictColor = switch (verdict) {
            case HEALTHY   -> grn;
            case WARNING   -> yel;
            case UNHEALTHY -> red;
        };
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
        return switch (v) {
            case HEALTHY -> messages.get("cli.zgc.verdict.healthy");
            case WARNING -> {
                if (d.softMaxBreached && d.pauseMarkEndMs > 1.0) {
                    yield messages.get("cli.zgc.verdict.warning.both");
                }
                if (d.softMaxBreached) yield messages.get("cli.zgc.verdict.warning.softmax");
                yield messages.get("cli.zgc.verdict.warning.pause");
            }
            case UNHEALTHY -> {
                if (!d.stalls.isEmpty() && d.cycleOverlap) {
                    yield messages.get("cli.zgc.verdict.unhealthy.stalls.overlap");
                }
                if (!d.stalls.isEmpty()) yield d.softMaxBreached
                        ? messages.get("cli.zgc.verdict.unhealthy.stalls.softmax")
                        : messages.get("cli.zgc.verdict.unhealthy.stalls");
                yield messages.get("cli.zgc.verdict.unhealthy.overlap");
            }
        };
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
        // Suggest +25% rounded up.
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

    /** Runs {@code jcmd <pid> <command> [args...]} and returns stdout, or null on failure. */
    private static String runJcmd(long pid, String command, String... extraArgs) {
        try {
            String[] fullCmd = new String[3 + extraArgs.length];
            fullCmd[0] = "jcmd";
            fullCmd[1] = String.valueOf(pid);
            fullCmd[2] = command;
            System.arraycopy(extraArgs, 0, fullCmd, 3, extraArgs.length);
            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
