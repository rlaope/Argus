package io.argus.cli.model;

/**
 * GC cause result from jstat -gccause. Extends gcutil with last/current GC cause.
 */
public final class GcCauseResult {
    private final double s0;
    private final double s1;
    private final double eden;
    private final double old;
    private final double meta;
    private final double ccs;
    private final long ygc;
    private final double ygct;
    private final long fgc;
    private final double fgct;
    private final double gct;
    private final String lastGcCause;
    private final String currentGcCause;

    public GcCauseResult(double s0, double s1, double eden, double old, double meta,
                         double ccs, long ygc, double ygct, long fgc, double fgct, double gct,
                         String lastGcCause, String currentGcCause) {
        this.s0 = s0; this.s1 = s1; this.eden = eden; this.old = old;
        this.meta = meta; this.ccs = ccs; this.ygc = ygc; this.ygct = ygct;
        this.fgc = fgc; this.fgct = fgct; this.gct = gct;
        this.lastGcCause = lastGcCause; this.currentGcCause = currentGcCause;
    }

    public double s0() { return s0; }
    public double s1() { return s1; }
    public double eden() { return eden; }
    public double old() { return old; }
    public double meta() { return meta; }
    public double ccs() { return ccs; }
    public long ygc() { return ygc; }
    public double ygct() { return ygct; }
    public long fgc() { return fgc; }
    public double fgct() { return fgct; }
    public double gct() { return gct; }
    public String lastGcCause() { return lastGcCause; }
    public String currentGcCause() { return currentGcCause; }
}
