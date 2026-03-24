package io.argus.cli.provider.jdk;

import io.argus.cli.model.HeapResult;
import io.argus.cli.provider.HeapProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HeapProvider that uses {@code jcmd GC.heap_info} to report heap memory usage.
 *
 * <p>The output format varies by GC type. This parser handles the most common formats:
 * <pre>
 *  garbage-first heap   total 262144K, used 131072K [...]
 *   region size 1024K, 100 young (102400K), 10 survivors (10240K)
 *  Metaspace       used 50000K, committed 51200K, reserved 1056768K
 * </pre>
 */
public final class JdkHeapProvider implements HeapProvider {

    // "garbage-first heap   total 262144K, used 131072K"
    private static final Pattern HEAP_TOTAL_USED = Pattern.compile(
            "total\\s+(\\d+)K.*?used\\s+(\\d+)K", Pattern.CASE_INSENSITIVE);

    // "committed 51200K"
    private static final Pattern COMMITTED_PATTERN = Pattern.compile(
            "committed\\s+(\\d+)K", Pattern.CASE_INSENSITIVE);

    // "reserved 1056768K" or "-Xmx" derived max — not always present; we use committed as fallback
    private static final Pattern RESERVED_PATTERN = Pattern.compile(
            "reserved\\s+(\\d+)K", Pattern.CASE_INSENSITIVE);

    // Space-named lines: "Metaspace       used 50000K, committed 51200K, reserved 1056768K"
    //                     "Eden Space:   capacity = 131072K, ..."
    private static final Pattern NAMED_SPACE = Pattern.compile(
            "^\\s*(\\S[^:=]*)(?::|\\s+)?\\s*(?:capacity\\s*=\\s*(\\d+)K.*?)?used\\s+(\\d+)K[,\\s]+committed\\s+(\\d+)K",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String source() {
        return "jdk";
    }

    @Override
    public HeapResult getHeapInfo(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "GC.heap_info");
        } catch (RuntimeException e) {
            return new HeapResult(0L, 0L, 0L, Map.of());
        }

        long heapUsed = 0L;
        long heapCommitted = 0L;
        long heapMax = 0L;
        Map<String, HeapResult.SpaceInfo> spaces = new HashMap<>();

        for (String line : output.split("\n")) {
            // Try to match named space lines (Metaspace, Eden, Survivor, etc.)
            Matcher namedMatcher = NAMED_SPACE.matcher(line);
            if (namedMatcher.find()) {
                String spaceName = namedMatcher.group(1).trim().replaceAll("\\s+", " ");
                long spaceUsed = JdkParseUtils.parseLong(namedMatcher.group(3)) * 1024L;
                long spaceCommitted = JdkParseUtils.parseLong(namedMatcher.group(4)) * 1024L;
                long spaceMax = spaceCommitted; // best available approximation

                // Check for reserved as max
                Matcher resMatcher = RESERVED_PATTERN.matcher(line);
                if (resMatcher.find()) {
                    spaceMax = JdkParseUtils.parseLong(resMatcher.group(1)) * 1024L;
                }

                spaces.put(spaceName, new HeapResult.SpaceInfo(spaceName, spaceUsed, spaceCommitted, spaceMax));
                continue;
            }

            // Match main heap total/used line
            Matcher totalMatcher = HEAP_TOTAL_USED.matcher(line);
            if (totalMatcher.find() && heapCommitted == 0) {
                heapCommitted = JdkParseUtils.parseLong(totalMatcher.group(1)) * 1024L;
                heapUsed = JdkParseUtils.parseLong(totalMatcher.group(2)) * 1024L;

                // Try to find reserved/max on same line
                Matcher resMatcher = RESERVED_PATTERN.matcher(line);
                heapMax = resMatcher.find() ? JdkParseUtils.parseLong(resMatcher.group(1)) * 1024L : heapCommitted;
                continue;
            }

            // Fallback: committed-only line
            Matcher committedMatcher = COMMITTED_PATTERN.matcher(line);
            if (committedMatcher.find() && heapCommitted == 0) {
                heapCommitted = JdkParseUtils.parseLong(committedMatcher.group(1)) * 1024L;
            }
        }

        if (heapMax == 0L) {
            heapMax = heapCommitted;
        }

        return new HeapResult(heapUsed, heapCommitted, heapMax, Map.copyOf(spaces));
    }

}
