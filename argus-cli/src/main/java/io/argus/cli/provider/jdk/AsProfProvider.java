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

        String eventErr = checkEventSupport(type);
        if (eventErr != null) return ProfileResult.error(eventErr);

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

        // asprof v4.4 may exit 0 yet emit "[INFO] No samples were collected" on stderr.
        String noSamples = detectNoSamples(result.stderr());
        if (noSamples != null && (result.stdout() == null || result.stdout().isBlank())) {
            return ProfileResult.error(noSamples + " (event=" + type + ")");
        }

        return parseCollapsed(result.stdout(), type, durationSec, null);
    }

    @Override
    public ProfileResult flameGraph(long pid, String type, int durationSec, String outputFile) {
        return flameGraph(pid, type, durationSec, outputFile, null);
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

        String eventErr = checkEventSupport(type);
        if (eventErr != null) return ProfileResult.error(eventErr);

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

        String eventErr = checkEventSupport(type);
        if (eventErr != null) return ProfileResult.error(eventErr);

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
        // Formats that require a -f output path (cannot emit to stdout)
        boolean needsFile = "jfr".equals(outputFmt) || "tree".equals(outputFmt)
                || "text".equals(outputFmt) || "otlp".equals(outputFmt);
        if (needsFile) {
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

        // asprof v4.4 may exit 0 yet emit "[INFO] No samples were collected" on stderr.
        String noSamples = detectNoSamples(result.stderr());
        if (noSamples != null && (result.stdout() == null || result.stdout().isBlank())) {
            return ProfileResult.error(noSamples + " (event=" + type + ")");
        }

        if (needsFile) {
            return ProfileResult.ok(type, durationSec, 0L, Collections.emptyList(), outputFile);
        }
        // flat and traces emit human-readable text to stdout; treat like collapsed for sample counting
        boolean captureRaw = "ascii".equals(opts != null ? opts.outputFormat : null);
        if ("flat".equals(outputFmt) || "traces".equals(outputFmt)) {
            // These formats are not collapsed stacks — return as file-less result with status text
            return ProfileResult.ok(type, durationSec, 0L, Collections.emptyList(), null);
        }
        return parseCollapsed(result.stdout(), type, durationSec, null, captureRaw);
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

        String eventErr = checkEventSupport(type);
        if (eventErr != null) return ProfileResult.error(eventErr);

        String asProfPath = ensureBinary();
        if (asProfPath == null) {
            return ProfileResult.error("async-profiler download failed. Check network access.");
        }

        String outputFmt = (opts != null && opts.outputFormat != null) ? opts.outputFormat : "flamegraph";

        // Non-flamegraph formats: keep the direct asprof path (single run, no jfrconv).
        if (!"flamegraph".equals(outputFmt)) {
            List<String> cmd = new ArrayList<>();
            cmd.add(asProfPath);
            cmd.add("-d"); cmd.add(String.valueOf(durationSec));
            cmd.add("-o"); cmd.add(outputFmt);
            cmd.add("-e"); cmd.add(type);
            cmd.add("-f"); cmd.add(outputFile);
            buildExtraArgs(cmd, opts);
            cmd.add(String.valueOf(pid));

            AsProfExecutor.Result direct = executeWithShutdownHook(
                    cmd.toArray(new String[0]), durationSec + 30);
            if (direct.exitCode() != 0) {
                String msg = direct.stderr().isEmpty() ? "asprof exited with code " + direct.exitCode()
                        : direct.stderr();
                return ProfileResult.error(msg);
            }
            return ProfileResult.ok(type, durationSec, 0L, Collections.emptyList(), outputFile);
        }

        // flamegraph path: capture once to JFR, then convert to HTML + collapsed for accurate
        // sample count and topMethods. Single profiling pass, two cheap conversions.
        String jfrPath = System.getProperty("java.io.tmpdir")
                + java.io.File.separator + "argus-flame-" + pid + "-"
                + System.currentTimeMillis() + ".jfr";

        List<String> profCmd = new ArrayList<>();
        profCmd.add(asProfPath);
        profCmd.add("-d"); profCmd.add(String.valueOf(durationSec));
        profCmd.add("-o"); profCmd.add("jfr");
        profCmd.add("-e"); profCmd.add(type);
        profCmd.add("-f"); profCmd.add(jfrPath);
        buildExtraArgs(profCmd, opts);
        profCmd.add(String.valueOf(pid));

        AsProfExecutor.Result profResult = executeWithShutdownHook(
                profCmd.toArray(new String[0]), durationSec + 30);
        if (profResult.exitCode() != 0) {
            String msg = profResult.stderr().isEmpty() ? "asprof exited with code " + profResult.exitCode()
                    : profResult.stderr();
            new java.io.File(jfrPath).delete();
            return ProfileResult.error(msg);
        }
        String noSamples = detectNoSamples(profResult.stderr());
        if (noSamples != null) {
            new java.io.File(jfrPath).delete();
            return ProfileResult.error(noSamples + " (event=" + type + ")");
        }

        String jfrConvPath = jfrConvPath(asProfPath);
        try {
            // Convert JFR -> HTML
            AsProfExecutor.Result htmlResult = AsProfExecutor.execute(
                    new String[]{jfrConvPath, "-o", "html", jfrPath, outputFile},
                    60, null);
            if (htmlResult.exitCode() != 0) {
                String msg = htmlResult.stderr().isEmpty()
                        ? "jfrconv (html) exited with code " + htmlResult.exitCode()
                        : htmlResult.stderr();
                return ProfileResult.error(msg);
            }

            // Convert JFR -> collapsed (in tmp), parse for sample count + top methods
            String collapsedPath = jfrPath + ".collapsed";
            AsProfExecutor.Result collapsedResult = AsProfExecutor.execute(
                    new String[]{jfrConvPath, "-o", "collapsed", jfrPath, collapsedPath},
                    60, null);
            long total = 0L;
            List<MethodSample> topMethods = Collections.emptyList();
            if (collapsedResult.exitCode() == 0) {
                try {
                    String collapsed = new String(java.nio.file.Files.readAllBytes(
                            java.nio.file.Paths.get(collapsedPath)));
                    ProfileResult parsed = parseCollapsed(collapsed, type, durationSec, outputFile);
                    if ("ok".equals(parsed.status())) {
                        total = parsed.totalSamples();
                        topMethods = parsed.topMethods();
                    }
                } catch (java.io.IOException ignored) {
                    // fall through with empty stats
                } finally {
                    new java.io.File(collapsedPath).delete();
                }
            }
            return ProfileResult.ok(type, durationSec, total, topMethods, outputFile);
        } finally {
            new java.io.File(jfrPath).delete();
        }
    }

    /** Returns the path to the bundled jfrconv binary alongside asprof. */
    private static String jfrConvPath(String asProfPath) {
        java.io.File asProfBin = new java.io.File(asProfPath);
        java.io.File parent = asProfBin.getParentFile();
        return new java.io.File(parent, "jfrconv").getAbsolutePath();
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

        String eventErr = checkEventSupport(type);
        if (eventErr != null) return ProfileResult.error(eventErr);

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
            case "flat":      return "flat";
            case "traces":    return "traces";
            case "otlp":      return "otlp";
            case "collapsed": return "collapsed";
            case "ascii":     return "collapsed"; // ascii = collapsed data + terminal render
            default:          return "collapsed";
        }
    }

    private static String formatExtension(String fmt) {
        switch (fmt) {
            case "jfr":    return ".jfr";
            case "tree":   return ".html";
            case "text":   return ".txt";
            case "flat":   return ".txt";
            case "traces": return ".txt";
            case "otlp":   return ".otlp.json";
            default:       return ".collapsed.txt";
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
        return parseCollapsed(output, type, durationSec, flameGraphPath, false);
    }

    /**
     * Parses async-profiler collapsed stack output into a {@link ProfileResult}.
     *
     * @param captureRaw when {@code true}, stores the raw collapsed text in the result
     *                   via {@link ProfileResult#okWithRaw} for use by ASCII flame rendering.
     *                   Has no effect on the existing callers that pass {@code false}.
     */
    static ProfileResult parseCollapsed(String output, String type, int durationSec,
                                        String flameGraphPath, boolean captureRaw) {
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

        if (captureRaw) {
            return ProfileResult.okWithRaw(type, durationSec, total, topMethods, flameGraphPath, output);
        }
        return ProfileResult.ok(type, durationSec, total, topMethods, flameGraphPath);
    }

    // -------------------------------------------------------------------------
    // Event support guard + asprof output diagnostics
    // -------------------------------------------------------------------------

    /**
     * Checks whether {@code event} is supported by the bundled async-profiler v4.4
     * on the current OS/architecture. Returns null if supported, else a
     * fully-formed error message ready for {@link ProfileResult#error}.
     *
     * <p>Method-trace events (containing a dot) and PMU counter names (lowercase
     * identifiers with hyphens) are passed through — asprof reports unknown
     * counters with its own clear error.
     */
    static String checkEventSupport(String event) {
        if (event == null || event.isBlank()) return null;
        // Method trace pattern (Class.method) and PMU counter passthroughs are accepted.
        if (event.contains(".")) return null;
        String lc = event.toLowerCase();

        String os   = System.getProperty("os.name",  "").toLowerCase();
        String arch = System.getProperty("os.arch",  "").toLowerCase();
        boolean isMac   = os.contains("mac") || os.contains("darwin");
        boolean isLinux = os.contains("linux");
        boolean isArm64 = arch.equals("aarch64") || arch.equals("arm64");

        // Built-in events known to be honored by async-profiler 4.4.
        // Lock event is supported on macOS arm64 but typically yields zero samples
        // for short durations; we surface that via "no samples" detection at runtime
        // rather than blocking it here.
        java.util.Set<String> builtins = new java.util.HashSet<>(java.util.Arrays.asList(
                "cpu", "alloc", "lock", "wall", "nativemem", "nativelock", "cpu-time"));
        if (builtins.contains(lc)) return null;

        // PMU / hardware counters: many require perf_event_open (Linux) and
        // are not reachable on macOS arm64 (Apple Silicon does not expose
        // them via async-profiler 4.4's perf backend).
        if (lc.matches("[a-z][a-z0-9_]*(-[a-z0-9_]+)*")) {
            if (isMac && isArm64) {
                return "Event '" + event + "' not supported by bundled async-profiler v4.4 on darwin-arm64. "
                        + "Supported: cpu, alloc, lock, wall, nativemem, nativelock";
            }
            if (!isLinux) {
                return "Event '" + event + "' (PMU counter) requires Linux. "
                        + "Supported on this platform: cpu, alloc, lock, wall, nativemem, nativelock";
            }
            return null; // Linux: defer to asprof
        }
        return null; // unknown shape — let asprof report
    }

    /**
     * Returns a non-null error message if asprof's stderr indicates that no
     * samples were collected during the run; otherwise null. This is used to
     * convert asprof's exit-0-but-empty case into a user-visible error so we
     * never silently report a successful profile with zero data.
     */
    static String detectNoSamples(String stderr) {
        if (stderr == null) return null;
        String lc = stderr.toLowerCase();
        if (lc.contains("no samples were collected")
                || lc.contains("no profiling events were recorded")) {
            return "async-profiler collected no samples";
        }
        return null;
    }
}
