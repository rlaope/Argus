package io.argus.cli.gcwhy;

import java.util.List;
import java.util.Map;

/**
 * Result of the gcwhy correlation engine: the worst pause in the window,
 * a small set of "why" bullets, and a map of related counters to display.
 */
public record GcWhyResult(
        double timestampSec,
        String type,
        String cause,
        double pauseMs,
        List<String> bullets,
        Map<String, String> counters) {

    public static GcWhyResult empty() {
        return new GcWhyResult(0, "", "", 0, List.of(), Map.of());
    }
}
