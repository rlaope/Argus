package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.MethodSample;
import io.argus.cli.model.ProfileResult;
import io.argus.cli.provider.ProfileProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.List;

/**
 * Profiles a target JVM process using async-profiler.
 * Supports CPU, allocation, lock, and wall-clock profiling modes.
 */
public final class ProfileCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int MINI_BAR_WIDTH = 4;

    @Override
    public String name() {
        return "profile";
    }

    @Override public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.profile.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            printHelp(config.color(), messages);
            return;
        }

        long pid;
        try {
            pid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println(messages.get("error.pid.invalid", args[0]));
            return;
        }

        // Parse options
        String type = "cpu";
        int durationSec = 30;
        boolean flame = false;
        String file = null;
        int top = 20;
        String sourceOverride = null;
        boolean json = "json".equals(config.format());

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--type=")) {
                type = arg.substring(7).toLowerCase();
            } else if (arg.startsWith("--duration=")) {
                try { durationSec = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--duration") && i + 1 < args.length) {
                try { durationSec = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--flame")) {
                flame = true;
            } else if (arg.startsWith("--file=")) {
                file = arg.substring(7);
            } else if (arg.startsWith("--top=")) {
                try { top = Integer.parseInt(arg.substring(6)); } catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            }
        }

        // Validate type
        if (!isValidType(type)) {
            System.err.println(messages.get("error.profile.invalid.type", type));
            return;
        }

        ProfileProvider provider = registry.findProfileProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        boolean useColor = config.color();

        if (flame) {
            String outputFile = file != null ? file : "flamegraph-" + pid + "-" + type + ".html";
            System.out.println(messages.get("status.profiling", pid, durationSec, type));
            ProfileResult result = provider.flameGraph(pid, type, durationSec, outputFile);
            if ("error".equals(result.status())) {
                System.err.println(messages.get("error.profile.asprof.failed",
                        result.errorMessage() != null ? result.errorMessage() : "unknown error"));
            } else {
                System.out.println(messages.get("status.flame.generated", outputFile));
                openBrowser(outputFile);
            }
        } else {
            System.out.println(messages.get("status.profiling", pid, durationSec, type));
            ProfileResult result = provider.profile(pid, type, durationSec);
            if (result == null) return;
            if (json) {
                printJson(result, top);
            } else {
                System.out.print(RichRenderer.brandedHeader(useColor, "profile",
                        messages.get("desc.profile")));
                printResult(result, pid, top, useColor, messages);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private static void printResult(ProfileResult result, long pid, int top,
                                    boolean useColor, Messages messages) {
        if ("error".equals(result.status())) {
            System.err.println(messages.get("error.profile.asprof.failed",
                    result.errorMessage() != null ? result.errorMessage() : "unknown error"));
            return;
        }

        String samplesFormatted = formatWithCommas(result.totalSamples());
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.profile"), WIDTH,
                "pid:" + pid,
                result.type() + " " + result.durationSec() + "s",
                samplesFormatted + " samples"));

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Column header line
        String colHeader = "  " + RichRenderer.padRight("#", 4)
                + RichRenderer.padRight(messages.get("label.method"), 46)
                + RichRenderer.padLeft(messages.get("label.samples"), 10)
                + "  " + RichRenderer.padLeft("%", 7);
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + colHeader
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

        // Separator
        String sep = "  " + "\u2500".repeat(4)
                + "  " + "\u2500".repeat(44)
                + "  " + "\u2500".repeat(10)
                + "  " + "\u2500".repeat(8);
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.DIM) + sep
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

        List<MethodSample> methods = result.topMethods();
        int limit = Math.min(top, methods.size());
        for (int i = 0; i < limit; i++) {
            MethodSample m = methods.get(i);
            String bar = miniBar(m.percentage(), MINI_BAR_WIDTH);
            String rank = RichRenderer.padRight(String.valueOf(i + 1), 4);
            String method = RichRenderer.padRight(RichRenderer.truncate(m.method(), 44), 46);
            String samples = RichRenderer.padLeft(formatWithCommas(m.samples()), 10);
            String pct = RichRenderer.padLeft(String.format("%.1f%%", m.percentage()), 7);
            String line = "  " + rank + method + samples + "  " + pct + "  " + bar;
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printHelp(boolean useColor, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(useColor, "profile",
                messages.get("cmd.profile.desc")));
        System.out.println(RichRenderer.boxHeader(useColor, "Usage", WIDTH));
        System.out.println(RichRenderer.boxLine("argus profile <pid> [options]", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Options:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --type=cpu|alloc|lock|wall", 36)
                + "Profiling type (default: cpu)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --duration=N", 36)
                + "Duration in seconds (default: 30)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --flame", 36)
                + "Generate flame graph HTML", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --file=NAME", 36)
                + "Output file for flame graph", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --top=N", 36)
                + "Show top N methods (default: 20)", WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(ProfileResult result, int top) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"").append(RichRenderer.escapeJson(result.status())).append('"');
        if (result.type() != null) {
            sb.append(",\"type\":\"").append(RichRenderer.escapeJson(result.type())).append('"');
        }
        sb.append(",\"durationSec\":").append(result.durationSec());
        sb.append(",\"totalSamples\":").append(result.totalSamples());
        sb.append(",\"topMethods\":[");
        List<MethodSample> methods = result.topMethods();
        int limit = Math.min(top, methods.size());
        for (int i = 0; i < limit; i++) {
            MethodSample m = methods.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"method\":\"").append(RichRenderer.escapeJson(m.method())).append('"');
            sb.append(",\"samples\":").append(m.samples());
            sb.append(",\"percentage\":").append(String.format("%.1f", m.percentage()));
            sb.append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static boolean isValidType(String type) {
        return "cpu".equals(type) || "alloc".equals(type) || "lock".equals(type) || "wall".equals(type);
    }

    /**
     * Renders a mini inline progress bar using filled block characters.
     * Width is capped at {@code maxWidth} filled chars.
     */
    private static String miniBar(double pct, int maxWidth) {
        int filled = (int) Math.round(Math.min(100.0, Math.max(0.0, pct)) / 100.0 * maxWidth);
        StringBuilder sb = new StringBuilder(maxWidth);
        for (int i = 0; i < filled; i++) {
            sb.append('\u2588');
        }
        return sb.toString();
    }

    /**
     * Formats a long value with comma thousands separators.
     * Example: 15234 -> "15,234"
     */
    private static String formatWithCommas(long n) {
        String s = String.valueOf(n);
        if (s.length() <= 3) return s;
        StringBuilder sb = new StringBuilder();
        int rem = s.length() % 3;
        if (rem > 0) {
            sb.append(s, 0, rem);
        }
        for (int i = rem; i < s.length(); i += 3) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s, i, i + 3);
        }
        return sb.toString();
    }

    /**
     * Attempts to open a file in the default browser.
     * Tries java.awt.Desktop first, then OS-specific fallbacks.
     */
    private static void openBrowser(String path) {
        System.out.println("Opening in browser...");
        // Try java.awt.Desktop (may not be available in headless environments)
        try {
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            Object desktop = desktopClass.getMethod("getDesktop").invoke(null);
            java.io.File f = new java.io.File(path);
            desktopClass.getMethod("browse", java.net.URI.class).invoke(desktop, f.toURI());
            return;
        } catch (Exception ignored) {
            // fall through to OS-specific approach
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd;
        if (os.contains("mac")) {
            cmd = new String[]{"open", path};
        } else {
            cmd = new String[]{"xdg-open", path};
        }
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception ignored) {
            // browser open is best-effort; silently skip
        }
    }
}
