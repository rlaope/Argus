package io.argus.cli.gclog;

import java.util.regex.Pattern;

/**
 * Shared GC log regex patterns. Single source of truth for parsers + followers.
 */
public final class GcLogPatterns {

    public static final Pattern TIMESTAMP_UPTIME = Pattern.compile("\\[(\\d+\\.\\d+)s\\]");
    public static final Pattern TIMESTAMP_ISO    = Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2}T[\\d:.+]+)\\]");

    public static final Pattern UNIFIED_PAUSE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Pause\\s+(\\S+(?:\\s+\\([^)]*\\))*)\\s+(\\d+)([MKG])->(\\d+)([MKG])\\((\\d+)([MKG])\\)\\s+(\\d+\\.?\\d*)ms");

    public static final Pattern UNIFIED_CAUSE = Pattern.compile("\\(([^)]+)\\)\\s+\\d+[MKG]->");

    public static final Pattern ZGC_PAUSE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Pause\\s+(\\w+)\\s+(\\d+\\.?\\d*)ms");

    public static final Pattern ZGC_CYCLE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Garbage Collection\\s+\\(([^)]+)\\)\\s+(\\d+)([MKG])\\([^)]*\\)->(\\d+)([MKG])");

    public static final Pattern SHENANDOAH_PAUSE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Pause\\s+(Init Mark|Final Mark|Init Update|Final Update|Full).*?(\\d+\\.?\\d*)ms");

    // Legacy JDK 7/8: cause group is optional (early -XX:+PrintGC without +PrintGCCause)
    public static final Pattern LEGACY_GC = Pattern.compile(
            "(\\d+\\.\\d+):\\s+\\[(Full )?GC\\s*(?:\\(([^)]+)\\))?.*?(\\d+)K->(\\d+)K\\((\\d+)K\\),?\\s+(\\d+\\.\\d+)\\s+secs");

    public static final Pattern UNIFIED_CONCURRENT = Pattern.compile(
            "GC\\(\\d+\\)\\s+Concurrent\\s+(\\S+)\\s+(\\d+\\.?\\d*)ms");

    public static final Pattern GC_PHASE = Pattern.compile(
            "GC\\((\\d+)\\)\\s+(.+?):\\s+(\\d+\\.?\\d*)ms");

    private GcLogPatterns() {}
}
