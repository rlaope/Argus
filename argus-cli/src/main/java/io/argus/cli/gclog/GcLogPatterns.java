package io.argus.cli.gclog;

import java.util.regex.Pattern;

/**
 * Shared GC log regex patterns used by GcLogParser and GcLogFollower.
 */
final class GcLogPatterns {

    static final Pattern TIMESTAMP_UPTIME = Pattern.compile("\\[(\\d+\\.\\d+)s\\]");
    static final Pattern TIMESTAMP_ISO    = Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2}T[\\d:.+]+)\\]");

    static final Pattern UNIFIED_PAUSE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Pause\\s+(\\S+(?:\\s+\\([^)]*\\))*)\\s+(\\d+)([MKG])->(\\d+)([MKG])\\((\\d+)([MKG])\\)\\s+(\\d+\\.?\\d*)ms");

    static final Pattern UNIFIED_CAUSE = Pattern.compile("\\(([^)]+)\\)\\s+\\d+[MKG]->");

    static final Pattern ZGC_PAUSE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Pause\\s+(\\w+)\\s+(\\d+\\.?\\d*)ms");

    static final Pattern ZGC_CYCLE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Garbage Collection\\s+\\(([^)]+)\\)\\s+(\\d+)([MKG])\\([^)]*\\)->(\\d+)([MKG])");

    static final Pattern SHENANDOAH_PAUSE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Pause\\s+(Init Mark|Final Mark|Init Update|Final Update|Full).*?(\\d+\\.?\\d*)ms");

    static final Pattern LEGACY_GC = Pattern.compile(
            "(\\d+\\.\\d+):\\s+\\[(Full )?GC\\s*\\(([^)]+)\\).*?(\\d+)K->(\\d+)K\\((\\d+)K\\),?\\s+(\\d+\\.\\d+)\\s+secs");

    static final Pattern UNIFIED_CONCURRENT = Pattern.compile(
            "GC\\(\\d+\\)\\s+Concurrent\\s+(\\S+)\\s+(\\d+\\.?\\d*)ms");

    private GcLogPatterns() {}
}
