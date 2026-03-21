package io.argus.cli.model;

/**
 * GC utilization snapshot from jstat -gcutil.
 */
public record GcUtilResult(
        double s0,       // Survivor 0 usage %
        double s1,       // Survivor 1 usage %
        double eden,     // Eden usage %
        double old,      // Old gen usage %
        double meta,     // Metaspace usage %
        double ccs,      // Compressed class space usage %
        long ygc,        // Young GC count
        double ygct,     // Young GC time (seconds)
        long fgc,        // Full GC count
        double fgct,     // Full GC time (seconds)
        double gct        // Total GC time (seconds)
) {}
