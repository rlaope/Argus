package io.argus.cli.provider.jdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pre-flight permission and environment checker for async-profiler on Linux.
 *
 * <p>On macOS, {@link #validate} returns {@link Result#OK} immediately — asprof
 * works without kernel tuning on macOS.  On Windows, the platform check in
 * {@link AsProfDownloader} already returns {@code null} so this code is never
 * reached in practice.
 *
 * <p>I18n note: AsProfProvider (the caller) does not carry a Messages instance,
 * so error strings are hardcoded English here — consistent with the existing
 * hardcoded strings in AsProfProvider itself.  The same strings are registered
 * as keys {@code error.profile.preflight.*} / {@code warn.profile.preflight.*}
 * in all four messages_*.properties files for callers that do carry a Messages
 * instance and want localised output.
 */
public final class AsProfPermissionCheck {

    private AsProfPermissionCheck() {}

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    public static final class Result {
        public final boolean ok;
        public final List<String> warnings;
        public final String error;    // null if ok
        public final String fixHint;  // null if ok or no actionable fix

        private Result(boolean ok, List<String> warnings, String error, String fixHint) {
            this.ok = ok;
            this.warnings = Collections.unmodifiableList(warnings);
            this.error = error;
            this.fixHint = fixHint;
        }

        /** Successful result, possibly with warnings. */
        public static Result ok(List<String> warnings) {
            return new Result(true, warnings, null, null);
        }

        /** Convenience: successful result with no warnings. */
        public static Result OK() {
            return ok(Collections.emptyList());
        }

        /** Failed result with an actionable fix hint. */
        public static Result FAIL(String error, String fixHint) {
            return new Result(false, Collections.emptyList(), error, fixHint);
        }

        /** Failed result without a fix hint. */
        public static Result FAIL(String error) {
            return FAIL(error, null);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Validates that the current environment allows async-profiler to attach to
     * the given PID.
     *
     * @param pid the target JVM process ID
     * @return a {@link Result} describing whether profiling can proceed
     */
    public static Result validate(long pid) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("linux")) {
            // macOS, Windows, etc. — no /proc tuning required
            return Result.OK();
        }

        List<String> warnings = new ArrayList<>();

        // 1. perf_event_paranoid
        Result perfCheck = checkPerfEventParanoid(warnings);
        if (!perfCheck.ok) {
            return perfCheck;
        }

        // 2. kptr_restrict (warn only)
        checkKptrRestrict(warnings);

        // 3. PID accessibility + ptrace_scope
        Result pidCheck = checkPidAccessibility(pid, warnings);
        if (!pidCheck.ok) {
            return pidCheck;
        }

        // 4. Container/cgroup detection (warn only)
        checkContainerRuntime(warnings);

        return Result.ok(warnings);
    }

    // -------------------------------------------------------------------------
    // Individual checks
    // -------------------------------------------------------------------------

    private static Result checkPerfEventParanoid(List<String> warnings) {
        String path = "/proc/sys/kernel/perf_event_paranoid";
        String raw = readFirstLine(path);
        if (raw == null) {
            // Cannot read the file — warn but do not fail (may still work as root)
            warnings.add("Cannot read " + path + "; you may need root privileges");
            return Result.ok(warnings);
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            warnings.add("Unexpected value in " + path + ": " + raw.trim());
            return Result.ok(warnings);
        }
        if (value >= 2) {
            return Result.FAIL(
                "perf_event_paranoid=" + value + " prevents async-profiler CPU profiling (must be <= 1)",
                "Run: sudo sysctl kernel.perf_event_paranoid=1  (temporary)\n"
                + "  or add 'kernel.perf_event_paranoid=1' to /etc/sysctl.d/99-perf.conf (permanent)"
            );
        }
        // value == 1 is fine for CPU; >= 2 was already caught above
        return Result.ok(warnings);
    }

    private static void checkKptrRestrict(List<String> warnings) {
        String path = "/proc/sys/kernel/kptr_restrict";
        String raw = readFirstLine(path);
        if (raw == null) {
            return; // Cannot read — skip silently
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (value >= 2) {
            warnings.add("kptr_restrict=" + value
                + ": kernel symbols may be hidden; native stack frames will show as [unknown]");
        }
    }

    private static Result checkPidAccessibility(long pid, List<String> warnings) {
        String statusPath = "/proc/" + pid + "/status";
        if (!isReadable(statusPath)) {
            return Result.FAIL(
                "Cannot read " + statusPath + ": target PID " + pid + " is not accessible",
                "Check that argus runs as the same user as the JVM, or that ptrace_scope allows attachment.\n"
                + "  If inside a container, the JVM PID must be the host PID (not the container-namespace PID)."
            );
        }

        // ptrace_scope check
        String ptracePath = "/proc/sys/kernel/yama/ptrace_scope";
        String raw = readFirstLine(ptracePath);
        if (raw == null) {
            return Result.ok(warnings); // File absent (Yama not loaded) — OK
        }
        int ptraceScope;
        try {
            ptraceScope = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return Result.ok(warnings);
        }
        if (ptraceScope == 1) {
            warnings.add("ptrace_scope=1: async-profiler can only attach to processes owned by the same UID");
        } else if (ptraceScope >= 2) {
            return Result.FAIL(
                "ptrace_scope=" + ptraceScope + " prevents async-profiler from attaching to other processes",
                "Run: sudo sysctl kernel.yama.ptrace_scope=0\n"
                + "  or run argus as the same user as the target JVM"
            );
        }
        return Result.ok(warnings);
    }

    private static void checkContainerRuntime(List<String> warnings) {
        String cgroupPath = "/proc/1/cgroup";
        String content = readFileContent(cgroupPath);
        if (content == null) {
            return;
        }
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("docker") || lower.contains("containerd")
                || lower.contains("kubepods") || lower.contains("lxc")) {
            warnings.add("Detected container runtime — async-profiler needs --cap-add=SYS_ADMIN or "
                + "--privileged. If you mounted host /proc, ensure the target PID is the host PID "
                + "(not the container's).");
        }
    }

    // -------------------------------------------------------------------------
    // File helpers
    // -------------------------------------------------------------------------

    /** Reads the first line of a file, returning {@code null} on any error. */
    private static String readFirstLine(String path) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(path));
            return lines.isEmpty() ? null : lines.get(0);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** Reads all content of a file, returning {@code null} on any error. */
    private static String readFileContent(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)));
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** Returns {@code true} if the file exists and is readable. */
    private static boolean isReadable(String path) {
        return Files.isReadable(Paths.get(path));
    }
}
