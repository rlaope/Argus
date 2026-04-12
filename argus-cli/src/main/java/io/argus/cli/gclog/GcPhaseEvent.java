package io.argus.cli.gclog;

/**
 * A single GC phase entry from JDK 17+ debug GC logs (-Xlog:gc*=debug).
 * Example source line:
 *   [0.234s][debug][gc,phases] GC(0) Pre Evacuate Collection Set: 0.1ms
 */
public final class GcPhaseEvent {
    private final int gcId;
    private final String phase;
    private final double durationMs;

    public GcPhaseEvent(int gcId, String phase, double durationMs) {
        this.gcId = gcId;
        this.phase = phase;
        this.durationMs = durationMs;
    }

    public int gcId() { return gcId; }
    public String phase() { return phase; }
    public double durationMs() { return durationMs; }
}
