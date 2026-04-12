package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.AgeDistribution;
import io.argus.cli.model.GcNewResult;
import io.argus.cli.provider.GcAgeProvider;
import io.argus.cli.provider.GcNewProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.List;

/**
 * Shows young generation GC detail: survivor spaces, tenuring threshold, eden.
 * With --age-histogram shows per-age object distribution.
 */
public final class GcNewCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int BAR_WIDTH = 16;
    private static final int AGE_BAR_WIDTH = 20;

    @Override
    public String name() { return "gcnew"; }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gcnew.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) { System.err.println(messages.get("error.pid.required")); return; }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        boolean ageHistogram = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--source=")) sourceOverride = args[i].substring(9);
            else if (args[i].equals("--format=json")) json = true;
            else if (args[i].equals("--age-histogram")) ageHistogram = true;
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        GcNewProvider provider = registry.findGcNewProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        GcNewResult result = provider.getGcNew(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "gcnew", messages.get("desc.gcnew")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.gcnew"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Eden
        double edenPct = result.ec() > 0 ? (result.eu() / result.ec()) * 100 : 0;
        String edenBar = RichRenderer.progressBar(useColor, edenPct, BAR_WIDTH);
        System.out.println(RichRenderer.boxLine("  Eden  " + edenBar + "  "
                + RichRenderer.formatKB((long) result.eu()) + " / " + RichRenderer.formatKB((long) result.ec())
                + "  (" + String.format("%.0f%%", edenPct) + ")", WIDTH));

        // S0
        double s0Pct = result.s0c() > 0 ? (result.s0u() / result.s0c()) * 100 : 0;
        String s0Bar = RichRenderer.progressBar(useColor, s0Pct, BAR_WIDTH);
        System.out.println(RichRenderer.boxLine("  S0    " + s0Bar + "  "
                + RichRenderer.formatKB((long) result.s0u()) + " / " + RichRenderer.formatKB((long) result.s0c())
                + "  (" + String.format("%.0f%%", s0Pct) + ")", WIDTH));

        // S1
        double s1Pct = result.s1c() > 0 ? (result.s1u() / result.s1c()) * 100 : 0;
        String s1Bar = RichRenderer.progressBar(useColor, s1Pct, BAR_WIDTH);
        System.out.println(RichRenderer.boxLine("  S1    " + s1Bar + "  "
                + RichRenderer.formatKB((long) result.s1u()) + " / " + RichRenderer.formatKB((long) result.s1c())
                + "  (" + String.format("%.0f%%", s1Pct) + ")", WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Tenuring
        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);

        String ttLine = bold + messages.get("gcnew.tenuring") + reset + "  "
                + result.tt() + " / " + result.mtt();
        System.out.println(RichRenderer.boxLine(ttLine, WIDTH));

        String dssLine = bold + messages.get("gcnew.dss") + reset + "        "
                + RichRenderer.formatKB((long) result.dss());
        System.out.println(RichRenderer.boxLine(dssLine, WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // YGC
        String gcLine = "YGC: " + result.ygc() + "  (" + String.format("%.3fs", result.ygct()) + ")";
        System.out.println(RichRenderer.boxLine(gcLine, WIDTH));

        // Age histogram
        if (ageHistogram) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxSeparator(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(useColor, AnsiStyle.BOLD, AnsiStyle.CYAN)
                            + messages.get("gcnew.age.title")
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));

            GcAgeProvider ageProvider = registry.findGcAgeProvider(pid, sourceOverride);
            if (ageProvider == null) {
                System.out.println(RichRenderer.boxLine(
                        "  " + messages.get("gcnew.age.unavailable"), WIDTH));
            } else {
                AgeDistribution dist = ageProvider.getAgeDistribution(pid);
                renderAgeHistogram(dist, useColor);
            }
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private void renderAgeHistogram(AgeDistribution dist, boolean useColor) {
        List<AgeDistribution.AgeEntry> entries = dist.entries();

        if (entries.isEmpty()) {
            System.out.println(RichRenderer.boxLine(
                    "  " + "No age data available. Run with -Xlog:gc+age=debug for live data.", WIDTH));
            if (dist.tenuringThreshold() > 0) {
                System.out.println(RichRenderer.boxLine(
                        "  Tenuring: " + dist.tenuringThreshold()
                                + " / max: " + dist.maxTenuringThreshold(), WIDTH));
            }
            return;
        }

        // Header
        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(
                "  " + bold
                        + RichRenderer.padRight("Age", 5)
                        + RichRenderer.padLeft("Bytes", 12)
                        + RichRenderer.padLeft("Cumulative", 13)
                        + "   Bar"
                        + reset, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        long total = dist.survivorCapacity();
        if (total == 0 && !entries.isEmpty()) total = entries.getLast().cumulativeBytes();

        // Group ages >= 6 together
        long ageGe6Bytes = 0;
        long ageGe6Cumulative = 0;
        boolean hasHighAges = false;

        for (AgeDistribution.AgeEntry e : entries) {
            if (e.age() >= 6) {
                ageGe6Bytes += e.bytes();
                ageGe6Cumulative = e.cumulativeBytes();
                hasHighAges = true;
            }
        }

        for (AgeDistribution.AgeEntry e : entries) {
            if (e.age() >= 6) continue;
            renderAgeLine(e.age(), String.valueOf(e.age()), e.bytes(), e.cumulativeBytes(),
                    total, useColor, dist.tenuringThreshold());
        }

        if (hasHighAges) {
            renderAgeLine(-1, "6+", ageGe6Bytes, ageGe6Cumulative, total, useColor, dist.tenuringThreshold());
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Summary lines
        long survivorCap = dist.survivorCapacity();
        long desiredSize = dist.desiredSurvivorSize();
        int survivorPct = survivorCap > 0 && desiredSize > 0
                ? (int) Math.min(100, total * 100 / desiredSize) : 0;

        System.out.println(RichRenderer.boxLine(
                "  Tenuring: " + dist.tenuringThreshold() + " / max: " + dist.maxTenuringThreshold(), WIDTH));
        if (desiredSize > 0) {
            System.out.println(RichRenderer.boxLine(
                    "  Survivor: " + survivorPct + "% ("
                            + RichRenderer.formatKB(total / 1024)
                            + " / " + RichRenderer.formatKB(desiredSize / 1024) + ")", WIDTH));
        }

        // Insights
        System.out.println(RichRenderer.emptyLine(WIDTH));
        if (!entries.isEmpty() && total > 0) {
            long age1Bytes = entries.getFirst().age() == 1 ? entries.getFirst().bytes() : 0;
            int age1Pct = (int) (age1Bytes * 100 / total);
            if (age1Pct >= 50) {
                System.out.println(RichRenderer.boxLine(
                        "  \u2192 " + age1Pct + "% die at age 1 (healthy)", WIDTH));
            }
        }

        // MaxTenuringThreshold suggestion
        if (dist.tenuringThreshold() > 4 && !entries.isEmpty()) {
            // Find age at which 80% is accumulated
            long threshold80 = (long) (total * 0.80);
            for (AgeDistribution.AgeEntry e : entries) {
                if (e.cumulativeBytes() >= threshold80) {
                    if (e.age() < dist.maxTenuringThreshold()) {
                        System.out.println(RichRenderer.boxLine(
                                "  \u2192 Consider -XX:MaxTenuringThreshold=" + e.age(), WIDTH));
                    }
                    break;
                }
            }
        }
    }

    private void renderAgeLine(int age, String label, long bytes, long cumulative,
                               long total, boolean useColor, int tenuringThreshold) {
        int pct = total > 0 ? (int) (bytes * 100 / total) : 0;
        int barLen = AGE_BAR_WIDTH * pct / 100;
        String bar = "\u2588".repeat(Math.max(0, barLen));

        boolean atThreshold = age == tenuringThreshold;
        String color = atThreshold ? AnsiStyle.style(useColor, AnsiStyle.YELLOW) : "";
        String reset = atThreshold ? AnsiStyle.style(useColor, AnsiStyle.RESET) : "";

        String line = color
                + RichRenderer.padLeft(label, 3) + "   "
                + RichRenderer.padLeft(RichRenderer.formatKB(bytes / 1024), 10) + "   "
                + RichRenderer.padLeft(RichRenderer.formatKB(cumulative / 1024), 10) + "   "
                + RichRenderer.padRight(bar, AGE_BAR_WIDTH) + "  " + pct + "%"
                + reset;
        System.out.println(RichRenderer.boxLine("  " + line, WIDTH));
    }

    private static void printJson(GcNewResult r) {
        System.out.println("{\"s0c\":" + r.s0c() + ",\"s1c\":" + r.s1c()
                + ",\"s0u\":" + r.s0u() + ",\"s1u\":" + r.s1u()
                + ",\"tt\":" + r.tt() + ",\"mtt\":" + r.mtt()
                + ",\"dss\":" + r.dss() + ",\"ec\":" + r.ec() + ",\"eu\":" + r.eu()
                + ",\"ygc\":" + r.ygc() + ",\"ygct\":" + r.ygct() + "}");
    }
}
