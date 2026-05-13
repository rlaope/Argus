package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.MethodSample;
import io.argus.cli.model.ProfileResult;
import io.argus.cli.model.ProfileSnapshot;
import io.argus.cli.provider.ProfileProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.AsProfCapabilities;
import io.argus.cli.provider.jdk.AsProfDownloader;
import io.argus.cli.provider.jdk.AsProfOptions;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.AsciiFlameRenderer;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

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
    @Override public CommandMode mode() { return CommandMode.WRITE; }

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

        // --capabilities: print event table for current host, no PID required
        for (String arg : args) {
            if ("--capabilities".equals(arg)) {
                printCapabilities(config.color(), messages);
                return;
            }
        }

        // Subcommand routing: if args[0] is one of start|stop|dump|status, treat as session subcommand.
        if (isSessionSubcommand(args[0])) {
            executeSubcommand(args, config, registry, messages);
            return;
        }

        // Continuous profiling mode: args[0] == "continuous" and args[1] is a numeric pid
        if ("continuous".equals(args[0])) {
            if (args.length < 2) {
                System.err.println(messages.get("error.pid.required"));
                return;
            }
            long contPid;
            try {
                contPid = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                System.err.println(messages.get("error.pid.invalid", args[1]));
                return;
            }
            executeContinuous(contPid, args, 2, config, registry, messages);
            return;
        }

        // Multi-PID mode: --pids=... flag present anywhere in args
        String pidsFlag = null;
        for (String arg : args) {
            if (arg.startsWith("--pids=")) {
                pidsFlag = arg.substring("--pids=".length());
                break;
            }
        }
        if (pidsFlag != null) {
            String[] pidTokens = pidsFlag.split(",");
            executeMultiPid(pidTokens, args, config, registry, messages);
            return;
        }

        // Multi-PID mode: args[0] matches <digits>(,<digits>)+ pattern
        if (args[0].matches("\\d+(,\\d+)+")) {
            String[] pidTokens = args[0].split(",");
            executeMultiPid(pidTokens, args, config, registry, messages);
            return;
        }

        // Pure diff: --diff=A:B with no pid argument (args[0] starts with --)
        if (args[0].startsWith("--diff=")) {
            String diffVal = args[0].substring("--diff=".length());
            int sep = diffVal.indexOf(':');
            if (sep < 0) {
                System.err.println(messages.get("error.profile.diff.no.pid"));
                return;
            }
            Path beforePath = Path.of(diffVal.substring(0, sep));
            Path afterPath  = Path.of(diffVal.substring(sep + 1));
            boolean json = "json".equals(config.format());
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--format=json")) json = true;
            }
            int top = 20;
            for (int i = 1; i < args.length; i++) {
                if (args[i].startsWith("--top=")) {
                    try { top = Integer.parseInt(args[i].substring(6)); } catch (NumberFormatException ignored) {}
                }
            }
            try {
                ProfileSnapshot before = ProfileSnapshot.load(beforePath);
                ProfileSnapshot after  = ProfileSnapshot.load(afterPath);
                List<ProfileSnapshot.DiffEntry> deltas = ProfileSnapshot.diff(before, after);
                if (json) {
                    printDiffJson(before, after, deltas, top);
                } else {
                    printDiff(before, after, deltas, null, top, config.color(), messages);
                }
            } catch (IOException e) {
                System.err.println(messages.get("error.profile.snapshot.load", e.getMessage()));
            }
            return;
        }

        // Reject flag-shaped args that were not caught by earlier routing blocks
        if (args[0].startsWith("--save=")) {
            System.err.println(messages.get("error.profile.save.no.pid"));
            return;
        }
        if (args[0].startsWith("--diff=")) {
            // --diff=VALUE with no ':' separator (pure diff path without a pid)
            System.err.println(messages.get("error.profile.diff.no.pid"));
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
        Path saveTo   = null;
        Path diffWith = null;
        String outputFormat = null;

        // Advanced asprof options
        AsProfOptions.Builder optsBuilder = AsProfOptions.builder();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--type=") || arg.startsWith("--event=")) {
                String raw = arg.substring(arg.indexOf('=') + 1);
                // Preserve case for method-trace events (e.g. com.example.MyClass.myMethod)
                // but normalise built-in aliases and PMU counter names to lowercase
                type = raw.contains(".") ? raw : raw.toLowerCase();
            } else if ((arg.equals("--type") || arg.equals("--event")) && i + 1 < args.length) {
                String raw = args[++i];
                type = raw.contains(".") ? raw : raw.toLowerCase();
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
            } else if (arg.startsWith("--save=")) {
                saveTo = Path.of(arg.substring("--save=".length()));
            } else if (arg.startsWith("--diff=")) {
                String diffVal = arg.substring("--diff=".length());
                diffWith = Path.of(diffVal);
            } else if (arg.startsWith("--output-format=")) {
                outputFormat = arg.substring(16).toLowerCase();
            } else if (arg.startsWith("--interval=")) {
                String v = arg.substring(11);
                String err = validateInterval(v, messages);
                if (err != null) { System.err.println(err); return; }
                optsBuilder.interval(v);
            } else if (arg.startsWith("--jstackdepth=")) {
                int depth = parseClampedInt(arg.substring(14), 1, 2048, 64);
                optsBuilder.jstackdepth(depth);
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
            } else if (arg.equals("--reverse")) {
                optsBuilder.reverse(true);
            } else if (arg.startsWith("--minwidth=")) {
                optsBuilder.minwidth(arg.substring("--minwidth=".length()));
            } else if (arg.equals("--sched")) {
                optsBuilder.sched(true);
            } else if (arg.startsWith("--clock=")) {
                String v = arg.substring("--clock=".length());
                String err = validateClock(v, messages);
                if (err != null) { System.err.println(err); return; }
                optsBuilder.clock(v);
            } else if (arg.startsWith("--signal=")) {
                optsBuilder.signal(arg.substring("--signal=".length()));
            } else if (arg.startsWith("--proc=")) {
                String v = arg.substring("--proc=".length());
                String err = validateInterval(v, messages);
                if (err != null) { System.err.println(err); return; }
                optsBuilder.procInterval(v);
            } else if (arg.equals("--nofree")) {
                optsBuilder.nofree(true);
            } else if (arg.equals("--ttsp")) {
                optsBuilder.ttsp(true);
            } else if (arg.startsWith("--begin=")) {
                String v = arg.substring("--begin=".length());
                if (v.isEmpty()) { System.err.println(messages.get("error.profile.adv.begin.empty")); return; }
                optsBuilder.beginFunction(v);
            } else if (arg.startsWith("--end=")) {
                String v = arg.substring("--end=".length());
                if (v.isEmpty()) { System.err.println(messages.get("error.profile.adv.end.empty")); return; }
                optsBuilder.endFunction(v);
            }
        }

        // Mutual exclusion check
        AsProfOptions opts = optsBuilder.outputFormat(outputFormat).build();
        // --sched is Linux-only per asprof. Reject early on non-Linux.
        if (opts.sched && !System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            System.err.println(messages.get("error.profile.adv.sched.linux.only"));
            return;
        }
        // --nofree only affects --type=nativemem. Warn for other events.
        if (opts.nofree && !"nativemem".equals(type)) {
            System.err.println(messages.get("warn.profile.adv.nofree.event.mismatch", type));
        }
        if (opts.allUser && opts.allKernel) {
            System.err.println(messages.get("error.profile.adv.alluser.allkernel.exclusive"));
            return;
        }

        // Validate type
        if (!isValidType(type)) {
            System.err.println(messages.get("error.profile.invalid.type", type));
            return;
        }

        ProfileProvider provider = Providers.require(registry.findProfileProvider(pid, sourceOverride), pid, messages);

        boolean useColor = config.color();

        if ("ascii".equals(outputFormat)) {
            // ASCII flame graph: run collapsed profile then render to stdout — no file written
            System.out.println(messages.get("status.profiling", pid, durationSec, type));
            ProfileResult result = provider.profile(pid, type, durationSec, opts);
            if (result == null) return;
            if ("error".equals(result.status())) {
                System.err.println(messages.get("error.profile.asprof.failed",
                        result.errorMessage() != null ? result.errorMessage() : "unknown error"));
                return;
            }
            System.out.print(AsciiFlameRenderer.render(result, top, useColor));
            return;
        }

        if (flame || outputFormat != null) {
            // Determine the actual output file and format
            String resolvedFmt = outputFormat != null ? outputFormat : "flamegraph";
            String defaultExt = formatToExtension(resolvedFmt);
            String outputFile;
            if (file != null) {
                outputFile = file;
            } else if (outputFormat != null && !"flamegraph".equals(outputFormat)) {
                outputFile = "argus-profile-" + pid + "-" + (System.currentTimeMillis() / 1000L) + defaultExt;
            } else {
                outputFile = "flamegraph-" + pid + "-" + type + ".html";
            }
            System.out.println(messages.get("status.profiling", pid, durationSec, type));
            ProfileResult result = provider.flameGraph(pid, type, durationSec, outputFile, opts);
            if ("error".equals(result.status())) {
                System.err.println(messages.get("error.profile.asprof.failed",
                        result.errorMessage() != null ? result.errorMessage() : "unknown error"));
            } else {
                System.out.println(messages.get("status.flame.generated", outputFile));
                if ("flamegraph".equals(resolvedFmt)) {
                    openBrowser(outputFile);
                }
            }
            return;
        }

        System.out.println(messages.get("status.profiling", pid, durationSec, type));
        ProfileResult result = provider.profile(pid, type, durationSec, opts);
        if (result == null) return;
        if ("error".equals(result.status())) {
            System.err.println(messages.get("error.profile.asprof.failed",
                    result.errorMessage() != null ? result.errorMessage() : "unknown error"));
            return;
        }

        // --save: persist snapshot
        if (saveTo != null) {
            try {
                ProfileSnapshot.save(saveTo, pid, result);
                System.out.println(messages.get("status.profile.saved", saveTo.toAbsolutePath()));
            } catch (IOException e) {
                System.err.println(messages.get("error.profile.snapshot.save", e.getMessage()));
            }
        }

        // --diff: compare live result against saved snapshot
        if (diffWith != null) {
            try {
                ProfileSnapshot before = ProfileSnapshot.load(diffWith);
                // Warn on type mismatch
                if (before.type() != null && result.type() != null
                        && !before.type().equalsIgnoreCase(result.type())) {
                    System.err.println(messages.get("warn.profile.diff.type.mismatch",
                            before.type(), result.type()));
                }
                // Build an ephemeral "after" snapshot from the live result for diff
                ProfileSnapshot afterSnap = new ProfileSnapshot(
                        "live", "now", System.currentTimeMillis() / 1000L,
                        pid, result.type(), result.durationSec(), result.totalSamples(),
                        result.topMethods());
                List<ProfileSnapshot.DiffEntry> deltas = ProfileSnapshot.diff(before, afterSnap);
                if (json) {
                    printDiffJson(before, afterSnap, deltas, top);
                } else {
                    printDiff(before, afterSnap, deltas, result, top, useColor, messages);
                }
            } catch (IOException e) {
                System.err.println(messages.get("error.profile.snapshot.load", e.getMessage()));
            }
            return;
        }

        if (json) {
            printJson(result, top);
        } else {
            System.out.print(RichRenderer.brandedHeader(useColor, "profile",
                    messages.get("desc.profile")));
            printResult(result, pid, top, useColor, messages);
        }
    }

    // -------------------------------------------------------------------------
    // Continuous profiling mode
    // -------------------------------------------------------------------------

    private static void executeContinuous(long pid, String[] args, int optStart,
                                          CliConfig config, ProviderRegistry registry,
                                          Messages messages) {
        int intervalSec = 30;
        int windowMin   = 5;
        String outputDir    = null;
        String diffAgainst  = null;
        String type         = "cpu";
        String sourceOverride = null;

        for (int i = optStart; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--interval=")) {
                try { intervalSec = Integer.parseInt(a.substring(11)); } catch (NumberFormatException ignored) {}
            } else if (a.startsWith("--window=")) {
                try { windowMin = Integer.parseInt(a.substring(9)); } catch (NumberFormatException ignored) {}
            } else if (a.startsWith("--output-dir=")) {
                outputDir = a.substring(13);
            } else if (a.startsWith("--diff-against=")) {
                diffAgainst = a.substring(15);
            } else if (a.startsWith("--type=") || a.startsWith("--event=")) {
                String raw = a.substring(a.indexOf('=') + 1);
                type = raw.contains(".") ? raw : raw.toLowerCase();
            } else if (a.startsWith("--source=")) {
                sourceOverride = a.substring(9);
            }
        }

        ProfileProvider provider = Providers.require(registry.findProfileProvider(pid, sourceOverride), pid, messages);

        // Create output dir if needed
        final String snapDir = outputDir;
        if (snapDir != null) {
            new File(snapDir).mkdirs();
        }

        // Load fixed baseline if --diff-against provided
        ProfileSnapshot baseline = null;
        if (diffAgainst != null) {
            try {
                baseline = ProfileSnapshot.load(Path.of(diffAgainst));
            } catch (IOException e) {
                System.err.println(messages.get("error.profile.continuous.baseline.load", e.getMessage()));
                return;
            }
        }

        // Start profiling session
        ProfileResult startResult = provider.start(pid, type, AsProfOptions.builder().build());
        if ("error".equals(startResult.status())) {
            System.err.println(messages.get("error.profile.asprof.failed",
                    startResult.errorMessage() != null ? startResult.errorMessage() : "unknown error"));
            return;
        }
        System.out.println(messages.get("status.profile.continuous.started", pid, type, intervalSec));

        final AtomicBoolean running = new AtomicBoolean(true);
        final ProfileProvider providerRef = provider;
        final long pidRef = pid;

        // Ctrl-C shutdown hook: stop profiling cleanly
        Thread shutdownHook = new Thread(() -> {
            running.set(false);
            try {
                providerRef.stop(pidRef, "/dev/null", "collapsed");
            } catch (Exception ignored) {}
            System.out.println(messages.get("status.profile.continuous.stopped"));
        });
        shutdownHook.setName("argus-continuous-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        final DateTimeFormatter hhmmss = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

        ProfileSnapshot[] prevHolder = new ProfileSnapshot[1];
        if (baseline != null) {
            prevHolder[0] = baseline;
        }

        final int finalIntervalSec = intervalSec;
        final int finalWindowMin   = windowMin;
        final boolean useBaseline  = (baseline != null);

        try {
            while (running.get()) {
                Thread.sleep(finalIntervalSec * 1000L);
                if (!running.get()) break;

                // Dump current state to a snapshot file
                String snapPath = (snapDir != null)
                        ? snapDir + "/snap-" + Instant.now().getEpochSecond() + ".collapsed.txt"
                        : null;

                // Use a temp file if no output-dir
                String dumpTarget = snapPath != null ? snapPath
                        : System.getProperty("java.io.tmpdir") + "/argus-snap-" + pid
                          + "-" + Instant.now().getEpochSecond() + ".collapsed.txt";

                ProfileResult dumpResult = provider.dump(pidRef, dumpTarget, "collapsed");
                if ("error".equals(dumpResult.status())) {
                    System.err.println(messages.get("error.profile.continuous.dump.failed",
                            dumpResult.errorMessage() != null ? dumpResult.errorMessage() : "?"));
                    continue;
                }

                // Parse collapsed dump into snapshot
                ProfileSnapshot curr;
                try {
                    curr = loadCollapsedAsSnapshot(Path.of(dumpTarget), pidRef, type, finalIntervalSec);
                } catch (IOException e) {
                    System.err.println(messages.get("error.profile.continuous.parse.failed", e.getMessage()));
                    continue;
                }

                // Print one-line status
                String ts = hhmmss.format(Instant.now());
                String topMethod = curr.methods().isEmpty() ? "-"
                        : curr.methods().get(0).method();
                double topPct = curr.methods().isEmpty() ? 0.0
                        : curr.methods().get(0).percentage();

                if (prevHolder[0] != null) {
                    List<ProfileSnapshot.DiffEntry> deltas = ProfileSnapshot.diff(prevHolder[0], curr);
                    double deltaPp = 0.0;
                    String sign = "+";
                    if (!deltas.isEmpty()) {
                        ProfileSnapshot.DiffEntry topDelta = deltas.get(0);
                        if (topDelta.method().equals(topMethod)) {
                            deltaPp = topDelta.deltaPct();
                            sign = deltaPp >= 0 ? "+" : "";
                        }
                    }
                    System.out.printf(messages.get("status.profile.continuous.line"),
                            ts,
                            formatWithCommas(curr.totalSamples()),
                            topMethod,
                            topPct,
                            sign,
                            deltaPp);
                    System.out.println();
                } else {
                    System.out.printf(messages.get("status.profile.continuous.line.first"),
                            ts,
                            formatWithCommas(curr.totalSamples()),
                            topMethod,
                            topPct);
                    System.out.println();
                }

                // Update prev (not the baseline in fixed-baseline mode)
                if (!useBaseline) {
                    prevHolder[0] = curr;
                }

                // Prune old snapshots from output-dir
                if (snapDir != null) {
                    pruneOldSnapshots(snapDir, finalWindowMin);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {}
            // Stop the profiling session
            provider.stop(pidRef, "/dev/null", "collapsed");
            System.out.println(messages.get("status.profile.continuous.stopped"));
        }
    }

    /**
     * Loads a collapsed-format dump file written by asprof dump into a ProfileSnapshot.
     */
    private static ProfileSnapshot loadCollapsedAsSnapshot(Path file, long pid, String type,
                                                            int durationSec) throws IOException {
        String content = Files.readString(file);
        // Re-use AsProfProvider's parseCollapsed logic by parsing inline
        long total = 0L;
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            if (line.isEmpty()) continue;
            int sp = line.lastIndexOf(' ');
            if (sp < 0) continue;
            String countStr = line.substring(sp + 1).trim();
            long cnt;
            try { cnt = Long.parseLong(countStr); } catch (NumberFormatException e) { continue; }
            total += cnt;
            String stack = line.substring(0, sp);
            int semi = stack.lastIndexOf(';');
            String leaf = semi >= 0 ? stack.substring(semi + 1) : stack;
            if (leaf.isEmpty()) continue;
            counts.merge(leaf, cnt, Long::sum);
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<MethodSample> methods = new ArrayList<>();
        for (Map.Entry<String, Long> e : entries) {
            double pct = total > 0 ? (e.getValue() * 100.0) / total : 0.0;
            methods.add(new MethodSample(e.getKey(), e.getValue(), pct));
        }
        Instant now = Instant.now();
        String ver = ProfileSnapshot.class.getPackage() != null
                && ProfileSnapshot.class.getPackage().getImplementationVersion() != null
                ? ProfileSnapshot.class.getPackage().getImplementationVersion() : "dev";
        DateTimeFormatter iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC);
        return new ProfileSnapshot(ver, iso.format(now), now.getEpochSecond(),
                pid, type, durationSec, total, methods);
    }

    /**
     * Deletes snapshot files from outputDir older than windowMinutes.
     * Only deletes files matching snap-*.collapsed.txt pattern.
     */
    private static void pruneOldSnapshots(String outputDir, int windowMinutes) {
        long cutoff = Instant.now().getEpochSecond() - (windowMinutes * 60L);
        File dir = new File(outputDir);
        File[] files = dir.listFiles((d, n) -> n.startsWith("snap-") && n.endsWith(".collapsed.txt"));
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            // extract epoch from snap-<epoch>.collapsed.txt
            try {
                String epochStr = name.substring(5, name.indexOf(".collapsed.txt"));
                long epoch = Long.parseLong(epochStr);
                if (epoch < cutoff) {
                    f.delete();
                }
            } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Multi-PID parallel profiling
    // -------------------------------------------------------------------------

    /** Result container for one pid in a multi-pid run. */
    private static final class PidOutcome {
        final long pid;
        final ProfileResult result;
        final String outputFile;
        PidOutcome(long pid, ProfileResult result, String outputFile) {
            this.pid = pid;
            this.result = result;
            this.outputFile = outputFile;
        }
    }

    private static void executeMultiPid(String[] pidTokens, String[] args,
                                        CliConfig config, ProviderRegistry registry,
                                        Messages messages) {
        // Parse shared options
        int durationSec = 30;
        String type = "cpu";
        String outputPrefix = null;
        String sourceOverride = null;
        AsProfOptions.Builder optsBuilder = AsProfOptions.builder();

        for (String arg : args) {
            if (arg.startsWith("--duration=")) {
                try { durationSec = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--type=") || arg.startsWith("--event=")) {
                String raw = arg.substring(arg.indexOf('=') + 1);
                type = raw.contains(".") ? raw : raw.toLowerCase();
            } else if (arg.startsWith("--output-prefix=")) {
                outputPrefix = arg.substring(16);
            } else if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            }
        }

        final String finalType = type;
        final int finalDuration = durationSec;
        final String finalPrefix = outputPrefix;
        final String finalSource = sourceOverride;
        final AsProfOptions opts = optsBuilder.build();

        boolean useColor = config.color();

        // Validate and deduplicate pids
        List<Long> pids = new ArrayList<>();
        for (String tok : pidTokens) {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            try {
                long p = Long.parseLong(tok);
                if (!pids.contains(p)) pids.add(p);
            } catch (NumberFormatException e) {
                System.err.println(messages.get("error.pid.invalid", tok));
            }
        }
        if (pids.isEmpty()) {
            System.err.println(messages.get("error.pid.required"));
            return;
        }

        int poolSize = Math.min(pids.size(), Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);

        List<Future<PidOutcome>> futures = new ArrayList<>();
        for (long p : pids) {
            final long fp = p;
            futures.add(pool.submit(new Callable<PidOutcome>() {
                @Override
                public PidOutcome call() {
                    ProfileProvider prov = registry.findProfileProvider(fp, finalSource);
                    if (prov == null) {
                        return new PidOutcome(fp,
                                ProfileResult.error(messages.get("error.provider.none", fp)), null);
                    }
                    long epoch = System.currentTimeMillis() / 1000L;
                    String outFile = finalPrefix != null
                            ? finalPrefix + "-" + fp + ".html"
                            : "argus-profile-" + fp + "-" + epoch + ".html";
                    ProfileResult r = prov.profile(fp, finalType, finalDuration, opts);
                    return new PidOutcome(fp, r, outFile);
                }
            }));
        }

        pool.shutdown();

        List<PidOutcome> outcomes = new ArrayList<>();
        int timeoutSec = finalDuration + 30;
        for (int i = 0; i < futures.size(); i++) {
            Future<PidOutcome> f = futures.get(i);
            long pid = pids.get(i);
            try {
                PidOutcome out = f.get(timeoutSec, TimeUnit.SECONDS);
                outcomes.add(out);
            } catch (TimeoutException e) {
                outcomes.add(new PidOutcome(pid,
                        ProfileResult.error(messages.get("error.profile.multipid.timeout", pid)), null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcomes.add(new PidOutcome(pid,
                        ProfileResult.error("interrupted"), null));
            } catch (ExecutionException e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                outcomes.add(new PidOutcome(pid,
                        ProfileResult.error(msg != null ? msg : "unknown error"), null));
            }
        }

        printMultiPidTable(outcomes, useColor, messages);
    }

    private static void printMultiPidTable(List<PidOutcome> outcomes, boolean useColor,
                                           Messages messages) {
        String bold  = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String dim   = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String red   = AnsiStyle.style(useColor, AnsiStyle.RED);
        String green = AnsiStyle.style(useColor, AnsiStyle.GREEN);

        int n = outcomes.size();
        String title = messages.get("header.profile.multipid", n);

        // Box width matching the rest of the profile output
        int w = WIDTH;
        System.out.println(RichRenderer.boxHeader(useColor, title, w));
        System.out.println(RichRenderer.emptyLine(w));

        // Column header
        String hdr = bold
                + "  " + RichRenderer.padRight("pid", 8)
                + RichRenderer.padLeft("samples", 10)
                + "   " + RichRenderer.padRight("top method", 42)
                + "   " + RichRenderer.padRight("status", 14)
                + reset;
        System.out.println(RichRenderer.boxLine(hdr, w));

        String sep = dim + "  " + "─".repeat(6)
                + "  " + "─".repeat(9)
                + "   " + "─".repeat(42)
                + "   " + "─".repeat(14) + reset;
        System.out.println(RichRenderer.boxLine(sep, w));

        for (PidOutcome o : outcomes) {
            boolean ok = !"error".equals(o.result.status());
            String pidStr    = String.valueOf(o.pid);
            String samplesStr, topStr, statusStr, statusColor;
            if (ok) {
                samplesStr  = formatWithCommas(o.result.totalSamples());
                List<MethodSample> ms = o.result.topMethods();
                topStr = ms.isEmpty() ? "-"
                        : RichRenderer.truncate(ms.get(0).method()
                            + " (" + String.format("%.0f", ms.get(0).percentage()) + "%)", 42);
                statusStr   = "ok";
                statusColor = green;
            } else {
                samplesStr  = "-";
                topStr      = "-";
                String err  = o.result.errorMessage();
                statusStr   = err != null ? RichRenderer.truncate(err, 14) : messages.get("error.profile.multipid.failed");
                statusColor = red;
            }

            String row = "  " + RichRenderer.padRight(pidStr, 8)
                    + RichRenderer.padLeft(samplesStr, 10)
                    + "   " + RichRenderer.padRight(topStr, 42)
                    + "   " + statusColor + RichRenderer.padRight(statusStr, 14) + reset;
            System.out.println(RichRenderer.boxLine(row, w));
        }

        System.out.println(RichRenderer.emptyLine(w));
        System.out.println(RichRenderer.boxFooter(useColor, null, w));
    }

    // -------------------------------------------------------------------------
    // Session subcommands (start/stop/dump/status)
    // -------------------------------------------------------------------------

    private static boolean isSessionSubcommand(String s) {
        return "start".equals(s) || "stop".equals(s) || "dump".equals(s) || "status".equals(s);
    }

    private static void executeSubcommand(String[] args, CliConfig config,
                                          ProviderRegistry registry, Messages messages) {
        String subcommand = args[0];
        if (args.length < 2) {
            System.err.println(messages.get("error.pid.required"));
            return;
        }

        long pid;
        try {
            pid = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            System.err.println(messages.get("error.pid.invalid", args[1]));
            return;
        }

        String type = "cpu";
        String output = null;
        String outputFormat = null;
        boolean flame = false;
        String sourceOverride = null;
        AsProfOptions.Builder subOptsBuilder = AsProfOptions.builder();

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--type=") || arg.startsWith("--event=")) {
                String raw = arg.substring(arg.indexOf('=') + 1);
                type = raw.contains(".") ? raw : raw.toLowerCase();
            } else if (arg.startsWith("--output=")) {
                output = arg.substring(9);
            } else if (arg.startsWith("--output-format=")) {
                outputFormat = arg.substring(16).toLowerCase();
            } else if (arg.equals("--flame")) {
                flame = true;
            } else if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.startsWith("--interval=")) {
                String v = arg.substring(11);
                String err = validateInterval(v, messages);
                if (err != null) { System.err.println(err); return; }
                subOptsBuilder.interval(v);
            } else if (arg.startsWith("--jstackdepth=")) {
                subOptsBuilder.jstackdepth(parseClampedInt(arg.substring(14), 1, 2048, 64));
            } else if (arg.startsWith("--cstack=")) {
                String v = arg.substring(9);
                String err = validateCstack(v, messages);
                if (err != null) { System.err.println(err); return; }
                subOptsBuilder.cstack(v);
            } else if (arg.equals("--threads")) {
                subOptsBuilder.perThread(true);
            } else if (arg.equals("--alluser")) {
                subOptsBuilder.allUser(true);
            } else if (arg.equals("--allkernel")) {
                subOptsBuilder.allKernel(true);
            } else if (arg.startsWith("--alloc=")) {
                subOptsBuilder.allocBytes(arg.substring(8));
            } else if (arg.equals("--live")) {
                subOptsBuilder.live(true);
            } else if (arg.startsWith("--include=")) {
                subOptsBuilder.addInclude(arg.substring(10));
            } else if (arg.startsWith("--exclude=")) {
                subOptsBuilder.addExclude(arg.substring(10));
            } else if (arg.equals("--reverse")) {
                subOptsBuilder.reverse(true);
            } else if (arg.startsWith("--minwidth=")) {
                subOptsBuilder.minwidth(arg.substring("--minwidth=".length()));
            } else if (arg.equals("--sched")) {
                subOptsBuilder.sched(true);
            } else if (arg.startsWith("--clock=")) {
                String v = arg.substring("--clock=".length());
                String err = validateClock(v, messages);
                if (err != null) { System.err.println(err); return; }
                subOptsBuilder.clock(v);
            } else if (arg.startsWith("--signal=")) {
                subOptsBuilder.signal(arg.substring("--signal=".length()));
            } else if (arg.startsWith("--proc=")) {
                String v = arg.substring("--proc=".length());
                String err = validateInterval(v, messages);
                if (err != null) { System.err.println(err); return; }
                subOptsBuilder.procInterval(v);
            } else if (arg.equals("--nofree")) {
                subOptsBuilder.nofree(true);
            } else if (arg.equals("--ttsp")) {
                subOptsBuilder.ttsp(true);
            } else if (arg.startsWith("--begin=")) {
                String v = arg.substring("--begin=".length());
                if (v.isEmpty()) { System.err.println(messages.get("error.profile.adv.begin.empty")); return; }
                subOptsBuilder.beginFunction(v);
            } else if (arg.startsWith("--end=")) {
                String v = arg.substring("--end=".length());
                if (v.isEmpty()) { System.err.println(messages.get("error.profile.adv.end.empty")); return; }
                subOptsBuilder.endFunction(v);
            }
        }

        AsProfOptions subOpts = subOptsBuilder.build();
        // --sched is Linux-only per asprof. Reject early on non-Linux.
        if (subOpts.sched && !System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            System.err.println(messages.get("error.profile.adv.sched.linux.only"));
            return;
        }
        // --nofree only affects --type=nativemem. Warn for other events.
        if (subOpts.nofree && !"nativemem".equals(type)) {
            System.err.println(messages.get("warn.profile.adv.nofree.event.mismatch", type));
        }
        if (subOpts.allUser && subOpts.allKernel) {
            System.err.println(messages.get("error.profile.adv.alluser.allkernel.exclusive"));
            return;
        }

        if ("start".equals(subcommand) && !isValidType(type)) {
            System.err.println(messages.get("error.profile.invalid.type", type));
            return;
        }

        ProfileProvider provider = Providers.require(registry.findProfileProvider(pid, sourceOverride), pid, messages);

        ProfileResult result;
        switch (subcommand) {
            case "start":
                result = provider.start(pid, type, subOpts);
                if ("error".equals(result.status())) {
                    System.err.println(messages.get("error.profile.asprof.failed",
                            result.errorMessage() != null ? result.errorMessage() : "unknown error"));
                } else {
                    System.out.println(messages.get("status.profile.started", pid, type));
                    if (result.statusText() != null && !result.statusText().isBlank()) {
                        System.out.println(result.statusText());
                    }
                }
                return;
            case "stop": {
                String outFile = output != null ? output : defaultOutputFile(pid);
                String outFmt = outputFormat != null ? outputFormat : detectFormat(outFile);
                result = provider.stop(pid, outFile, outFmt);
                if ("error".equals(result.status())) {
                    System.err.println(messages.get("error.profile.asprof.failed",
                            result.errorMessage() != null ? result.errorMessage() : "unknown error"));
                } else {
                    System.out.println(messages.get("status.profile.stopped", pid, outFile));
                    if (result.statusText() != null && !result.statusText().isBlank()) {
                        System.out.println(result.statusText());
                    }
                    if (flame || "flamegraph".equals(outFmt)) {
                        openBrowser(outFile);
                    }
                }
                return;
            }
            case "dump": {
                String outFile = output != null ? output : defaultOutputFile(pid);
                String outFmt = outputFormat != null ? outputFormat : detectFormat(outFile);
                result = provider.dump(pid, outFile, outFmt);
                if ("error".equals(result.status())) {
                    System.err.println(messages.get("error.profile.asprof.failed",
                            result.errorMessage() != null ? result.errorMessage() : "unknown error"));
                } else {
                    System.out.println(messages.get("status.profile.dump.written", pid, outFile));
                    if (result.statusText() != null && !result.statusText().isBlank()) {
                        System.out.println(result.statusText());
                    }
                }
                return;
            }
            case "status":
                result = provider.status(pid);
                if ("error".equals(result.status())) {
                    System.err.println(messages.get("error.profile.asprof.failed",
                            result.errorMessage() != null ? result.errorMessage() : "unknown error"));
                } else {
                    System.out.println(messages.get("status.profile.status", pid));
                    if (result.statusText() != null && !result.statusText().isBlank()) {
                        System.out.println(result.statusText());
                    }
                }
                return;
            default:
                printHelp(config.color(), messages);
        }
    }

    private static String defaultOutputFile(long pid) {
        return "argus-profile-" + pid + "-" + (System.currentTimeMillis() / 1000L) + ".html";
    }

    private static String detectFormat(String path) {
        if (path == null) return "flamegraph";
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) return "flamegraph";
        if (lower.endsWith(".jfr")) return "jfr";
        if (lower.endsWith(".collapsed.txt") || lower.endsWith(".collapsed")) return "collapsed";
        if (lower.endsWith(".otlp.json")) return "otlp";
        if (lower.endsWith(".txt")) return "flat";
        return "flamegraph";
    }

    // -------------------------------------------------------------------------
    // Diff rendering
    // -------------------------------------------------------------------------

    /**
     * Renders the profile diff box to stdout.
     *
     * @param before    the saved baseline snapshot
     * @param after     the "after" snapshot (may be ephemeral for live run)
     * @param deltas    pre-sorted diff entries
     * @param liveResult the live ProfileResult if this is a live-vs-saved diff (null for file-vs-file)
     * @param top       max rows to show
     */
    private static void printDiff(ProfileSnapshot before, ProfileSnapshot after,
                                  List<ProfileSnapshot.DiffEntry> deltas,
                                  ProfileResult liveResult,
                                  int top, boolean useColor, Messages messages) {
        String bold   = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String dim    = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String reset  = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String red    = AnsiStyle.style(useColor, AnsiStyle.RED);
        String green  = AnsiStyle.style(useColor, AnsiStyle.GREEN);
        String cyan   = AnsiStyle.style(useColor, AnsiStyle.CYAN);

        // Header box
        String beforeDesc = before.pid() >= 0
                ? before.pid() + " (" + before.type() + ", " + before.durationSec() + "s, "
                  + formatWithCommas(before.totalSamples()) + " samples, captured " + before.capturedAt() + ")"
                : before.type() + ", " + before.durationSec() + "s, "
                  + formatWithCommas(before.totalSamples()) + " samples";
        String afterDesc = liveResult != null
                ? "pid " + after.pid() + " (" + after.type() + ", " + after.durationSec() + "s, "
                  + formatWithCommas(after.totalSamples()) + " samples)"
                : after.pid() + " (" + after.type() + ", " + after.durationSec() + "s, "
                  + formatWithCommas(after.totalSamples()) + " samples, captured " + after.capturedAt() + ")";

        System.out.println(RichRenderer.boxHeader(useColor, "Profile Diff", WIDTH,
                "before", "after"));
        System.out.println(RichRenderer.boxLine("  before: " + beforeDesc, WIDTH));
        System.out.println(RichRenderer.boxLine("  after:  " + afterDesc,  WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Column header
        String colHeader = bold
                + "  " + RichRenderer.padRight("#", 4)
                + RichRenderer.padRight(messages.get("label.method"), 42)
                + RichRenderer.padLeft("before", 9)
                + "   " + RichRenderer.padLeft("after", 9)
                + "   " + RichRenderer.padLeft("Δ samples", 10)
                + "   " + RichRenderer.padLeft("%", 7)
                + reset;
        System.out.println(RichRenderer.boxLine(colHeader, WIDTH));

        String sep = dim + "  " + "─".repeat(4) + "  " + "─".repeat(40)
                + "  " + "─".repeat(9) + "   " + "─".repeat(9)
                + "   " + "─".repeat(10) + "   " + "─".repeat(8) + reset;
        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        int limit = Math.min(top, deltas.size());
        for (int i = 0; i < limit; i++) {
            ProfileSnapshot.DiffEntry de = deltas.get(i);
            String suffix;
            switch (de.state()) {
                case "new":  suffix = " (NEW)";  break;
                case "gone": suffix = " (GONE)"; break;
                default:     suffix = "";         break;
            }
            String methodDisplay = RichRenderer.truncate(de.method() + suffix, 42);
            String deltaColor = de.deltaSamples() > 0 ? red : (de.deltaSamples() < 0 ? green : "");
            String sign = de.deltaSamples() >= 0 ? "+" : "";
            String line = "  "
                    + RichRenderer.padRight(String.valueOf(i + 1), 4)
                    + cyan + RichRenderer.padRight(methodDisplay, 42) + reset
                    + RichRenderer.padLeft(formatWithCommas(de.beforeSamples()), 9)
                    + "   " + RichRenderer.padLeft(formatWithCommas(de.afterSamples()), 9)
                    + "   " + deltaColor + RichRenderer.padLeft(sign + formatWithCommas(de.deltaSamples()), 10) + reset
                    + "   " + deltaColor + RichRenderer.padLeft(String.format("%s%.1f%%", sign, de.deltaPct()), 7) + reset;
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor,
                deltas.size() + " method(s) changed, showing top " + limit, WIDTH));
    }

    private static void printDiffJson(ProfileSnapshot before, ProfileSnapshot after,
                                      List<ProfileSnapshot.DiffEntry> deltas, int top) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"ok\"");
        sb.append(",\"before\":{")
          .append("\"pid\":").append(before.pid())
          .append(",\"type\":\"").append(ProfileSnapshot.escape(before.type())).append('"')
          .append(",\"durationSec\":").append(before.durationSec())
          .append(",\"totalSamples\":").append(before.totalSamples())
          .append(",\"capturedAt\":\"").append(before.capturedAt()).append('"')
          .append('}');
        sb.append(",\"after\":{")
          .append("\"pid\":").append(after.pid())
          .append(",\"type\":\"").append(ProfileSnapshot.escape(after.type())).append('"')
          .append(",\"durationSec\":").append(after.durationSec())
          .append(",\"totalSamples\":").append(after.totalSamples())
          .append(",\"capturedAt\":\"").append(after.capturedAt()).append('"')
          .append('}');
        sb.append(",\"deltas\":[");
        int limit = Math.min(top, deltas.size());
        for (int i = 0; i < limit; i++) {
            ProfileSnapshot.DiffEntry de = deltas.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"method\":\"").append(ProfileSnapshot.escape(de.method())).append('"')
              .append(",\"before\":").append(de.beforeSamples())
              .append(",\"after\":").append(de.afterSamples())
              .append(",\"deltaSamples\":").append(de.deltaSamples())
              .append(",\"deltaPct\":").append(String.format("%.1f", de.deltaPct()))
              .append(",\"state\":\"").append(de.state()).append('"')
              .append('}');
        }
        sb.append("]}");
        System.out.println(sb);
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

    /**
     * Prints async-profiler capability table for the current host OS/arch.
     * No PID is required — invoked via {@code argus profile --capabilities}.
     */
    private static void printCapabilities(boolean useColor, Messages messages) {
        String bold  = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String dim   = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String green = AnsiStyle.style(useColor, AnsiStyle.GREEN);
        String red   = AnsiStyle.style(useColor, AnsiStyle.RED);
        String cyan  = AnsiStyle.style(useColor, AnsiStyle.CYAN);

        String platform = AsProfCapabilities.displayPlatform();
        String version  = AsProfCapabilities.ASPROF_VERSION;
        String binary   = AsProfDownloader.asProfPath();

        System.out.print(RichRenderer.brandedHeader(useColor, "profile",
                messages.get("cmd.profile.desc")));

        System.out.println(RichRenderer.boxHeader(useColor,
                messages.get("profile.capabilities.header", version, platform), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        System.out.println(RichRenderer.boxLine(
                dim + "  " + messages.get("profile.capabilities.binary", binary) + reset, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Events table header
        System.out.println(RichRenderer.boxLine(
                bold + "  " + messages.get("profile.capabilities.events.header") + reset, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        for (AsProfCapabilities.EventInfo ev : AsProfCapabilities.forCurrentHost().values()) {
            String mark  = ev.isSupported() ? green + "✔" + reset : red + "✘" + reset;
            String name  = bold + RichRenderer.padRight(ev.name(), 14) + reset;
            String desc  = ev.isSupported()
                    ? ev.description()
                    : red + ev.unsupportedReason() + reset;
            String line  = "  " + mark + "  " + name + "  " + desc;
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Output formats
        System.out.println(RichRenderer.boxLine(
                bold + "  " + messages.get("profile.capabilities.formats.header") + reset, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        String fmts = "  " + cyan
                + String.join(", ", AsProfCapabilities.OUTPUT_FORMATS)
                + reset;
        System.out.println(RichRenderer.boxLine(fmts, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Tips
        System.out.println(RichRenderer.boxLine(
                bold + "  " + messages.get("profile.capabilities.tips.header") + reset, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  • " + messages.get("profile.capabilities.tip1"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  • " + messages.get("profile.capabilities.tip2"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printHelp(boolean useColor, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(useColor, "profile",
                messages.get("cmd.profile.desc")));
        System.out.println(RichRenderer.boxHeader(useColor, "Usage", WIDTH));
        System.out.println(RichRenderer.boxLine("argus profile <pid> [options]", WIDTH));
        System.out.println(RichRenderer.boxLine("argus profile <subcommand> <pid> [options]", WIDTH));
        System.out.println(RichRenderer.boxLine("argus profile --diff=before.json:after.json", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Modes:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  continuous <pid>", 36)
                + messages.get("cmd.profile.continuous.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  <pid1>,<pid2>,...", 36)
                + messages.get("cmd.profile.multipid.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --pids=<pid1>,<pid2>", 36)
                + messages.get("cmd.profile.multipid.flag.desc"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Continuous options:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --interval=N", 36)
                + messages.get("cmd.profile.continuous.interval.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --window=N", 36)
                + messages.get("cmd.profile.continuous.window.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --output-dir=PATH", 36)
                + messages.get("cmd.profile.continuous.output.dir.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --diff-against=PATH", 36)
                + messages.get("cmd.profile.continuous.diff.against.desc"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Multi-PID options:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --output-prefix=PATH", 36)
                + messages.get("cmd.profile.multipid.output.prefix.desc"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Subcommands:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  start <pid>", 36)
                + messages.get("cmd.profile.subcmd.start.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  stop <pid>", 36)
                + messages.get("cmd.profile.subcmd.stop.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  dump <pid>", 36)
                + messages.get("cmd.profile.subcmd.dump.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  status <pid>", 36)
                + messages.get("cmd.profile.subcmd.status.desc"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Options:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --type=EVENT", 36)
                + messages.get("cmd.profile.event.type.desc"), WIDTH));
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
                RichRenderer.padRight("  --output=PATH", 36)
                + "Output path for stop/dump", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --output-format=FMT", 36)
                + messages.get("cmd.profile.event.output.format.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --top=N", 36)
                + "Show top N methods (default: 20)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --save=PATH", 36)
                + messages.get("cmd.profile.save.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --diff=PATH", 36)
                + messages.get("cmd.profile.diff.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --diff=BEFORE:AFTER", 36)
                + messages.get("cmd.profile.diff.pure.desc"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Advanced:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --interval=N[ms|us|ns]", 36)
                + messages.get("cmd.profile.adv.interval.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --jstackdepth=N", 36)
                + messages.get("cmd.profile.adv.jstackdepth.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --cstack=fp|dwarf|lbr|vm|no", 36)
                + messages.get("cmd.profile.adv.cstack.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --threads", 36)
                + messages.get("cmd.profile.adv.threads.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --alluser", 36)
                + messages.get("cmd.profile.adv.alluser.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --allkernel", 36)
                + messages.get("cmd.profile.adv.allkernel.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --alloc=N[k|m|g]", 36)
                + messages.get("cmd.profile.adv.alloc.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --live", 36)
                + messages.get("cmd.profile.adv.live.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --include=PATTERN", 36)
                + messages.get("cmd.profile.adv.include.desc"), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --exclude=PATTERN", 36)
                + messages.get("cmd.profile.adv.exclude.desc"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Info:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --capabilities", 36)
                + messages.get("cmd.profile.capabilities.desc"), WIDTH));
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

    /**
     * Validates an event/type token before passing it to asprof.
     *
     * <p>Accepts:
     * <ul>
     *   <li>Built-in aliases: cpu, alloc, lock, wall
     *   <li>PMU / hardware counter names: lowercase identifiers with hyphens (e.g. cache-misses)
     *   <li>Method-trace pattern: FQ ClassName.methodName (e.g. java.lang.String.intern)
     *   <li>Any other valid identifier — unknown events are passed through to asprof which
     *       surfaces a clear "unknown event" error message to the user
     * </ul>
     * Rejects flag-shaped values (starting with '-') to prevent argument injection.
     */
    private static boolean isValidType(String type) {
        if (type == null || type.isEmpty()) return false;
        // Reject anything that looks like a flag (prevents argument injection)
        if (type.startsWith("-")) return false;
        // Accept: letters, digits, underscores, dollar signs, dots (method trace), hyphens (PMU events)
        return type.matches("[a-zA-Z_$][a-zA-Z0-9_$.\\-]*");
    }

    /** Returns null if valid, else an i18n error string. */
    private static String validateInterval(String v, Messages messages) {
        if (v == null || v.isEmpty()) return messages.get("error.profile.adv.interval.invalid", v);
        if (!v.endsWith("ms") && !v.endsWith("us") && !v.endsWith("ns") && !v.endsWith("s")) {
            return messages.get("error.profile.adv.interval.invalid", v);
        }
        String num = (v.endsWith("ms") || v.endsWith("us") || v.endsWith("ns"))
                   ? v.substring(0, v.length() - 2)
                   : v.substring(0, v.length() - 1);
        try {
            if (Long.parseLong(num) <= 0) return messages.get("error.profile.adv.interval.invalid", v);
        } catch (NumberFormatException e) {
            return messages.get("error.profile.adv.interval.invalid", v);
        }
        return null;
    }

    /** Returns null if valid, else an i18n error string. */
    private static String validateCstack(String v, Messages messages) {
        if ("fp".equals(v) || "dwarf".equals(v) || "lbr".equals(v) || "vm".equals(v) || "no".equals(v)) {
            return null;
        }
        return messages.get("error.profile.adv.cstack.invalid", v);
    }

    /** Returns null if valid, else an i18n error string. Accepts the asprof clock-source enum. */
    private static String validateClock(String v, Messages messages) {
        if ("tsc".equals(v) || "monotonic".equals(v)) {
            return null;
        }
        return messages.get("error.profile.adv.clock.invalid", v);
    }

    private static int parseClampedInt(String s, int min, int max, int fallback) {
        try {
            return Math.min(max, Math.max(min, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Maps an output format token to a default file extension. */
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
