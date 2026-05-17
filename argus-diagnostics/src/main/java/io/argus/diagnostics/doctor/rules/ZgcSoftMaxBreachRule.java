package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects when ZGC heap committed memory exceeds the configured SoftMaxHeapSize.
 *
 * <p>ZGC's SoftMaxHeapSize is a soft upper bound on heap usage. The JVM tries to
 * keep committed heap below this limit, but under allocation pressure it may exceed
 * it. When that happens, GC efficiency degrades and allocation stalls may occur.
 *
 * <p>Fires WARNING when {@code heapCommitted > SoftMaxHeapSize > 0}.
 * Skipped entirely for non-ZGC collectors.
 */
public final class ZgcSoftMaxBreachRule implements HealthRule {

    private static final Pattern SOFT_MAX_PATTERN =
            Pattern.compile("-XX:SoftMaxHeapSize=(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        if (!s.gcAlgorithm().contains("ZGC")) return List.of();

        long softMax = parseSoftMaxHeapSize(s.vmFlags());
        if (softMax <= 0) return List.of();

        long committed = s.heapCommitted();
        if (committed <= softMax) return List.of();

        long deltaBytes = committed - softMax;

        var b = Finding.builder(Severity.WARNING, "GC",
                "ZGC heap committed exceeds SoftMaxHeapSize");

        b.detail(String.format(
                "committed %.2fg > soft max %.2fg (delta %.2fg)",
                toGib(committed), toGib(softMax), toGib(deltaBytes)));

        b.recommend("Raise -XX:SoftMaxHeapSize toward -Xmx, or raise -Xmx; "
                + "verify allocation rate isn't bursting beyond ZGC's headroom budget.");

        return List.of(b.build());
    }

    /**
     * Parse {@code -XX:SoftMaxHeapSize=N} from VM flags. VM.flags emits raw bytes
     * for size flags. Returns 0 if not set or unparseable.
     */
    static long parseSoftMaxHeapSize(List<String> vmFlags) {
        for (String flag : vmFlags) {
            Matcher m = SOFT_MAX_PATTERN.matcher(flag);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }

    private static double toGib(long bytes) {
        return bytes / (1024.0 * 1024.0 * 1024.0);
    }
}
