package io.argus.cli.model;

/**
 * GC utilization snapshot from jstat -gcutil.
 */
public final class GcUtilResult {
    private final double s0;       // Survivor 0 usage %
    private final double s1;       // Survivor 1 usage %
    private final double eden;     // Eden usage %
    private final double old;      // Old gen usage %
    private final double meta;     // Metaspace usage %
    private final double ccs;      // Compressed class space usage %
    private final long ygc;        // Young GC count
    private final double ygct;     // Young GC time (seconds)
    private final long fgc;        // Full GC count
    private final double fgct;     // Full GC time (seconds)
    private final double gct;      // Total GC time (seconds)

    public GcUtilResult(double s0, double s1, double eden, double old, double meta,
                        double ccs, long ygc, double ygct, long fgc, double fgct, double gct) {
        this.s0 = s0;
        this.s1 = s1;
        this.eden = eden;
        this.old = old;
        this.meta = meta;
        this.ccs = ccs;
        this.ygc = ygc;
        this.ygct = ygct;
        this.fgc = fgc;
        this.fgct = fgct;
        this.gct = gct;
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
}
