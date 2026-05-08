package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.doctor.JvmSnapshot;
import io.argus.cli.doctor.JvmSnapshotCollector;
import io.argus.cli.model.ProfileResult;
import io.argus.cli.model.ProfileSnapshot;
import io.argus.cli.provider.ProfileProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.cli.suggest.ProfileSuggestions;
import io.argus.cli.suggest.ProfileSuggestions.ProfileRecommendation;
import io.argus.core.command.CommandGroup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes current JVM configuration and workload, then suggests optimal JVM flags.
 * Supports workload profiles: web, batch, microservice, streaming.
 *
 * <p>Usage:
 * <pre>
 * argus suggest                        # auto-detect workload
 * argus suggest --profile=web          # optimize for web server
 * argus suggest --profile=batch        # optimize for batch processing
 * argus suggest --compare              # compare current vs recommended
 * </pre>
 */
public final class SuggestCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public String name() { return "suggest"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.suggest.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        String workloadProfile = null;
        long pid = 0;
        boolean runProfile = false;
        String profileSnapshotPath = null;
        int profileDurationSec = 5;
        boolean advanced = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--advanced")) {
                advanced = true;
            } else if (arg.startsWith("--profile=")) {
                String val = arg.substring(10);
                // If the value looks like a file path, treat it as a snapshot file;
                // otherwise it's a workload profile name (legacy behaviour).
                if (val.startsWith("/") || val.startsWith(".") || val.endsWith(".json")) {
                    profileSnapshotPath = val;
                    runProfile = true;
                } else {
                    workloadProfile = val;
                }
            } else if (arg.equals("--profile")) {
                runProfile = true;
            } else if (arg.startsWith("--profile-duration=")) {
                try { profileDurationSec = Integer.parseInt(arg.substring(19)); }
                catch (NumberFormatException ignored) {}
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (!arg.startsWith("--")) {
                try { pid = Long.parseLong(arg); } catch (NumberFormatException ignored) {}
            }
        }

        JvmSnapshot s = JvmSnapshotCollector.collect(pid);

        // Auto-detect workload profile if not specified
        if (workloadProfile == null) {
            workloadProfile = detectProfile(s);
        }

        List<Suggestion> suggestions = generateSuggestions(s, workloadProfile, advanced, messages);

        // --- Profile-driven suggestions ------------------------------------------
        List<ProfileRecommendation> profileRecs = new ArrayList<>();
        String profileWarning = null;

        if (runProfile) {
            try {
                ProfileSnapshot snapshot;
                if (profileSnapshotPath != null) {
                    snapshot = ProfileSnapshot.load(Path.of(profileSnapshotPath));
                } else {
                    ProfileProvider provider = registry.findProfileProvider(pid, null);
                    if (provider == null) {
                        profileWarning = messages.get("suggest.profile.no.snapshot");
                        snapshot = null;
                    } else {
                        ProfileResult result = provider.profile(pid, "cpu", profileDurationSec);
                        if ("ok".equals(result.status()) && !result.topMethods().isEmpty()) {
                            // Build a transient snapshot from the live result
                            snapshot = new ProfileSnapshot(
                                    "live", "", System.currentTimeMillis() / 1000L,
                                    pid, result.type() != null ? result.type() : "cpu",
                                    result.durationSec(), result.totalSamples(),
                                    result.topMethods());
                        } else {
                            profileWarning = result.errorMessage() != null
                                    ? result.errorMessage()
                                    : messages.get("suggest.profile.no.snapshot");
                            snapshot = null;
                        }
                    }
                }
                if (snapshot != null) {
                    profileRecs = ProfileSuggestions.analyze(snapshot);
                }
            } catch (Exception e) {
                profileWarning = e.getMessage() != null ? e.getMessage()
                        : messages.get("suggest.profile.no.snapshot");
            }

            // Supersede conflicting workload suggestions with profile-based ones
            if (!profileRecs.isEmpty()) {
                markSuperseded(suggestions, profileRecs);
            }
        }
        // -------------------------------------------------------------------------

        if (json) {
            printJson(suggestions, workloadProfile, profileRecs, runProfile);
            return;
        }

        printRich(suggestions, s, workloadProfile, useColor, profileRecs, profileWarning, runProfile, messages);
    }

    /**
     * Tags workload suggestions that are superseded by a profile-based recommendation
     * covering the same JVM flag area (e.g. NewRatio vs NewRatio).
     */
    private void markSuperseded(List<Suggestion> workload, List<ProfileRecommendation> profileRecs) {
        for (int i = 0; i < workload.size(); i++) {
            Suggestion sg = workload.get(i);
            for (ProfileRecommendation pr : profileRecs) {
                if (!pr.flag().isEmpty() && !sg.flag().isEmpty()
                        && sharesFlag(sg.flag(), pr.flag())) {
                    workload.set(i, new Suggestion(sg.area(), sg.reason(), sg.flag(),
                            sg.note() + " (superseded by profile evidence)"));
                    break;
                }
            }
        }
    }

    /** Returns true if the two flag strings share the same primary flag token. */
    private boolean sharesFlag(String a, String b) {
        String keyA = primaryFlagKey(a);
        String keyB = primaryFlagKey(b);
        return !keyA.isEmpty() && keyA.equalsIgnoreCase(keyB);
    }

    private String primaryFlagKey(String flags) {
        // Take first token, strip -XX:+/- prefix, keep up to '=' sign
        String[] tokens = flags.trim().split("\\s+");
        if (tokens.length == 0) return "";
        String t = tokens[0].replaceFirst("^-XX:[+-]?", "").replaceFirst("^-X", "");
        int eq = t.indexOf('=');
        return eq > 0 ? t.substring(0, eq) : t;
    }

    private String detectProfile(JvmSnapshot s) {
        // Detect framework from VM flags (classpath/module info)
        String flagsJoined = String.join(" ", s.vmFlags()).toLowerCase();

        // Spring Boot / web server indicators
        if (flagsJoined.contains("spring") || flagsJoined.contains("tomcat")
                || flagsJoined.contains("jetty") || flagsJoined.contains("netty")
                || flagsJoined.contains("undertow")) {
            return "web";
        }
        // Kafka / streaming indicators
        if (flagsJoined.contains("kafka") || flagsJoined.contains("flink")
                || flagsJoined.contains("storm") || flagsJoined.contains("pulsar")) {
            return "streaming";
        }
        // Spark / batch indicators
        if (flagsJoined.contains("spark") || flagsJoined.contains("hadoop")
                || flagsJoined.contains("mapreduce") || flagsJoined.contains("batch")) {
            return "batch";
        }

        // Heuristic fallback based on resource profile
        long heapMB = s.heapMax() / (1024 * 1024);
        if (heapMB < 256) return "microservice";
        if (heapMB > 8192) return "batch";

        // Default: cannot determine reliably
        return "general";
    }

    private List<Suggestion> generateSuggestions(JvmSnapshot s, String profile, boolean advanced, Messages messages) {
        List<Suggestion> suggestions = new ArrayList<>();

        String gc = s.gcAlgorithm().toLowerCase();
        long heapMB = s.heapMax() / (1024 * 1024);

        // GC algorithm recommendation
        switch (profile) {
            case "web": {
                if (!gc.contains("g1") && !gc.contains("zgc")) {
                    suggestions.add(new Suggestion("GC Algorithm",
                            "Switch to G1GC for balanced latency/throughput",
                            "-XX:+UseG1GC", "Current: " + s.gcAlgorithm()));
                }
                // -XX:MaxGCPauseMillis is a no-op on ZGC; suppress it when ZGC is active
                if (!gc.contains("zgc")) {
                    suggestions.add(new Suggestion("GC Pause Target",
                            "Set max pause target for web workloads",
                            "-XX:MaxGCPauseMillis=200", "Balances latency vs throughput"));
                }
                if (heapMB >= 8192 && !gc.contains("zgc")) {
                    suggestions.add(new Suggestion("Consider ZGC",
                            "For heaps > 8GB, ZGC provides sub-ms pauses",
                            "-XX:+UseZGC", "Requires Java 17+"));
                }
                break;
            }
            case "batch": {
                if (!gc.contains("parallel")) {
                    suggestions.add(new Suggestion("GC Algorithm",
                            "Parallel GC maximizes throughput for batch processing",
                            "-XX:+UseParallelGC", "Current: " + s.gcAlgorithm()));
                }
                suggestions.add(new Suggestion("GC Threads",
                        "Match GC threads to available processors",
                        "-XX:ParallelGCThreads=" + s.availableProcessors(),
                        s.availableProcessors() + " processors available"));
                break;
            }
            case "microservice": {
                if (heapMB < 256) {
                    suggestions.add(new Suggestion("GC Algorithm",
                            "Serial GC is efficient for small heaps (<256MB)",
                            "-XX:+UseSerialGC", "Lowest footprint"));
                }
                suggestions.add(new Suggestion("Class Data Sharing",
                        "Enable CDS for faster startup",
                        "-XX:SharedArchiveFile=app-cds.jsa", "Use: java -Xshare:dump first"));
                break;
            }
            case "streaming": {
                if (!gc.contains("g1")) {
                    suggestions.add(new Suggestion("GC Algorithm",
                            "G1GC handles mixed workloads well for streaming",
                            "-XX:+UseG1GC", ""));
                }
                suggestions.add(new Suggestion("Heap Region Size",
                        "Larger regions reduce fragmentation for steady allocation",
                        "-XX:G1HeapRegionSize=8m", "Default is auto-sized"));
                break;
            }
            case "general": {
                // Conservative suggestions when workload is unknown
                if (!gc.contains("g1") && heapMB > 512) {
                    suggestions.add(new Suggestion("GC Algorithm",
                            "G1GC is the safest default for general workloads",
                            "-XX:+UseG1GC", "Default since Java 9"));
                }
                break;
            }
            default:
                break;
        }

        // Universal recommendations based on current state
        double heapPct = s.heapUsagePercent();
        if (heapPct > 75) {
            long suggestedMB = (long) (heapMB * 1.5);
            suggestions.add(new Suggestion("Heap Size",
                    String.format("Heap usage %.0f%% — increase max heap", heapPct),
                    "-Xmx" + (suggestedMB >= 1024 ? suggestedMB / 1024 + "g" : suggestedMB + "m"),
                    "Current: " + heapMB + "MB used " + String.format("%.0f%%", heapPct)));
        }

        if (s.gcOverheadPercent() > 5) {
            suggestions.add(new Suggestion("GC Overhead",
                    String.format("GC overhead %.1f%% — tune GC or increase heap", s.gcOverheadPercent()),
                    "-XX:GCTimeRatio=19", "Target: 95% throughput (5% GC time)"));
        }

        // Metaspace
        for (var pool : s.memoryPools().values()) {
            if (pool.name().toLowerCase().contains("metaspace") && pool.max() <= 0) {
                suggestions.add(new Suggestion("Metaspace Limit",
                        "No metaspace limit set — risk of unbounded growth",
                        "-XX:MaxMetaspaceSize=512m", "Prevents runaway class loading"));
            }
        }

        // ── ZGC-specific suggestions ─────────────────────────────────────────
        if (gc.contains("zgc")) {
            addZgcSuggestions(suggestions, s, heapMB, messages);
            boolean spikyBehavior = s.gcOverheadPercent() > 3.0 && s.maxRecentPauseMs() > 10;
            if (advanced || spikyBehavior) {
                addAdvancedZgcSuggestions(suggestions, advanced, messages);
            }
        }

        return suggestions;
    }

    /**
     * ZGC-specific suggestions that always apply. Only called when the current GC algorithm contains "zgc".
     */
    private void addZgcSuggestions(List<Suggestion> suggestions, JvmSnapshot s,
                                   long heapMB, Messages messages) {
        double heapPct = s.heapUsagePercent();

        // 1. SoftMaxHeapSize — suggest when heap usage is consistently below 80% of -Xmx
        if (heapPct < 80.0 && heapMB > 0) {
            long softMaxMB = Math.round(heapMB * 1.2);
            // Round up to nearest GB
            long softMaxGB = (softMaxMB + 1023) / 1024;
            if (softMaxGB < 1) softMaxGB = 1;
            suggestions.add(new Suggestion(
                    messages.get("suggest.zgc.softmax.area"),
                    messages.get("suggest.zgc.softmax.reason"),
                    "-XX:SoftMaxHeapSize=" + softMaxGB + "g",
                    "Current heap usage: " + String.format("%.0f%%", heapPct) + " of " + heapMB + "MB -Xmx"));
        }

        // 2. ZUncommit — when heap committed > 4 GB and uptime > 1 hour
        long heapCommittedMB = s.heapCommitted() / (1024 * 1024);
        long uptimeSec = s.uptimeMs() / 1000;
        if (heapCommittedMB > 4096 && uptimeSec > 3600) {
            suggestions.add(new Suggestion(
                    messages.get("suggest.zgc.uncommit.area"),
                    messages.get("suggest.zgc.uncommit.reason"),
                    "-XX:+ZUncommit -XX:ZUncommitDelay=300",
                    "Committed heap: " + heapCommittedMB + "MB; uptime: " + (uptimeSec / 3600) + "h"));
        }
    }

    /**
     * Advanced ZGC suggestions — only emitted when the user passes {@code --advanced} or
     * pause-variance heuristics suggest bursty allocation behavior.
     */
    private void addAdvancedZgcSuggestions(List<Suggestion> suggestions,
                                           boolean userAskedAdvanced, Messages messages) {
        String note = userAskedAdvanced ? "ADVANCED" : "detected spike behavior";
        suggestions.add(new Suggestion(
                messages.get("suggest.zgc.spike.area"),
                messages.get("suggest.zgc.spike.reason"),
                "-XX:ZAllocationSpikeTolerance=5.0",
                note + "; default is 2.0"));
    }

    private void printRich(List<Suggestion> suggestions, JvmSnapshot s, String profile,
                           boolean c, List<ProfileRecommendation> profileRecs,
                           String profileWarning, boolean profileRequested, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(c, "suggest",
                "JVM flag optimization based on workload analysis"));
        System.out.println(RichRenderer.boxHeader(c, "JVM Optimization", WIDTH,
                "profile:" + profile, "heap:" + (s.heapMax() / (1024 * 1024)) + "MB",
                "gc:" + s.gcAlgorithm()));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + "Detected profile: "
                        + AnsiStyle.style(c, AnsiStyle.CYAN) + profile.toUpperCase()
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        for (int i = 0; i < suggestions.size(); i++) {
            Suggestion sg = suggestions.get(i);
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + (i + 1) + ". " + sg.area()
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.boxLine("     " + sg.reason(), WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "     " + AnsiStyle.style(c, AnsiStyle.GREEN) + sg.flag()
                            + AnsiStyle.style(c, AnsiStyle.RESET)
                            + (sg.note().isEmpty() ? "" : "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                            + "(" + sg.note() + ")" + AnsiStyle.style(c, AnsiStyle.RESET)), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
        }

        // Summary: all flags on one line for copy-paste
        System.out.println(RichRenderer.boxSeparator(WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + "Copy-paste flags:"
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        StringBuilder allFlags = new StringBuilder("  ");
        for (Suggestion sg : suggestions) {
            if (!sg.flag().isEmpty()) allFlags.append(sg.flag()).append(" ");
        }
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(c, AnsiStyle.GREEN) + allFlags.toString().trim()
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(c, suggestions.size() + " suggestions", WIDTH));

        // ---- Profile-Driven Suggestions section --------------------------------
        if (profileRequested) {
            System.out.println();
            printProfileSection(c, profileRecs, profileWarning, messages);
        }
    }

    private void printProfileSection(boolean c, List<ProfileRecommendation> recs,
                                     String warning, Messages messages) {
        System.out.println(RichRenderer.boxHeader(c,
                messages.get("suggest.profile.section.title"), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (warning != null) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.YELLOW) + "[!] " + warning
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
        }

        if (recs.isEmpty()) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                            + messages.get("suggest.profile.no.suggestions")
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        } else {
            for (int i = 0; i < recs.size(); i++) {
                ProfileRecommendation r = recs.get(i);
                String confColor;
                switch (r.confidence()) {
                    case HIGH: confColor = AnsiStyle.GREEN; break;
                    case MED:  confColor = AnsiStyle.YELLOW; break;
                    default:   confColor = AnsiStyle.DIM; break;
                }
                System.out.println(RichRenderer.boxLine(
                        "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + (i + 1) + ". "
                                + r.ruleName() + AnsiStyle.style(c, AnsiStyle.RESET)
                                + "  " + AnsiStyle.style(c, confColor)
                                + "[" + r.confidence().name().toLowerCase() + "]"
                                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                System.out.println(RichRenderer.boxLine("     " + r.rationale(), WIDTH));
                if (!r.flag().isEmpty()) {
                    System.out.println(RichRenderer.boxLine(
                            "     " + AnsiStyle.style(c, AnsiStyle.GREEN) + r.flag()
                                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                }
                if (!r.evidence().isEmpty()) {
                    System.out.println(RichRenderer.boxLine(
                            "     " + AnsiStyle.style(c, AnsiStyle.DIM)
                                    + String.format("evidence: %s (%.1f%%)",
                                            r.evidence(), r.evidencePct())
                                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                }
                System.out.println(RichRenderer.emptyLine(WIDTH));
            }
        }

        System.out.println(RichRenderer.boxFooter(c, recs.size() + " profile suggestion(s)", WIDTH));
    }

    private static void printJson(List<Suggestion> suggestions, String profile,
                                  List<ProfileRecommendation> profileRecs, boolean profileRequested) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"profile\":\"").append(profile).append('"');
        sb.append(",\"suggestions\":[");
        for (int i = 0; i < suggestions.size(); i++) {
            Suggestion sg = suggestions.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"area\":\"").append(RichRenderer.escapeJson(sg.area())).append('"');
            sb.append(",\"reason\":\"").append(RichRenderer.escapeJson(sg.reason())).append('"');
            sb.append(",\"flag\":\"").append(RichRenderer.escapeJson(sg.flag())).append('"');
            sb.append(",\"source\":\"workload\"}");
        }
        sb.append("],\"flags\":[");
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(suggestions.get(i).flag()).append('"');
        }
        sb.append(']');

        if (profileRequested) {
            sb.append(",\"profileSuggestions\":[");
            for (int i = 0; i < profileRecs.size(); i++) {
                ProfileRecommendation r = profileRecs.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"rule\":\"").append(RichRenderer.escapeJson(r.ruleName())).append('"');
                sb.append(",\"confidence\":\"").append(r.confidence().name().toLowerCase()).append('"');
                sb.append(",\"flag\":\"").append(RichRenderer.escapeJson(r.flag())).append('"');
                sb.append(",\"rationale\":\"").append(RichRenderer.escapeJson(r.rationale())).append('"');
                sb.append(",\"evidence\":\"").append(RichRenderer.escapeJson(r.evidence())).append('"');
                sb.append(",\"evidencePct\":").append(String.format("%.2f", r.evidencePct()));
                sb.append(",\"source\":\"profile\"}");
            }
            sb.append(']');
        }

        sb.append('}');
        System.out.println(sb);
    }

    private static final class Suggestion {
        final String area;
        final String reason;
        final String flag;
        final String note;
        Suggestion(String area, String reason, String flag, String note) {
            this.area = area;
            this.reason = reason;
            this.flag = flag;
            this.note = note;
        }
        String area() { return area; }
        String reason() { return reason; }
        String flag() { return flag; }
        String note() { return note; }
    }
}
