package io.argus.diagnostics.jcmd;

import io.argus.diagnostics.model.GcUtilResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdkGcUtilParserTest {

    private static final String SAMPLE_OUTPUT =
            "  S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT     GCT   \n" +
            " 12.50   0.00  67.32  58.12  99.21  95.44   1234    0.567     5    0.323    0.890";

    @Test
    void parseOutput_typicalJstatOutput() {
        GcUtilResult r = JdkGcUtilProvider.parseOutput(SAMPLE_OUTPUT);
        assertEquals(12.50, r.s0(),   0.001);
        assertEquals(0.00,  r.s1(),   0.001);
        assertEquals(67.32, r.eden(), 0.001);
        assertEquals(58.12, r.old(),  0.001);
        assertEquals(99.21, r.meta(), 0.001);
        assertEquals(95.44, r.ccs(),  0.001);
        assertEquals(1234,  r.ygc());
        assertEquals(0.567, r.ygct(), 0.001);
        assertEquals(5,     r.fgc());
        assertEquals(0.323, r.fgct(), 0.001);
        assertEquals(0.890, r.gct(),  0.001);
    }

    @Test
    void parseOutput_nullReturnsEmpty() {
        GcUtilResult r = JdkGcUtilProvider.parseOutput(null);
        assertEquals(0, r.ygc());
        assertEquals(0, r.fgc());
        assertEquals(0.0, r.gct(), 0.001);
    }

    @Test
    void parseOutput_blankReturnsEmpty() {
        GcUtilResult r = JdkGcUtilProvider.parseOutput("   ");
        assertEquals(0, r.ygc());
    }

    @Test
    void parseOutput_onlyHeaderLineReturnsEmpty() {
        String onlyHeader = "  S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT     GCT";
        GcUtilResult r = JdkGcUtilProvider.parseOutput(onlyHeader);
        assertEquals(0, r.ygc());
    }

    @Test
    void parseOutput_insufficientColumnsReturnsEmpty() {
        String bad = "header\n 1.0 2.0 3.0";
        GcUtilResult r = JdkGcUtilProvider.parseOutput(bad);
        assertEquals(0, r.ygc());
    }

    @Test
    void parseOutput_zeroValues() {
        String zeros = "header\n 0.00 0.00 0.00 0.00 0.00 0.00 0 0.000 0 0.000 0.000";
        GcUtilResult r = JdkGcUtilProvider.parseOutput(zeros);
        assertEquals(0.0, r.s0(), 0.001);
        assertEquals(0,   r.ygc());
        assertEquals(0,   r.fgc());
    }

    /**
     * JDK 21 with concurrent GC inserts CGC and CGCT columns between FGCT and GCT,
     * shifting GCT from index 10 to index 12. The header-driven parser must still
     * read GCT correctly and must not mistake CGCT for GCT.
     */
    @Test
    void parseOutput_jdk21WithConcurrentGcColumns() {
        String jdk21Output =
                "  S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT    CGC    CGCT     GCT   LGCC GCC\n" +
                "  0.00   0.00  12.34  45.67  98.10  90.11     3    0.123     1    0.045     8    0.067    0.235 Allocation Failure G1 Young Generation";
        GcUtilResult r = JdkGcUtilProvider.parseOutput(jdk21Output);
        assertEquals(0.00,  r.s0(),   0.001);
        assertEquals(12.34, r.eden(), 0.001);
        assertEquals(45.67, r.old(),  0.001);
        assertEquals(3,     r.ygc());
        assertEquals(0.123, r.ygct(), 0.001);
        assertEquals(1,     r.fgc());
        assertEquals(0.045, r.fgct(), 0.001);
        // GCT must be 0.235, not CGCT (0.067) and not the shifted wrong value
        assertEquals(0.235, r.gct(),  0.001);
    }
}
