package io.argus.server.analysis;

import java.util.Locale;

/**
 * Post-JEP-491 classification of virtual-thread pinning causes.
 *
 * <p>JDK 24 (JEP 491) reworked the runtime so that holding a {@code synchronized}
 * monitor across a blocking operation no longer pins the carrier thread. The old
 * "synchronized is the footgun" framing is therefore obsolete on JDK 24+. The
 * pinning that remains is rooted in places the runtime still cannot unmount a
 * continuation:
 *
 * <ul>
 *   <li>{@link #NATIVE_FRAME} — a native (C/C++) method is on the stack;</li>
 *   <li>{@link #FOREIGN_CALL} — a Foreign Function &amp; Memory (Panama / JNI)
 *       downcall is in progress;</li>
 *   <li>{@link #OBJECT_MONITOR_IN_NATIVE} — an object monitor is entered while a
 *       native frame is active, which the runtime still cannot release.</li>
 * </ul>
 *
 * <p>Classification is a pure function of the pinning stack frames: the topmost
 * frame that matches a known pinning shape wins, scanning from the leaf (the
 * frame nearest the blocking call) downwards.
 */
public enum PinningTaxonomy {

    /** A native method frame is on the stack (e.g. {@code Foo.bar(Native Method)}). */
    NATIVE_FRAME,

    /** A Foreign Function &amp; Memory / JNI downcall is in progress. */
    FOREIGN_CALL,

    /** An object monitor is held while a native frame is active. */
    OBJECT_MONITOR_IN_NATIVE,

    /** Pinning shape could not be attributed to a known post-491 cause. */
    UNCLASSIFIED;

    /**
     * Classifies a pinning stack trace into a post-JEP-491 bucket.
     *
     * <p>The stack trace is the multi-line text captured with a
     * {@code jdk.VirtualThreadPinned} event, e.g.:
     * <pre>
     *     at java.base/java.lang.Object.wait0(Native Method)
     *     at com.example.Db.query(Db.java:42)
     * </pre>
     *
     * @param stackTrace the pinning stack trace (may be null/empty)
     * @return the taxonomy bucket; {@link #UNCLASSIFIED} when no rule matches
     */
    public static PinningTaxonomy classify(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return UNCLASSIFIED;
        }

        boolean sawNativeFrame = false;
        boolean sawMonitorEnter = false;

        for (String rawLine : stackTrace.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);

            // Foreign Function & Memory (Panama) downcall — strongest signal, return immediately.
            if (lower.contains("jdk.internal.foreign")
                    || lower.contains("java.lang.foreign")
                    || lower.contains("nativemethodhandle")
                    || lower.contains("downcallstub")
                    || lower.contains("invokenative")) {
                return FOREIGN_CALL;
            }

            boolean nativeHere = lower.contains("(native method)");
            if (nativeHere) {
                sawNativeFrame = true;
            }

            // Object-monitor entry frames (monitorenter / Object.wait / monitor reentry).
            if (lower.contains("monitorenter")
                    || lower.contains(".wait0")
                    || lower.contains("object.wait")
                    || lower.contains("synchronizer")
                    || lower.contains("objectmonitor")) {
                sawMonitorEnter = true;
                // A monitor frame that is itself native is the in-native-monitor case.
                if (nativeHere) {
                    return OBJECT_MONITOR_IN_NATIVE;
                }
            }
        }

        if (sawMonitorEnter && sawNativeFrame) {
            return OBJECT_MONITOR_IN_NATIVE;
        }
        if (sawNativeFrame) {
            return NATIVE_FRAME;
        }
        return UNCLASSIFIED;
    }

    /** Short human-readable label for reports. */
    public String label() {
        return switch (this) {
            case NATIVE_FRAME -> "native-frame";
            case FOREIGN_CALL -> "foreign-call";
            case OBJECT_MONITOR_IN_NATIVE -> "object-monitor-in-native";
            case UNCLASSIFIED -> "unclassified";
        };
    }
}
