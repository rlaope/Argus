package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.doctor.JvmSnapshot;
import io.argus.cli.doctor.JvmSnapshotCollector;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compare two JVM processes side by side, or compare against a saved baseline.
 *
 * <p>Usage:
 * <pre>
 * argus compare 12345 67890            # compare two live JVMs
 * argus compare 12345 --save base.json # save snapshot as baseline
 * argus compare 12345 --load base.json # compare live vs baseline
 * </pre>
 */
public final class CompareCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public String name() { return "compare"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public boolean supportsTui() { return false; }
    @Override public CommandMode mode() { return CommandMode.WRITE; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.compare.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus compare <pid1> <pid2>");
            System.err.println("       argus compare <pid> --save baseline.json");
            System.err.println("       argus compare <pid> --load baseline.json");
            return;
        }

        long pid1 = 0;
        long pid2 = 0;
        String savePath = null;
        String loadPath = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--save=")) savePath = args[i].substring(7);
            else if (args[i].equals("--save") && i + 1 < args.length) savePath = args[++i];
            else if (args[i].startsWith("--load=")) loadPath = args[i].substring(7);
            else if (args[i].equals("--load") && i + 1 < args.length) loadPath = args[++i];
            else if (args[i].equals("--format=json")) json = true;
            else if (!args[i].startsWith("--")) {
                try {
                    if (pid1 == 0) pid1 = Long.parseLong(args[i]);
                    else pid2 = Long.parseLong(args[i]);
                } catch (NumberFormatException ignored) {}
            }
        }

        // Save mode
        if (savePath != null && pid1 > 0) {
            JvmSnapshot snap = JvmSnapshotCollector.collect(pid1);
            String jsonStr = snapshotToJson(snap);
            try {
                Files.writeString(Path.of(savePath), jsonStr);
                System.out.println("Saved baseline to: " + savePath);
            } catch (IOException e) {
                System.err.println("Failed to save: " + e.getMessage());
            }
            return;
        }

        // Collect snapshots
        JvmSnapshot snapA;
        JvmSnapshot snapB;
        String labelA, labelB;

        if (loadPath != null) {
            snapA = JvmSnapshotCollector.collect(pid1);
            snapB = loadBaseline(loadPath);
            labelA = "Live (pid:" + pid1 + ")";
            labelB = "Baseline";
            if (snapB == null) return;
        } else if (pid1 > 0 && pid2 > 0) {
            snapA = JvmSnapshotCollector.collect(pid1);
            snapB = JvmSnapshotCollector.collect(pid2);
            labelA = "pid:" + pid1;
            labelB = "pid:" + pid2;
        } else {
            System.err.println("Need two PIDs or --load baseline.json");
            return;
        }

        if (json) {
            printJson(snapA, snapB, labelA, labelB);
            return;
        }

        printRich(snapA, snapB, labelA, labelB, useColor);
    }

    private void printRich(JvmSnapshot a, JvmSnapshot b, String labelA, String labelB, boolean c) {
        System.out.print(RichRenderer.brandedHeader(c, "compare",
                "Side-by-side JVM comparison"));
        System.out.println(RichRenderer.boxHeader(c, "JVM Comparison", WIDTH,
                labelA, "vs", labelB));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Header row
        String hdr = AnsiStyle.style(c, AnsiStyle.BOLD)
                + RichRenderer.padRight("Metric", 20)
                + RichRenderer.padLeft(labelA, 18)
                + RichRenderer.padLeft(labelB, 18)
                + RichRenderer.padLeft("Delta", 14)
                + AnsiStyle.style(c, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine("  " + hdr, WIDTH));
        System.out.println(RichRenderer.boxSeparator(WIDTH));

        // Heap
        row(c, "Heap Used", fmtBytes(a.heapUsed()), fmtBytes(b.heapUsed()),
                delta(a.heapUsed(), b.heapUsed()));
        row(c, "Heap Max", fmtBytes(a.heapMax()), fmtBytes(b.heapMax()),
                delta(a.heapMax(), b.heapMax()));
        row(c, "Heap %", pct(a.heapUsagePercent()), pct(b.heapUsagePercent()),
                deltaPct(a.heapUsagePercent(), b.heapUsagePercent()));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // GC
        row(c, "GC Overhead", pct(a.gcOverheadPercent()), pct(b.gcOverheadPercent()),
                deltaPct(a.gcOverheadPercent(), b.gcOverheadPercent()));
        row(c, "GC Count", String.valueOf(a.totalGcCount()), String.valueOf(b.totalGcCount()),
                delta(a.totalGcCount(), b.totalGcCount()));
        row(c, "GC Algorithm", a.gcAlgorithm(), b.gcAlgorithm(), "");

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // CPU
        if (a.processCpuLoad() >= 0 || b.processCpuLoad() >= 0) {
            row(c, "CPU %",
                    a.processCpuLoad() >= 0 ? pct(a.processCpuLoad() * 100) : "N/A",
                    b.processCpuLoad() >= 0 ? pct(b.processCpuLoad() * 100) : "N/A",
                    a.processCpuLoad() >= 0 && b.processCpuLoad() >= 0
                            ? deltaPct(a.processCpuLoad() * 100, b.processCpuLoad() * 100) : "");
        }

        // Threads
        row(c, "Threads", String.valueOf(a.threadCount()), String.valueOf(b.threadCount()),
                delta(a.threadCount(), b.threadCount()));
        row(c, "Blocked", String.valueOf(a.blockedThreads()), String.valueOf(b.blockedThreads()),
                delta(a.blockedThreads(), b.blockedThreads()));
        row(c, "Deadlocked", String.valueOf(a.deadlockedThreads()), String.valueOf(b.deadlockedThreads()),
                delta(a.deadlockedThreads(), b.deadlockedThreads()));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Classes
        row(c, "Loaded Classes", String.valueOf(a.loadedClassCount()), String.valueOf(b.loadedClassCount()),
                delta(a.loadedClassCount(), b.loadedClassCount()));

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(c, null, WIDTH));
    }

    private void row(boolean c, String metric, String valA, String valB, String delta) {
        String deltaColor = "";
        if (delta.startsWith("+")) deltaColor = AnsiStyle.style(c, AnsiStyle.RED);
        else if (delta.startsWith("-") && !delta.equals("-")) deltaColor = AnsiStyle.style(c, AnsiStyle.GREEN);

        String line = "  " + RichRenderer.padRight(metric, 20)
                + RichRenderer.padLeft(valA, 18)
                + RichRenderer.padLeft(valB, 18)
                + deltaColor + RichRenderer.padLeft(delta, 14)
                + AnsiStyle.style(c, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(line, WIDTH));
    }

    private static String delta(long a, long b) {
        if (a == b) return "—";
        long diff = a - b;
        double pct = b != 0 ? (double) diff / b * 100 : 0;
        String sign = diff > 0 ? "+" : "";
        return sign + diff + " (" + sign + String.format("%.0f%%", pct) + ")";
    }

    private static String deltaPct(double a, double b) {
        double diff = a - b;
        if (Math.abs(diff) < 0.1) return "—";
        String sign = diff > 0 ? "+" : "";
        return sign + String.format("%.1f%%", diff);
    }

    private static String fmtBytes(long bytes) {
        if (bytes <= 0) return "N/A";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "K";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + "M";
        return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
    }

    private static String pct(double v) { return String.format("%.1f%%", v); }

    private String snapshotToJson(JvmSnapshot s) {
        return "{\"heapUsed\":" + s.heapUsed()
                + ",\"heapMax\":" + s.heapMax()
                + ",\"gcOverhead\":" + s.gcOverheadPercent()
                + ",\"gcCount\":" + s.totalGcCount()
                + ",\"gcAlgorithm\":\"" + s.gcAlgorithm() + "\""
                + ",\"cpuLoad\":" + s.processCpuLoad()
                + ",\"threadCount\":" + s.threadCount()
                + ",\"blockedThreads\":" + s.blockedThreads()
                + ",\"deadlockedThreads\":" + s.deadlockedThreads()
                + ",\"loadedClasses\":" + s.loadedClassCount()
                + "}";
    }

    private JvmSnapshot loadBaseline(String path) {
        try {
            String json = Files.readString(Path.of(path));
            // Simple JSON parse for baseline fields
            return new JvmSnapshot(
                    jsonLong(json, "heapUsed"), jsonLong(json, "heapMax"), 0, 0,
                    java.util.Map.of(), java.util.List.of(),
                    jsonLong(json, "gcCount"), 0,
                    (long) (jsonDouble(json, "gcOverhead") / 100 * 1000), // approximate
                    jsonDouble(json, "cpuLoad"), -1, Runtime.getRuntime().availableProcessors(),
                    (int) jsonLong(json, "threadCount"), 0, 0,
                    java.util.Map.of(), (int) jsonLong(json, "deadlockedThreads"),
                    java.util.List.of(),
                    (int) jsonLong(json, "loadedClasses"), 0, 0, 0,
                    "", "", jsonString(json, "gcAlgorithm"), java.util.List.of()
            );
        } catch (Exception e) {
            System.err.println("Failed to load baseline: " + e.getMessage());
            return null;
        }
    }

    private static long jsonLong(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":");
        if (idx < 0) return 0;
        String rest = json.substring(idx + key.length() + 3).trim();
        StringBuilder num = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (c == '-' || c == '.' || Character.isDigit(c)) num.append(c);
            else break;
        }
        try { return (long) Double.parseDouble(num.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static double jsonDouble(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":");
        if (idx < 0) return 0;
        String rest = json.substring(idx + key.length() + 3).trim();
        StringBuilder num = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (c == '-' || c == '.' || Character.isDigit(c)) num.append(c);
            else break;
        }
        try { return Double.parseDouble(num.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String jsonString(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":\"");
        if (idx < 0) return "";
        int start = idx + key.length() + 4;
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "";
    }

    private static void printJson(JvmSnapshot a, JvmSnapshot b, String labelA, String labelB) {
        System.out.println("{\"a\":\"" + labelA + "\",\"b\":\"" + labelB + "\""
                + ",\"heapUsed\":[" + a.heapUsed() + "," + b.heapUsed() + "]"
                + ",\"heapMax\":[" + a.heapMax() + "," + b.heapMax() + "]"
                + ",\"gcOverhead\":[" + a.gcOverheadPercent() + "," + b.gcOverheadPercent() + "]"
                + ",\"threads\":[" + a.threadCount() + "," + b.threadCount() + "]"
                + ",\"cpu\":[" + a.processCpuLoad() + "," + b.processCpuLoad() + "]"
                + "}");
    }
}
