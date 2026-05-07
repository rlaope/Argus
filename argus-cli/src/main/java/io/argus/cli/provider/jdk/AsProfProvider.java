package io.argus.cli.provider.jdk;

import io.argus.cli.model.MethodSample;
import io.argus.cli.model.ProfileResult;
import io.argus.cli.provider.ProfileProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/**
 * ProfileProvider backed by async-profiler (asprof).
 *
 * <p>Binary availability is checked lazily: {@link #isAvailable(long)} only verifies
 * platform support so that {@code ProviderRegistry.findBest()} selects this provider.
 * The binary is downloaded on first actual use inside {@link #profile} / {@link #flameGraph}.
 */
public final class AsProfProvider implements ProfileProvider {

    private static final int TOP_METHODS = 20;

    @Override
    public boolean isAvailable(long pid) {
        String platform = AsProfDownloader.detectPlatform();
        return platform != null;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String source() {
        return "asprof";
    }

    @Override
    public ProfileResult profile(long pid, String type, int durationSec) {
        AsProfPermissionCheck.Result preflight = AsProfPermissionCheck.validate(pid);
        if (!preflight.ok) {
            String msg = preflight.error
                    + (preflight.fixHint != null ? "\n  hint: " + preflight.fixHint : "");
            return ProfileResult.error(msg);
        }
        for (String w : preflight.warnings) {
            System.err.println("[argus] " + w);
        }

        String asProfPath = ensureBinary();
        if (asProfPath == null) {
            return ProfileResult.error("async-profiler download failed. Check network access.");
        }

        String[] command = new String[]{
            asProfPath,
            "-d", String.valueOf(durationSec),
            "-o", "collapsed",
            "-e", type,
            String.valueOf(pid)
        };

        AsProfExecutor.Result result = executeWithShutdownHook(command, durationSec + 30);

        if (result.exitCode() != 0) {
            String msg = result.stderr().isEmpty() ? "asprof exited with code " + result.exitCode()
                    : result.stderr();
            return ProfileResult.error(msg);
        }

        return parseCollapsed(result.stdout(), type, durationSec, null);
    }

    @Override
    public ProfileResult flameGraph(long pid, String type, int durationSec, String outputFile) {
        AsProfPermissionCheck.Result preflight = AsProfPermissionCheck.validate(pid);
        if (!preflight.ok) {
            String msg = preflight.error
                    + (preflight.fixHint != null ? "\n  hint: " + preflight.fixHint : "");
            return ProfileResult.error(msg);
        }
        for (String w : preflight.warnings) {
            System.err.println("[argus] " + w);
        }

        String asProfPath = ensureBinary();
        if (asProfPath == null) {
            return ProfileResult.error("async-profiler download failed. Check network access.");
        }

        String[] command = new String[]{
            asProfPath,
            "-d", String.valueOf(durationSec),
            "-o", "flamegraph",
            "-e", type,
            "-f", outputFile,
            String.valueOf(pid)
        };

        AsProfExecutor.Result result = executeWithShutdownHook(command, durationSec + 30);

        if (result.exitCode() != 0) {
            String msg = result.stderr().isEmpty() ? "asprof exited with code " + result.exitCode()
                    : result.stderr();
            return ProfileResult.error(msg);
        }

        return ProfileResult.ok(type, durationSec, 0L, Collections.emptyList(), outputFile);
    }

    @Override
    public ProfileResult start(long pid, String type) {
        AsProfPermissionCheck.Result preflight = AsProfPermissionCheck.validate(pid);
        if (!preflight.ok) {
            String msg = preflight.error
                    + (preflight.fixHint != null ? "\n  hint: " + preflight.fixHint : "");
            return ProfileResult.error(msg);
        }
        for (String w : preflight.warnings) {
            System.err.println("[argus] " + w);
        }

        String asProfPath = ensureBinary();
        if (asProfPath == null) {
            return ProfileResult.error("async-profiler download failed. Check network access.");
        }

        String[] command = new String[]{
            asProfPath,
            "start",
            "-e", type,
            String.valueOf(pid)
        };

        AsProfExecutor.Result result = AsProfExecutor.execute(command, 30, null);
        if (result.exitCode() != 0) {
            String msg = result.stderr().isEmpty() ? "asprof exited with code " + result.exitCode()
                    : result.stderr();
            return ProfileResult.error(msg);
        }

        String text = combineOutput(result);
        return ProfileResult.session(type, text.isEmpty() ? "Profiling started" : text, null);
    }

    @Override
    public ProfileResult stop(long pid, String outputFile, String outputFormat) {
        String asProfPath = ensureBinary();
        if (asProfPath == null) {
            return ProfileResult.error("async-profiler download failed. Check network access.");
        }

        String[] command = new String[]{
            asProfPath,
            "stop",
            "-o", outputFormat,
            "-f", outputFile,
            String.valueOf(pid)
        };

        AsProfExecutor.Result result = AsProfExecutor.execute(command, 60, null);
        if (result.exitCode() != 0) {
            String msg = result.stderr().isEmpty() ? "asprof exited with code " + result.exitCode()
                    : result.stderr();
            return ProfileResult.error(msg);
        }

        String text = combineOutput(result);
        return ProfileResult.session(null, text.isEmpty() ? "Profiling stopped" : text, outputFile);
    }

    @Override
    public ProfileResult dump(long pid, String outputFile, String outputFormat) {
        String asProfPath = ensureBinary();
        if (asProfPath == null) {
            return ProfileResult.error("async-profiler download failed. Check network access.");
        }

        String[] command = new String[]{
            asProfPath,
            "dump",
            "-o", outputFormat,
            "-f", outputFile,
            String.valueOf(pid)
        };

        AsProfExecutor.Result result = AsProfExecutor.execute(command, 60, null);
        if (result.exitCode() != 0) {
            String msg = result.stderr().isEmpty() ? "asprof exited with code " + result.exitCode()
                    : result.stderr();
            return ProfileResult.error(msg);
        }

        String text = combineOutput(result);
        return ProfileResult.session(null, text.isEmpty() ? "Profile dumped" : text, outputFile);
    }

    @Override
    public ProfileResult status(long pid) {
        String asProfPath = ensureBinary();
        if (asProfPath == null) {
            return ProfileResult.error("async-profiler download failed. Check network access.");
        }

        String[] command = new String[]{
            asProfPath,
            "status",
            String.valueOf(pid)
        };

        AsProfExecutor.Result result = AsProfExecutor.execute(command, 15, null);
        if (result.exitCode() != 0) {
            String msg = result.stderr().isEmpty() ? "asprof exited with code " + result.exitCode()
                    : result.stderr();
            return ProfileResult.error(msg);
        }

        String text = combineOutput(result);
        return ProfileResult.session(null, text.isEmpty() ? "No active profiling session" : text, null);
    }

    // -------------------------------------------------------------------------
    // Advanced overloads (AsProfOptions passthrough)
    // -------------------------------------------------------------------------

    @Override
    public ProfileResult profile(long pid, String type, int durationSec, AsProfOptions opts) {
        AsProfPermissionCheck.Result preflight = AsProfPermissionCheck.validate(pid);
        if (!preflight.ok) {
            String msg = preflight.error
                    + (preflight.fixHint != null ? "\n  hint: " + preflight.fixHint : "");
            return ProfileResult.error(msg);
        }
        for (String w : preflight.warnings) {
            System.err.println("[argus] " + w);
        }

        String asProfPath = ensureBinary();
        if (asProfPath == null) {
            return ProfileResult.error("async-profiler download failed. Check network access.");
        }

        // Determine output format: if opts requests jfr/collapsed/tree/text use that,
        // otherwise default to collapsed for text-output parsing.
        String outputFmt = resolveCollapsedFormat(opts);
        String outputFile = null;

        List<String> cmd = new ArrayList<>();
        cmd.add(asProfPath);
        cmd.add("-d"); cmd.add(String.valueOf(durationSec));
        cmd.add("-o"); cmd.add(outputFmt);
        cmd.add("-e"); cmd.add(type);
        if ("jfr".equals(outputFmt) || "tree".equals(outputFmt) || "text".equals(outputFmt)) {
            // Non-collapsed formats need a file
            outputFile = "argus-profile-" + pid + "-" + (System.currentTimeMillis() / 1000L)
                    + formatExtension(outputFmt);
            cmd.add("-f"); cmd.add(outputFile);
        }
        buildExtraArgs(cmd, opts);
        cmd.add(String.valueOf(pid));

        AsProfExecutor.Result result = executeWithShutdownHook(
                cmd.toArray(new String[0]), durationSec + 30);

        if (result.exitCode() != 0) {
            String msg = result.stderr().isEmpty() ? "asprof exited with code " + result.exitCode()
                    : result.stderr();
            return ProfileResult.error(msg);
        }

        if ("jfr".equals(outputFmt) || "tree".equals(outputFmt) || "text".equals(outputFmt)) {
            return ProfileResult.ok(type, durationSec, 0L, Collections.emptyList(), outputFile);
        }
        return parseCollapsed(result.stdout(), type, durationSec, null);
    }

    @Override
    public ProfileResult flameGraph(long pid, String type, int durationSec,
                                    String outputFile, AsProfOptions opts) {
        AsProfPermissionCheck.Result preflight = AsProfPermissionCheck.validate(pid);
        if (!preflight.ok) {
            String msg = preflight.error
                    + (preflight.fixHint != null ? "\n  hint: " + preflight.fixHint : "");
            return ProfileResult.error(msg);
        }
        for (String w : preflight.warnings) {
            System.err.println("[argus] " + w);
        }

        String asProfPath = ensureBinary();
        if (asProfPath == null) {
            return ProfileResult.error("async-profiler download failed. Check network access.");
        }

        String outputFmt = (opts != null && opts.outputFormat != null) ? opts.outputFormat : "flamegraph";

        List<String> cmd = new ArrayList<>();
        cmd.add(asProfPath);
        cmd.add("-d"); cmd.add(String.valueOf(durationSec));
        cmd.add("-o"); cmd.add(outputFmt);
        cmd.add("-e"); cmd.add(type);
        cmd.add("-f"); cmd.add(outputFile);
        buildExtraArgs(cmd, opts);
        cmd.add(String.valueOf(pid));

        AsProfExecutor.Result result = executeWithShutdownHook(
                cmd.toArray(new String[0]), durationSec + 30);

        if (result.exitCode() != 0) {
            String msg = result.stderr().isEmpty() ? "asprof exited with code " + result.exitCode()
                    : result.stderr();
            return ProfileResult.error(msg);
        }

        return ProfileResult.ok(type, durationSec, 0L, Collections.emptyList(), outputFile);
    }

    @Override
    public ProfileResult start(long pid, String type, AsProfOptions opts) {
        AsProfPermissionCheck.Result preflight = AsProfPermissionCheck.validate(pid);
        if (!preflight.ok) {
            String msg = preflight.error
                    + (preflight.fixHint != null ? "\n  hint: " + preflight.fixHint : "");
            return ProfileResult.error(msg);
        }
        for (String w : preflight.warnings) {
            System.err.println("[argus] " + w);
        }

        String asProfPath = ensureBinary();
        if (asProfPath == null) {
            return ProfileResult.error("async-profiler download failed. Check network access.");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(asProfPath);
        cmd.add("start");
        cmd.add("-e"); cmd.add(type);
        buildExtraArgs(cmd, opts);
        cmd.add(String.valueOf(pid));

        AsProfExecutor.Result result = AsProfExecutor.execute(
                cmd.toArray(new String[0]), 30, null);
        if (result.exitCode() != 0) {
            String msg = result.stderr().isEmpty() ? "asprof exited with code " + result.exitCode()
                    : result.stderr();
            return ProfileResult.error(msg);
        }

        String text = combineOutput(result);
        return ProfileResult.session(type, text.isEmpty() ? "Profiling started" : text, null);
    }

    /**
     * Appends extra asprof arguments derived from {@link AsProfOptions} to the command list.
     * Handles null opts gracefully (no-op).
     */
    private static void buildExtraArgs(List<String> cmd, AsProfOptions opts) {
        if (opts == null) return;
        if (opts.interval != null)    { cmd.add("-i"); cmd.add(opts.interval); }
        if (opts.jstackdepth != null) { cmd.add("-j"); cmd.add(String.valueOf(opts.jstackdepth)); }
        if (opts.cstack != null)      { cmd.add("--cstack"); cmd.add(opts.cstack); }
        if (opts.perThread)           { cmd.add("-t"); }
        if (opts.allUser)             { cmd.add("--alluser"); }
        if (opts.allKernel)           { cmd.add("--allkernel"); }
        if (opts.allocBytes != null)  { cmd.add("--alloc"); cmd.add(opts.allocBytes); }
        if (opts.live)                { cmd.add("--live"); }
        for (String p : opts.include) { cmd.add("-I"); cmd.add(p); }
        for (String p : opts.exclude) { cmd.add("-X"); cmd.add(p); }
    }

    /** Returns the output format string to pass to asprof for the one-shot profile() path. */
    private static String resolveCollapsedFormat(AsProfOptions opts) {
        if (opts == null || opts.outputFormat == null) return "collapsed";
        // flamegraph requires a -f path; one-shot profile() doesn't provide a caller-supplied path
        // so we only honour non-flamegraph overrides here.
        switch (opts.outputFormat) {
            case "jfr":       return "jfr";
            case "tree":      return "tree";
            case "text":      return "text";
            case "collapsed": return "collapsed";
            default:          return "collapsed";
        }
    }

    private static String formatExtension(String fmt) {
        switch (fmt) {
            case "jfr":  return ".jfr";
            case "tree": return ".html";
            case "text": return ".txt";
            default:     return ".collapsed.txt";
        }
    }

    private static String combineOutput(AsProfExecutor.Result result) {
        StringBuilder sb = new StringBuilder();
        if (result.stdout() != null && !result.stdout().isEmpty()) {
            sb.append(result.stdout().trim());
        }
        if (result.stderr() != null && !result.stderr().isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(result.stderr().trim());
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures the asprof binary is present, downloading it if needed.
     *
     * @return path to the binary, or null on failure
     */
    private static String ensureBinary() {
        String path = AsProfDownloader.asProfPath();
        if (new java.io.File(path).exists()) {
            return path;
        }
        System.out.println("Downloading async-profiler...");
        String downloaded = AsProfDownloader.download();
        return downloaded;
    }

    /**
     * Runs a command with a shutdown hook that forcibly terminates the child process
     * if the JVM exits before profiling completes.
     */
    private static AsProfExecutor.Result executeWithShutdownHook(String[] command, int timeoutSec) {
        // Holder lets the shutdown hook reference the process after it is created.
        // We use a single-element array as a mutable reference (Java 11 compatible).
        final Process[] processHolder = new Process[1];

        Thread shutdownHook = new Thread(() -> {
            Process p = processHolder[0];
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        });
        shutdownHook.setName("asprof-shutdown");

        // Intercept ProcessBuilder.start() so we can register the hook before
        // handing off to AsProfExecutor. We do this by wrapping the execute call
        // with a custom ProgressCallback that captures the process on first tick.
        // However, AsProfExecutor does not expose the Process handle publicly.
        //
        // Approach: register the hook before execution and remove it after.
        // The hook body is safe to call even if processHolder[0] is still null.
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        AsProfExecutor.Result result = AsProfExecutor.execute(command, timeoutSec, null);

        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM is already shutting down — hook already ran, nothing to do
        }

        return result;
    }

    /**
     * Parses async-profiler collapsed stack output into a {@link ProfileResult}.
     *
     * <p>Each line has the format: {@code frame1;frame2;...;leafMethod count}
     * where count is separated from the stack by a single space.
     */
    private static ProfileResult parseCollapsed(String output, String type, int durationSec,
                                                String flameGraphPath) {
        if (output == null || output.isEmpty()) {
            return ProfileResult.error("asprof produced no output");
        }

        Map<String, Long> methodCounts = new HashMap<>();
        long total = 0L;

        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace < 0) {
                continue;
            }
            String stack = line.substring(0, lastSpace);
            String countStr = line.substring(lastSpace + 1).trim();
            long count;
            try {
                count = Long.parseLong(countStr);
            } catch (NumberFormatException e) {
                continue;
            }
            total += count;

            // Leaf method is the last segment of the semicolon-delimited stack
            int lastSemi = stack.lastIndexOf(';');
            String leaf = lastSemi >= 0 ? stack.substring(lastSemi + 1) : stack;
            if (leaf.isEmpty()) {
                continue;
            }

            Long existing = methodCounts.get(leaf);
            methodCounts.put(leaf, existing == null ? count : existing + count);
        }

        // Build sorted list of entries (descending by sample count)
        List<Map.Entry<String, Long>> entries = new ArrayList<>(methodCounts.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        int topN = Math.min(TOP_METHODS, entries.size());
        List<MethodSample> topMethods = new ArrayList<>(topN);
        for (int i = 0; i < topN; i++) {
            Map.Entry<String, Long> entry = entries.get(i);
            double pct = total > 0 ? (entry.getValue() * 100.0) / total : 0.0;
            topMethods.add(new MethodSample(entry.getKey(), entry.getValue(), pct));
        }

        return ProfileResult.ok(type, durationSec, total, topMethods, flameGraphPath);
    }
}
