package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects misconfigured G1 IHOP (Initiating Heap Occupancy Percent).
 *
 * <p>IHOP controls when G1 starts the concurrent marking cycle. If it's too high
 * (e.g. 70+%), the cycle may not finish before old gen fills, forcing Full GC.
 * If adaptive IHOP is disabled while the manual value is high, G1 cannot react
 * to allocation-rate spikes.
 *
 * <p>Fires WARNING when:
 * <ul>
 *   <li>{@code -XX:-G1UseAdaptiveIHOP} (adaptive disabled) AND
 *       {@code -XX:InitiatingHeapOccupancyPercent &gt;= 70}.</li>
 * </ul>
 * Skipped entirely for non-G1 collectors.
 */
public final class G1IhopConfigurationRule implements HealthRule {

    private static final Pattern IHOP_FLAG = Pattern.compile(
            "-XX:InitiatingHeapOccupancyPercent=(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern ADAPTIVE_IHOP_FLAG = Pattern.compile(
            "-XX:([+-])G1UseAdaptiveIHOP", Pattern.CASE_INSENSITIVE);

    static final int HIGH_IHOP_THRESHOLD = 70;

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        if (!s.gcAlgorithm().contains("G1")) return List.of();

        Boolean adaptive = parseAdaptive(s.vmFlags());
        int     ihop     = parseIhop(s.vmFlags());

        if (adaptive == null || adaptive) return List.of(); // adaptive on (default) — fine
        if (ihop < HIGH_IHOP_THRESHOLD)   return List.of();

        var b = Finding.builder(Severity.WARNING, "GC",
                "Adaptive IHOP disabled with high manual threshold (" + ihop + "%)");

        b.detail("-XX:-G1UseAdaptiveIHOP -XX:InitiatingHeapOccupancyPercent=" + ihop
                + " — concurrent cycle may not finish before old gen fills, risking Full GC");

        b.recommend("Re-enable adaptive IHOP with -XX:+G1UseAdaptiveIHOP, "
                + "or lower -XX:InitiatingHeapOccupancyPercent to 45–55 to start marking earlier.");

        b.flag("-XX:+G1UseAdaptiveIHOP");
        b.flag("-XX:InitiatingHeapOccupancyPercent=45");

        return List.of(b.build());
    }

    static Boolean parseAdaptive(List<String> vmFlags) {
        for (String flag : vmFlags) {
            Matcher m = ADAPTIVE_IHOP_FLAG.matcher(flag);
            if (m.find()) return "+".equals(m.group(1));
        }
        return null;
    }

    static int parseIhop(List<String> vmFlags) {
        for (String flag : vmFlags) {
            Matcher m = IHOP_FLAG.matcher(flag);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); }
                catch (NumberFormatException ignored) {}
            }
        }
        return 45; // JVM default
    }
}
