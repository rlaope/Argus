package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traces method execution in a running JVM by collecting rapid thread dumps
 * and aggregating stack frames that match the target class.method.
 *
 * <p>Uses {@code jcmd <pid> Thread.print} at high frequency (10 samples/sec)
 * for the specified duration, then builds a call tree from matching traces.
 */
public final class TraceCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int SAMPLE_INTERVAL_MS = 100; // 10 samples/sec
    private static final int DEFAULT_DURATION_SEC = 10;

    @Override
    public String name() {
        return "trace";
    }

    @Override
    public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public CommandMode mode() { return CommandMode.WRITE; }

    @Override
    public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.trace.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length < 2) {
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

        String target = args[1]; // e.g. "com.example.OrderService.createOrder"
        int durationSec = DEFAULT_DURATION_SEC;

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--duration=")) {
                try { durationSec = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--duration") && i + 1 < args.length) {
                try { durationSec = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            }
        }

        // Validate target format (must contain at least one dot for class.method)
        if (!target.contains(".")) {
            System.err.println(messages.get("error.trace.invalid.target", target));
            return;
        }

        boolean useColor = config.color();
        int totalSamples = durationSec * (1000 / SAMPLE_INTERVAL_MS);

        System.out.println(messages.get("status.trace.sampling", pid, durationSec, totalSamples));

        List<List<String>> matchedStacks = collectSamples(pid, target, durationSec, totalSamples);

        if (matchedStacks.isEmpty()) {
            System.out.println(messages.get("status.trace.no.match", target, totalSamples));
            return;
        }

        // Build call tree from matched stacks
        CallNode root = buildCallTree(matchedStacks);

        printResult(root, pid, target, durationSec, totalSamples, matchedStacks.size(), useColor, messages);
    }

    // -------------------------------------------------------------------------
    // Sampling
    // -------------------------------------------------------------------------

    /**
     * Collects thread dump samples at SAMPLE_INTERVAL_MS intervals for durationSec seconds.
     * Returns stacks (as frame lists, outermost first) that contain the target method.
     */
    static List<List<String>> collectSamples(long pid, String target, int durationSec, int totalSamples) {
        List<List<String>> matched = new ArrayList<>();
        // Derive the simple method-only part from the target for matching
        // e.g. "com.example.OrderService.createOrder" -> class="com.example.OrderService", method="createOrder"
        int lastDot = target.lastIndexOf('.');
        String targetClass = target.substring(0, lastDot);
        String targetMethod = target.substring(lastDot + 1);

        for (int i = 0; i < totalSamples; i++) {
            try {
                String output = executeJcmdThreadPrint(pid);
                List<String> stack = extractMatchingStack(output, targetClass, targetMethod);
                if (stack != null) {
                    matched.add(stack);
                }
                if (i < totalSamples - 1) {
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // jcmd failure: skip this sample, keep going
            }
        }
        return matched;
    }

    /**
     * Executes {@code jcmd <pid> Thread.print} and returns stdout.
     * Factored out to allow unit-test overrides via subclass.
     */
    static String executeJcmdThreadPrint(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("jcmd", String.valueOf(pid), "Thread.print");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!sb.isEmpty()) sb.append('\n');
                        sb.append(line);
                    }
                } catch (Exception ignored) {}
            }, "trace-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            reader.join(1000);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Scans a thread dump output for any thread whose stack contains the target class+method.
     * Returns the stack frames in order from the target frame outward (target first),
     * or null if no match found.
     */
    static List<String> extractMatchingStack(String dump, String targetClass, String targetMethod) {
        if (dump == null || dump.isEmpty()) return null;

        String[] lines = dump.split("\n");
        List<String> currentStack = new ArrayList<>();
        boolean inThread = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Thread header line (starts with ")
            if (trimmed.startsWith("\"")) {
                // Check previous thread's stack
                List<String> result = findTargetInStack(currentStack, targetClass, targetMethod);
                if (result != null) return result;
                currentStack = new ArrayList<>();
                inThread = true;
                continue;
            }

            if (!inThread) continue;

            if (trimmed.startsWith("at ")) {
                currentStack.add(trimmed.substring(3));
            }
        }

        // Check last thread
        return findTargetInStack(currentStack, targetClass, targetMethod);
    }

    /**
     * Returns the sub-stack starting from the target frame going up to caller frames,
     * or null if the target frame is not present.
     */
    private static List<String> findTargetInStack(List<String> stack, String targetClass, String targetMethod) {
        if (stack.isEmpty()) return null;

        // Stack is in order: innermost (bottom of call) first in jstack output
        // Find the target frame
        int targetIdx = -1;
        for (int i = 0; i < stack.size(); i++) {
            String frame = stack.get(i);
            if (frame.startsWith(targetClass + "." + targetMethod)) {
                targetIdx = i;
                break;
            }
        }

        if (targetIdx < 0) return null;

        // Return from targetIdx backwards to 0 (target + all callees below it)
        // In jstack output, index 0 = innermost frame (currently executing)
        // So frames 0..targetIdx represent callees of the target, and targetIdx is the target
        // We want: target + any callee frames (0 to targetIdx, reversed so target is first)
        List<String> result = new ArrayList<>();
        for (int i = targetIdx; i >= 0; i--) {
            result.add(stack.get(i));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Call tree
    // -------------------------------------------------------------------------

    static CallNode buildCallTree(List<List<String>> stacks) {
        CallNode root = new CallNode("__root__");
        for (List<String> stack : stacks) {
            CallNode current = root;
            for (String frame : stack) {
                current = current.getOrCreate(frame);
                current.hits++;
            }
        }
        return root;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private static void printResult(CallNode root, long pid, String target, int durationSec,
                                    int totalSamples, int matchedSamples,
                                    boolean useColor, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(useColor, "trace",
                messages.get("desc.trace")));

        String header = RichRenderer.boxHeader(useColor, messages.get("header.trace"), WIDTH,
                "pid:" + pid,
                durationSec + "s",
                totalSamples + " samples");
        System.out.println(header);
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Print call tree (children of root are the target method entries)
        for (CallNode child : root.children.values()) {
            printNode(child, "", true, totalSamples, matchedSamples, useColor);
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));

        String summary = messages.get("trace.summary", totalSamples, matchedSamples, target);
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.DIM) + "  " + summary
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printNode(CallNode node, String prefix, boolean isRoot,
                                  int totalSamples, int parentHits, boolean useColor) {
        double pct = totalSamples > 0 ? (double) node.hits / totalSamples * 100.0 : 0.0;
        double ms = node.hits * SAMPLE_INTERVAL_MS / 1000.0 * 1000.0; // hits * interval_ms = estimated ms

        String methodShort = shortenFrame(node.method, WIDTH - prefix.length() - 24);
        String timePart = String.format("%.1fms (%.0f%%)", ms, pct);

        String line;
        if (isRoot) {
            line = "  " + AnsiStyle.style(useColor, AnsiStyle.BOLD, AnsiStyle.CYAN)
                    + RichRenderer.padRight(methodShort, WIDTH - 26)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET)
                    + AnsiStyle.style(useColor, AnsiStyle.YELLOW)
                    + RichRenderer.padLeft(timePart, 20)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
        } else {
            line = "  " + AnsiStyle.style(useColor, AnsiStyle.DIM) + prefix
                    + AnsiStyle.style(useColor, AnsiStyle.RESET)
                    + AnsiStyle.style(useColor, AnsiStyle.CYAN)
                    + RichRenderer.padRight(methodShort, WIDTH - 2 - prefix.length() - 20)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET)
                    + AnsiStyle.style(useColor, AnsiStyle.DIM)
                    + RichRenderer.padLeft(timePart, 20)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
        }

        System.out.println(RichRenderer.boxLine(line, WIDTH));

        // Print children sorted by hits descending
        List<CallNode> children = new ArrayList<>(node.children.values());
        children.sort((a, b) -> Integer.compare(b.hits, a.hits));

        for (int i = 0; i < children.size(); i++) {
            boolean last = (i == children.size() - 1);
            String childPrefix = prefix + (last ? "\u2514\u2500\u2500 " : "\u251c\u2500\u2500 ");
            String grandchildPrefix = prefix + (last ? "    " : "\u2502   ");
            CallNode child = children.get(i);
            printNodeWithPrefix(child, childPrefix, grandchildPrefix, totalSamples, node.hits, useColor);
        }
    }

    private static void printNodeWithPrefix(CallNode node, String prefix, String childPrefix,
                                            int totalSamples, int parentHits, boolean useColor) {
        double pct = totalSamples > 0 ? (double) node.hits / totalSamples * 100.0 : 0.0;
        double ms = node.hits * SAMPLE_INTERVAL_MS / 1000.0 * 1000.0;

        int methodWidth = Math.max(10, WIDTH - 2 - prefix.length() - 20);
        String methodShort = shortenFrame(node.method, methodWidth);
        String timePart = String.format("%.1fms (%.0f%%)", ms, pct);

        String line = "  " + AnsiStyle.style(useColor, AnsiStyle.DIM) + prefix
                + AnsiStyle.style(useColor, AnsiStyle.RESET)
                + AnsiStyle.style(useColor, AnsiStyle.CYAN)
                + RichRenderer.padRight(methodShort, methodWidth)
                + AnsiStyle.style(useColor, AnsiStyle.RESET)
                + AnsiStyle.style(useColor, AnsiStyle.DIM)
                + RichRenderer.padLeft(timePart, 20)
                + AnsiStyle.style(useColor, AnsiStyle.RESET);

        System.out.println(RichRenderer.boxLine(line, WIDTH));

        List<CallNode> children = new ArrayList<>(node.children.values());
        children.sort((a, b) -> Integer.compare(b.hits, a.hits));

        for (int i = 0; i < children.size(); i++) {
            boolean last = (i == children.size() - 1);
            String nextPrefix = childPrefix + (last ? "\u2514\u2500\u2500 " : "\u251c\u2500\u2500 ");
            String nextChildPrefix = childPrefix + (last ? "    " : "\u2502   ");
            printNodeWithPrefix(children.get(i), nextPrefix, nextChildPrefix, totalSamples, node.hits, useColor);
        }
    }

    private static void printHelp(boolean useColor, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(useColor, "trace",
                messages.get("cmd.trace.desc")));
        System.out.println(RichRenderer.boxHeader(useColor, "Usage", WIDTH));
        System.out.println(RichRenderer.boxLine("argus trace <pid> <class.method> [options]", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Options:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --duration=N", 36)
                + "Duration in seconds (default: " + DEFAULT_DURATION_SEC + ")", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Example:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  argus trace 12345 com.example.OrderService.createOrder --duration=10", WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    /**
     * Shortens a fully-qualified frame string to fit within maxLen characters.
     * Trims package prefix if needed, keeping the class.method(...) part readable.
     */
    static String shortenFrame(String frame, int maxLen) {
        if (frame.length() <= maxLen) return frame;
        // Strip source file/line info: "com.Foo.bar(Foo.java:42)" -> "com.Foo.bar"
        int parenIdx = frame.indexOf('(');
        String sig = parenIdx > 0 ? frame.substring(0, parenIdx) : frame;
        if (sig.length() <= maxLen) return sig;
        // Truncate with ellipsis
        return "\u2026" + sig.substring(sig.length() - (maxLen - 1));
    }

    // -------------------------------------------------------------------------
    // Internal call tree node
    // -------------------------------------------------------------------------

    static final class CallNode {
        final String method;
        int hits;
        final Map<String, CallNode> children = new LinkedHashMap<>();

        CallNode(String method) {
            this.method = method;
        }

        CallNode getOrCreate(String frame) {
            return children.computeIfAbsent(frame, CallNode::new);
        }
    }
}
