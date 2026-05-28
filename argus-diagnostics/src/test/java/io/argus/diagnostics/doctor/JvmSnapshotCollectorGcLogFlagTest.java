package io.argus.diagnostics.doctor;

import io.argus.diagnostics.gclog.G1Stats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@code -Xlog:gc:file=<path>} flag detection used by
 * {@link JvmSnapshotCollector#extractG1Stats(List, String)}.
 *
 * <p>The regex must:
 * <ul>
 *   <li>Capture the path (and only the path) when {@code :file=} is present.</li>
 *   <li>Not match flags that log only to stdout/stderr (so JFR-aware doctor rules
 *       don't fire spuriously on a wrongly-captured path).</li>
 * </ul>
 *
 * <p>This test does not actually parse a GC log — it verifies the regex
 * decision by checking whether extractG1Stats returns G1Stats.empty()
 * when given non-G1 input. The interesting failure is the regex; we use
 * an unreadable / non-existent path so the file-open path safely fails
 * and returns empty(), which doesn't tell us anything about the regex.
 * Instead we test the regex pattern directly via a tiny mirror.
 */
class JvmSnapshotCollectorGcLogFlagTest {

    /** Mirror of the production pattern — keep in sync with JvmSnapshotCollector.GC_LOG_FILE_FLAG. */
    private static final java.util.regex.Pattern PATTERN = java.util.regex.Pattern.compile(
            "-Xlog:gc[^\\s]*?:file=([^:\\s,]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static String matchPath(String flag) {
        var m = PATTERN.matcher(flag);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void simple_file_form_captures_path_only() {
        assertEquals("/tmp/gc.log", matchPath("-Xlog:gc:file=/tmp/gc.log"));
    }

    @Test
    void decorated_file_form_captures_path_only() {
        // -Xlog:gc*=info:file=/var/log/gc.log:time,level,tags
        assertEquals("/var/log/gc.log",
                matchPath("-Xlog:gc*=info:file=/var/log/gc.log:time,level,tags"));
    }

    @Test
    void without_file_eq_does_not_match() {
        // -Xlog:gc:stdout — no file= → should not match.
        assertNull(matchPath("-Xlog:gc:stdout"));
    }

    @Test
    void without_file_eq_decorated_does_not_match() {
        // -Xlog:gc=info:stderr — log to stderr, no file= → should not match.
        assertNull(matchPath("-Xlog:gc=info:stderr"));
    }

    @Test
    void unrelated_xlog_flag_does_not_match() {
        assertNull(matchPath("-Xlog:os+thread=info"));
    }

    @Test
    void extract_returns_empty_for_non_g1_collector() {
        G1Stats result = JvmSnapshotCollector.extractG1Stats(
                List.of("-Xlog:gc:file=/tmp/gc.log"), "ZGC");
        assertSame(G1Stats.empty().getClass(), result.getClass());
        assertFalse(result.present());
    }

    @Test
    void extract_returns_empty_for_no_log_flag() {
        G1Stats result = JvmSnapshotCollector.extractG1Stats(
                List.of("-Xmx4g"), "G1 Young Generation");
        assertFalse(result.present());
    }
}
