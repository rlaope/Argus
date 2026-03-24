package io.argus.cli.provider.jdk;

import io.argus.cli.model.GcUtilResult;
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
}
