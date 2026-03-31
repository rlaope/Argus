package io.argus.cli.model;

/**
 * Young generation GC detail from jstat -gcnew.
 */
public final class GcNewResult {
    private final double s0c;   // Survivor 0 capacity (KB)
    private final double s1c;   // Survivor 1 capacity (KB)
    private final double s0u;   // Survivor 0 used (KB)
    private final double s1u;   // Survivor 1 used (KB)
    private final int tt;       // Tenuring threshold
    private final int mtt;      // Max tenuring threshold
    private final double dss;   // Desired survivor size (KB)
    private final double ec;    // Eden capacity (KB)
    private final double eu;    // Eden used (KB)
    private final long ygc;     // Young GC count
    private final double ygct;  // Young GC time (seconds)

    public GcNewResult(double s0c, double s1c, double s0u, double s1u,
                       int tt, int mtt, double dss, double ec, double eu,
                       long ygc, double ygct) {
        this.s0c = s0c; this.s1c = s1c; this.s0u = s0u; this.s1u = s1u;
        this.tt = tt; this.mtt = mtt; this.dss = dss;
        this.ec = ec; this.eu = eu; this.ygc = ygc; this.ygct = ygct;
    }

    public double s0c() { return s0c; }
    public double s1c() { return s1c; }
    public double s0u() { return s0u; }
    public double s1u() { return s1u; }
    public int tt() { return tt; }
    public int mtt() { return mtt; }
    public double dss() { return dss; }
    public double ec() { return ec; }
    public double eu() { return eu; }
    public long ygc() { return ygc; }
    public double ygct() { return ygct; }
}
