package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.HeapDumpResult;
import io.argus.cli.provider.HeapDumpProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Generates a heap dump (.hprof) from a target JVM process.
 * Displays a Stop-The-World warning and asks for confirmation before proceeding.
 */
public final class HeapDumpCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "heapdump";
    }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.heapdump.desc");
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
        String filePath = null;
        boolean liveOnly = true;   // default: live objects only
        boolean skipConfirm = false;
        boolean json = "json".equals(config.format());
        String sourceOverride = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--file=")) {
                filePath = arg.substring(7);
            } else if (arg.equals("--all")) {
                liveOnly = false;
            } else if (arg.equals("--live")) {
                liveOnly = true;
            } else if (arg.equals("--yes") || arg.equals("-y")) {
                skipConfirm = true;
            } else if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            }
        }

        // Generate default filename if not provided
        if (filePath == null) {
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            filePath = "./heap-" + pid + "-" + timestamp + ".hprof";
        }

        String mode = liveOnly
                ? messages.get("status.heapdump.mode.live")
                : messages.get("status.heapdump.mode.all");

        // STW Warning confirmation (unless --yes/-y is passed)
        if (!skipConfirm) {
            boolean useColor = config.color();
            String yellow = AnsiStyle.style(useColor, AnsiStyle.YELLOW);
            String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
            String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);
            String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);

            System.out.println();
            System.out.println(yellow + bold + "\u26A0 " + messages.get("warn.heapdump.stw") + reset);
            System.out.println(dim + "  - " + messages.get("warn.heapdump.frozen") + reset);
            System.out.println(dim + "  - " + messages.get("warn.heapdump.duration") + reset);
            System.out.println(dim + "  - " + messages.get("warn.heapdump.size") + reset);
            System.out.println();
            System.out.println("  Target: PID " + pid);
            System.out.println("  Output: " + filePath);
            System.out.println("  Mode:   " + mode);
            System.out.println();
            System.out.print(messages.get("warn.heapdump.proceed"));
            System.out.flush();

            String answer = "";
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                answer = reader.readLine();
            } catch (Exception ignored) {
                // treat as no answer -> cancel
            }

            if (answer == null || (!answer.equals("y") && !answer.equals("Y"))) {
                System.out.println(messages.get("warn.heapdump.cancelled"));
                return;
            }
        }

        HeapDumpProvider provider = registry.findHeapDumpProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        HeapDumpResult result = provider.heapDump(pid, filePath, liveOnly);

        if (json) {
            printJson(result);
        } else {
            boolean useColor = config.color();
            System.out.print(RichRenderer.brandedHeader(useColor, "heapdump",
                    messages.get("desc.heapdump")));
            printResult(result, pid, mode, useColor, messages);
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private static void printResult(HeapDumpResult result, long pid, String mode,
                                    boolean useColor, Messages messages) {
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.heapdump"),
                WIDTH, "pid:" + pid));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if ("error".equalsIgnoreCase(result.status())) {
            String red = AnsiStyle.style(useColor, AnsiStyle.RED);
            String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(
                    red + "\u2717 " + (result.errorMessage() != null ? result.errorMessage() : "unknown error") + reset,
                    WIDTH));
        } else {
            String green = AnsiStyle.style(useColor, AnsiStyle.GREEN);
            String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);

            System.out.println(RichRenderer.boxLine(
                    green + "\u2713 " + messages.get("status.heapdump.success") + reset, WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));

            String sizeFormatted = formatBytes(result.fileSizeBytes());
            System.out.println(RichRenderer.boxLine(
                    String.format(messages.get("status.heapdump.file"), result.filePath()), WIDTH));
            System.out.println(RichRenderer.boxLine(
                    String.format(messages.get("status.heapdump.size"), sizeFormatted), WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "Mode:   " + mode, WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));

            String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);
            String resetDim = AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(dim + "Analyze with:" + resetDim, WIDTH));
            String fileName = result.filePath();
            // strip leading "./" for display in jhat suggestion
            if (fileName.startsWith("./")) {
                fileName = fileName.substring(2);
            }
            System.out.println(RichRenderer.boxLine(dim + "  jhat " + fileName + resetDim, WIDTH));
            System.out.println(RichRenderer.boxLine(dim + "  jvisualvm" + resetDim, WIDTH));
            System.out.println(RichRenderer.boxLine(dim + "  Eclipse MAT" + resetDim, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printHelp(boolean useColor, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(useColor, "heapdump",
                messages.get("desc.heapdump")));
        System.out.println(RichRenderer.boxHeader(useColor, "Usage", WIDTH));
        System.out.println(RichRenderer.boxLine("argus heapdump <pid> [options]", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Options:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --file=PATH", 28) + "Output file (default: ./heap-<pid>-<ts>.hprof)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --live", 28) + "Dump live objects only, triggers GC first (default)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --all", 28) + "Dump all objects including garbage", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --yes, -y", 28) + "Skip the STW confirmation prompt", WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(HeapDumpResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"").append(RichRenderer.escapeJson(result.status())).append('"');
        sb.append(",\"filePath\":\"").append(RichRenderer.escapeJson(result.filePath())).append('"');
        sb.append(",\"fileSizeBytes\":").append(result.fileSizeBytes());
        if (result.errorMessage() != null) {
            sb.append(",\"errorMessage\":\"").append(RichRenderer.escapeJson(result.errorMessage())).append('"');
        } else {
            sb.append(",\"errorMessage\":null");
        }
        sb.append('}');
        System.out.println(sb);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024.0) return String.format("%.1f GB", gb);
        double tb = gb / 1024.0;
        return String.format("%.1f TB", tb);
    }
}
