package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.ProfileResult;
import io.argus.cli.provider.ProfileProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.AsProfOptions;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.File;

/**
 * One-shot flame graph generation. Zero config, maximum impact.
 *
 * <p>Profiles a JVM for N seconds, generates an interactive HTML flame graph,
 * and opens it in the default browser. This is the "just show me" command —
 * simpler than {@code argus profile} which has many options and text output.
 *
 * <p>Usage:
 * <pre>
 * argus flame 12345                           # 10s CPU, open browser
 * argus flame 12345 --duration 30             # 30 seconds
 * argus flame 12345 --type alloc              # allocation flame graph
 * argus flame 12345 --output flame.html       # save to specific file
 * argus flame 12345 --no-open                 # don't open browser
 * argus flame 12345 --interval=1ms --cstack=dwarf  # advanced asprof flags
 * argus flame 12345 --output-format=jfr       # write .jfr instead of HTML
 * </pre>
 */
public final class FlameCommand implements Command {

    private static final int DEFAULT_DURATION = 10;

    @Override public String name() { return "flame"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public CommandMode mode() { return CommandMode.WRITE; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.flame.desc");
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

        int duration = DEFAULT_DURATION;
        String type = "cpu";
        String outputPath = null;
        String outputFormat = null;
        boolean openBrowser = true;
        boolean useColor = config.color();

        AsProfOptions.Builder optsBuilder = AsProfOptions.builder();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--duration=")) {
                try { duration = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--duration") && i + 1 < args.length) {
                try { duration = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--type=")) {
                type = arg.substring(7);
            } else if (arg.startsWith("--output=")) {
                outputPath = arg.substring(9);
            } else if (arg.equals("--output") && i + 1 < args.length) {
                outputPath = args[++i];
            } else if (arg.startsWith("--output-format=")) {
                outputFormat = arg.substring(16).toLowerCase();
            } else if (arg.equals("--no-open")) {
                openBrowser = false;
            } else if (arg.startsWith("--interval=")) {
                String v = arg.substring(11);
                String err = validateInterval(v, messages);
                if (err != null) { System.err.println(err); return; }
                optsBuilder.interval(v);
            } else if (arg.startsWith("--jstackdepth=")) {
                optsBuilder.jstackdepth(parseClampedInt(arg.substring(14), 1, 2048, 64));
            } else if (arg.startsWith("--cstack=")) {
                String v = arg.substring(9);
                String err = validateCstack(v, messages);
                if (err != null) { System.err.println(err); return; }
                optsBuilder.cstack(v);
            } else if (arg.equals("--threads")) {
                optsBuilder.perThread(true);
            } else if (arg.equals("--alluser")) {
                optsBuilder.allUser(true);
            } else if (arg.equals("--allkernel")) {
                optsBuilder.allKernel(true);
            } else if (arg.startsWith("--alloc=")) {
                optsBuilder.allocBytes(arg.substring(8));
            } else if (arg.equals("--live")) {
                optsBuilder.live(true);
            } else if (arg.startsWith("--include=")) {
                optsBuilder.addInclude(arg.substring(10));
            } else if (arg.startsWith("--exclude=")) {
                optsBuilder.addExclude(arg.substring(10));
            }
        }

        AsProfOptions opts = optsBuilder.outputFormat(outputFormat).build();

        // Mutual exclusion check
        if (opts.allUser && opts.allKernel) {
            System.err.println(messages.get("error.profile.adv.alluser.allkernel.exclusive"));
            return;
        }

        // Resolve output format and path
        String resolvedFmt = outputFormat != null ? outputFormat : "flamegraph";
        if (outputPath == null) {
            outputPath = System.getProperty("java.io.tmpdir")
                    + "/argus-flame-" + pid + formatToExtension(resolvedFmt);
        }

        ProfileProvider provider = registry.findProfileProvider(pid, null);
        if (provider == null) {
            System.err.println(messages.get("error.profile.adv.no.provider"));
            return;
        }

        // Progress message
        System.out.println();
        System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.BOLD, AnsiStyle.CYAN)
                + "argus flame" + AnsiStyle.style(useColor, AnsiStyle.RESET));
        System.out.println("  Profiling PID " + pid + " for " + duration + "s (" + type + " mode)...");
        System.out.println();

        ProfileResult result = provider.flameGraph(pid, type, duration, outputPath, opts);

        if (result.errorMessage() != null && !result.errorMessage().isEmpty()) {
            System.err.println("  " + AnsiStyle.style(useColor, AnsiStyle.RED)
                    + "✘ " + result.errorMessage() + AnsiStyle.style(useColor, AnsiStyle.RESET));
            return;
        }

        File outFile = new File(outputPath);
        if (!outFile.exists()) {
            System.err.println("  Output file not created. async-profiler may have failed.");
            return;
        }

        long sizeKB = outFile.length() / 1024;
        System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.GREEN)
                + "✔ " + result.totalSamples() + " samples collected"
                + AnsiStyle.style(useColor, AnsiStyle.RESET));
        System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.GREEN)
                + "✔ Output: " + outputPath + " (" + sizeKB + "KB)"
                + AnsiStyle.style(useColor, AnsiStyle.RESET));

        // Only open browser for HTML-producing formats
        if (openBrowser && ("flamegraph".equals(resolvedFmt) || "tree".equals(resolvedFmt))) {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                String safePath = new java.io.File(outputPath).getCanonicalFile().getAbsolutePath();
                ProcessBuilder pb;
                if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", safePath);
                } else if (os.contains("win")) {
                    java.awt.Desktop.getDesktop().browse(new java.io.File(safePath).toURI());
                    System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.CYAN)
                            + "→ Opened in browser"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET));
                    System.out.println();
                    return;
                } else {
                    pb = new ProcessBuilder("xdg-open", safePath);
                }
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                proc.getInputStream().close();
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.CYAN)
                        + "→ Opened in browser"
                        + AnsiStyle.style(useColor, AnsiStyle.RESET));
            } catch (Exception e) {
                System.out.println("  Open manually: " + outputPath);
            }
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Help
    // -------------------------------------------------------------------------

    private static void printHelp(boolean useColor, Messages messages) {
        System.out.println(RichRenderer.boxHeader(useColor, "argus flame", RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine("Usage: argus flame <pid> [options]", RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.emptyLine(RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Options:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --type=EVENT", 36)
                + messages.get("cmd.profile.event.type.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --duration=N", 36)
                + "Duration in seconds (default: 10)", RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --output=PATH", 36)
                + "Output file path", RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --output-format=FMT", 36)
                + messages.get("cmd.profile.event.output.format.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --no-open", 36)
                + "Do not open browser after profiling", RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.emptyLine(RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Advanced:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --interval=N[ms|us|ns]", 36)
                + messages.get("cmd.profile.adv.interval.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --jstackdepth=N", 36)
                + messages.get("cmd.profile.adv.jstackdepth.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --cstack=fp|dwarf|lbr|vm|no", 36)
                + messages.get("cmd.profile.adv.cstack.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --threads", 36)
                + messages.get("cmd.profile.adv.threads.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --alluser / --allkernel", 36)
                + messages.get("cmd.profile.adv.alluser.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --alloc=N[k|m|g]", 36)
                + messages.get("cmd.profile.adv.alloc.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --live", 36)
                + messages.get("cmd.profile.adv.live.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --include=PATTERN", 36)
                + messages.get("cmd.profile.adv.include.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --exclude=PATTERN", 36)
                + messages.get("cmd.profile.adv.exclude.desc"), RichRenderer.DEFAULT_WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, RichRenderer.DEFAULT_WIDTH));
    }

    // -------------------------------------------------------------------------
    // Validation helpers (mirrors ProfileCommand)
    // -------------------------------------------------------------------------

    private static String validateInterval(String v, Messages messages) {
        if (v == null || v.isEmpty()) return messages.get("error.profile.adv.interval.invalid", v);
        if (!v.endsWith("ms") && !v.endsWith("us") && !v.endsWith("ns")) {
            return messages.get("error.profile.adv.interval.invalid", v);
        }
        String num = v.substring(0, v.length() - 2);
        try {
            if (Long.parseLong(num) <= 0) return messages.get("error.profile.adv.interval.invalid", v);
        } catch (NumberFormatException e) {
            return messages.get("error.profile.adv.interval.invalid", v);
        }
        return null;
    }

    private static String validateCstack(String v, Messages messages) {
        if ("fp".equals(v) || "dwarf".equals(v) || "lbr".equals(v) || "vm".equals(v) || "no".equals(v)) {
            return null;
        }
        return messages.get("error.profile.adv.cstack.invalid", v);
    }

    private static int parseClampedInt(String s, int min, int max, int fallback) {
        try {
            return Math.min(max, Math.max(min, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatToExtension(String fmt) {
        if (fmt == null) return ".html";
        switch (fmt) {
            case "jfr":       return ".jfr";
            case "collapsed": return ".collapsed.txt";
            case "tree":      return ".html";
            case "text":      return ".txt";
            case "flat":      return ".txt";
            case "traces":    return ".txt";
            case "otlp":      return ".otlp.json";
            default:          return ".html";
        }
    }
}
