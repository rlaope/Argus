package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.ProfileResult;
import io.argus.cli.provider.ProfileProvider;
import io.argus.cli.provider.ProviderRegistry;
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
 * argus flame 12345                      # 10s CPU, open browser
 * argus flame 12345 --duration 30        # 30 seconds
 * argus flame 12345 --type alloc         # allocation flame graph
 * argus flame 12345 --output flame.html  # save to specific file
 * argus flame 12345 --no-open            # don't open browser
 * </pre>
 */
public final class FlameCommand implements Command {

    private static final int DEFAULT_DURATION = 10;

    @Override public String name() { return "flame"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.flame.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus flame <pid> [--duration N] [--type cpu|alloc|lock|wall] [--output file.html] [--no-open]");
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
        boolean openBrowser = true;
        boolean useColor = config.color();

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
            } else if (arg.equals("--no-open")) {
                openBrowser = false;
            }
        }

        // Default output path
        if (outputPath == null) {
            outputPath = System.getProperty("java.io.tmpdir") + "/argus-flame-" + pid + ".html";
        }

        ProfileProvider provider = registry.findProfileProvider(pid, null);
        if (provider == null) {
            System.err.println("async-profiler not available. Ensure Java process is accessible.");
            return;
        }

        // Progress message
        System.out.println();
        System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.BOLD, AnsiStyle.CYAN)
                + "argus flame" + AnsiStyle.style(useColor, AnsiStyle.RESET));
        System.out.println("  Profiling PID " + pid + " for " + duration + "s (" + type + " mode)...");
        System.out.println();

        // Profile with flame graph output
        ProfileResult result = provider.flameGraph(pid, type, duration, outputPath);

        if (result.errorMessage() != null && !result.errorMessage().isEmpty()) {
            System.err.println("  " + AnsiStyle.style(useColor, AnsiStyle.RED)
                    + "\u2718 " + result.errorMessage() + AnsiStyle.style(useColor, AnsiStyle.RESET));
            return;
        }

        File htmlFile = new File(outputPath);
        if (!htmlFile.exists()) {
            System.err.println("  Flame graph file not created. async-profiler may have failed.");
            return;
        }

        long sizeKB = htmlFile.length() / 1024;
        System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.GREEN)
                + "\u2714 " + result.totalSamples() + " samples collected"
                + AnsiStyle.style(useColor, AnsiStyle.RESET));
        System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.GREEN)
                + "\u2714 Flame graph: " + outputPath + " (" + sizeKB + "KB)"
                + AnsiStyle.style(useColor, AnsiStyle.RESET));

        // Open in browser
        if (openBrowser) {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", outputPath);
                } else if (os.contains("win")) {
                    pb = new ProcessBuilder("cmd", "/c", "start", outputPath);
                } else {
                    pb = new ProcessBuilder("xdg-open", outputPath);
                }
                pb.start();
                System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.CYAN)
                        + "\u2192 Opened in browser"
                        + AnsiStyle.style(useColor, AnsiStyle.RESET));
            } catch (Exception e) {
                System.out.println("  Open manually: " + outputPath);
            }
        }
        System.out.println();
    }
}
