package io.argus.cli.harness;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.Severity;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders harness output: a one-line live tick summary while the engine runs,
 * a rich final box at end-of-session, a structured JSON document for
 * automation, and an optional persistent JSON file.
 *
 * <p>Mirrors the visual conventions of {@code DoctorCommand} so users see
 * the same layout vocabulary across argus.
 */
public final class HarnessReporter {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    private final boolean color;
    private final boolean json;
    private final PrintStream out;

    public HarnessReporter(boolean color, boolean json, PrintStream out) {
        this.color = color;
        this.json = json;
        this.out = out;
    }

    public void onTick(HarnessEngine.TickEvent ev) {
        if (json) return; // JSON consumers wait for the final document
        long critical = ev.findingsThisTick.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
        long warning = ev.findingsThisTick.stream().filter(f -> f.severity() == Severity.WARNING).count();
        long info = ev.findingsThisTick.stream().filter(f -> f.severity() == Severity.INFO).count();
        long elapsedMs = ev.timed.timestampMs() - ev.session.snapshots().get(0).timestampMs();
        String line = String.format("  tick %3d  t+%-7s  heap %5.1f%%  gc %4.2f%%  thr %4d  -> %dC %dW %dI",
                ev.tickNumber,
                shortDuration(elapsedMs),
                ev.timed.snapshot().heapUsagePercent(),
                ev.timed.snapshot().gcOverheadPercent(),
                ev.timed.snapshot().threadCount(),
                critical, warning, info);
        out.println(AnsiStyle.style(color, AnsiStyle.DIM) + line + AnsiStyle.style(color, AnsiStyle.RESET));
    }

    public void onComplete(HarnessResult result, Path outFile) {
        if (json) {
            out.println(toJson(result));
        } else {
            printRich(result);
        }
        if (outFile != null) {
            try {
                Files.writeString(outFile, toJson(result));
                out.println("  Session JSON: " + outFile.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("[Argus harness] failed to write session file: " + e.getMessage());
            }
        }
    }

    private void printRich(HarnessResult r) {
        out.print(RichRenderer.brandedHeader(color, "harness",
                "Continuous JVM monitoring + optimization + troubleshooting"));
        out.println(RichRenderer.boxHeader(color, "Harness Session", WIDTH,
                "pid:" + r.pid(),
                "profile:" + r.profile().name().toLowerCase(),
                "duration:" + RichRenderer.formatDuration(r.durationMs()),
                "ticks:" + r.sampleCount()));
        out.println(RichRenderer.emptyLine(WIDTH));

        if (r.findings().isEmpty()) {
            out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(color, AnsiStyle.GREEN, AnsiStyle.BOLD)
                            + "✔ No issues detected during the session"
                            + AnsiStyle.style(color, AnsiStyle.RESET), WIDTH));
            out.println(RichRenderer.emptyLine(WIDTH));
        } else {
            String summary = "  " + sevTag(Severity.CRITICAL, r.criticalCount())
                    + "  " + sevTag(Severity.WARNING, r.warningCount())
                    + "  " + sevTag(Severity.INFO, r.infoCount());
            out.println(RichRenderer.boxLine(summary, WIDTH));
            out.println(RichRenderer.emptyLine(WIDTH));

            for (Finding f : r.findings()) {
                String sevColor;
                switch (f.severity()) {
                    case CRITICAL: sevColor = AnsiStyle.style(color, AnsiStyle.RED, AnsiStyle.BOLD);    break;
                    case WARNING:  sevColor = AnsiStyle.style(color, AnsiStyle.YELLOW, AnsiStyle.BOLD); break;
                    default:       sevColor = AnsiStyle.style(color, AnsiStyle.CYAN);                   break;
                }
                String key = f.category() + "|" + f.title();
                Integer hits = r.ruleHitCounts().get(key);
                String hitSuffix = (hits != null && hits > 1) ? " (fired " + hits + " ticks)" : "";
                out.println(RichRenderer.boxLine(
                        "  " + sevColor + f.severity().icon() + " " + f.severity().label()
                                + ": " + f.title() + hitSuffix
                                + AnsiStyle.style(color, AnsiStyle.RESET), WIDTH));
                if (!f.detail().isEmpty()) {
                    out.println(RichRenderer.boxLine(
                            "     " + AnsiStyle.style(color, AnsiStyle.DIM) + f.detail()
                                    + AnsiStyle.style(color, AnsiStyle.RESET), WIDTH));
                }
                for (String rec : f.recommendations()) {
                    out.println(RichRenderer.boxLine("     → " + rec, WIDTH));
                }
                out.println(RichRenderer.emptyLine(WIDTH));
            }

            Set<String> flags = new LinkedHashSet<>();
            for (Finding f : r.findings()) flags.addAll(f.suggestedFlags());
            if (!flags.isEmpty()) {
                out.println(RichRenderer.boxSeparator(WIDTH));
                out.println(RichRenderer.emptyLine(WIDTH));
                out.println(RichRenderer.boxLine(
                        "  " + AnsiStyle.style(color, AnsiStyle.BOLD, AnsiStyle.CYAN)
                                + "Suggested JVM Flags"
                                + AnsiStyle.style(color, AnsiStyle.RESET), WIDTH));
                out.println(RichRenderer.emptyLine(WIDTH));
                for (String flag : flags) {
                    out.println(RichRenderer.boxLine(
                            "    " + AnsiStyle.style(color, AnsiStyle.GREEN) + flag
                                    + AnsiStyle.style(color, AnsiStyle.RESET), WIDTH));
                }
                out.println(RichRenderer.emptyLine(WIDTH));
            }
        }

        String footer = r.exitCode() == 0 ? "✔ healthy"
                : r.exitCode() == 1 ? "⚠ warnings" : "✘ critical";
        out.println(RichRenderer.boxFooter(color, footer, WIDTH));
    }

    private String sevTag(Severity s, long count) {
        if (count == 0) return "";
        String c;
        switch (s) {
            case CRITICAL: c = AnsiStyle.style(color, AnsiStyle.RED, AnsiStyle.BOLD); break;
            case WARNING:  c = AnsiStyle.style(color, AnsiStyle.YELLOW, AnsiStyle.BOLD); break;
            default:       c = AnsiStyle.style(color, AnsiStyle.CYAN); break;
        }
        return c + count + " " + s.label().toLowerCase() + AnsiStyle.style(color, AnsiStyle.RESET);
    }

    public static String toJson(HarnessResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"pid\":").append(r.pid());
        sb.append(",\"profile\":\"").append(r.profile().name().toLowerCase()).append('"');
        sb.append(",\"startTimeMs\":").append(r.startTimeMs());
        sb.append(",\"endTimeMs\":").append(r.endTimeMs());
        sb.append(",\"durationMs\":").append(r.durationMs());
        sb.append(",\"sampleCount\":").append(r.sampleCount());
        sb.append(",\"exitCode\":").append(r.exitCode());
        sb.append(",\"counts\":{");
        sb.append("\"critical\":").append(r.criticalCount());
        sb.append(",\"warning\":").append(r.warningCount());
        sb.append(",\"info\":").append(r.infoCount());
        sb.append("}");
        sb.append(",\"findings\":[");
        List<Finding> findings = r.findings();
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            if (i > 0) sb.append(',');
            String key = f.category() + "|" + f.title();
            Integer hits = r.ruleHitCounts().get(key);
            sb.append("{\"severity\":\"").append(f.severity().label()).append('"');
            sb.append(",\"category\":\"").append(RichRenderer.escapeJson(f.category())).append('"');
            sb.append(",\"title\":\"").append(RichRenderer.escapeJson(f.title())).append('"');
            sb.append(",\"detail\":\"").append(RichRenderer.escapeJson(f.detail())).append('"');
            sb.append(",\"hits\":").append(hits == null ? 1 : hits);
            sb.append(",\"recommendations\":[");
            List<String> recs = f.recommendations();
            for (int j = 0; j < recs.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(RichRenderer.escapeJson(recs.get(j))).append('"');
            }
            sb.append("],\"suggestedFlags\":[");
            List<String> flags = f.suggestedFlags();
            for (int j = 0; j < flags.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(RichRenderer.escapeJson(flags.get(j))).append('"');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String shortDuration(long ms) {
        if (ms < 60_000) return (ms / 1000) + "s";
        long mins = ms / 60_000;
        long secs = (ms % 60_000) / 1000;
        return mins + "m" + secs + "s";
    }

    @SuppressWarnings("unused")
    private static String pad(Duration d) { return d.toString(); }
}
