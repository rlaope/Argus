package io.argus.cli.provider.jdk;

import io.argus.cli.model.GcCauseResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdkGcCauseProviderTest {

    // Newer JDK format with CGC/CGCT columns; multi-word LGCC, GCC = "No GC"
    private static final String SAMPLE_G1_NO_GC =
            "  S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT    CGC    CGCT     GCT    LGCC                 GCC\n" +
            "  0.00   0.00   0.00  98.70  57.20   7.00  30312   4.880     0   0.000     0   0.000    4.880 G1 Humongous Allocation No GC";

    @Test
    void parseOutput_multiWordLgcc_gccIsNoGc() {
        GcCauseResult r = JdkGcCauseProvider.parseOutput(SAMPLE_G1_NO_GC);
        assertEquals("G1 Humongous Allocation", r.lastGcCause());
        assertEquals("No GC", r.currentGcCause());
    }

    @Test
    void parseOutput_numericFieldsCorrect() {
        GcCauseResult r = JdkGcCauseProvider.parseOutput(SAMPLE_G1_NO_GC);
        assertEquals(0.00,   r.s0(),   0.001);
        assertEquals(0.00,   r.s1(),   0.001);
        assertEquals(0.00,   r.eden(), 0.001);
        assertEquals(98.70,  r.old(),  0.001);
        assertEquals(57.20,  r.meta(), 0.001);
        assertEquals(7.00,   r.ccs(),  0.001);
        assertEquals(30312,  r.ygc());
        assertEquals(4.880,  r.ygct(), 0.001);
        assertEquals(0,      r.fgc());
        assertEquals(0.000,  r.fgct(), 0.001);
        assertEquals(4.880,  r.gct(),  0.001);
    }

    // Older JDK format without CGC/CGCT; single-word causes
    @Test
    void parseOutput_olderFormat_singleWordCauses() {
        String output =
                "  S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT     GCT    LGCC                 GCC\n" +
                "  0.00   0.00  45.00  60.00  95.00  88.00    100   1.234     2   0.050    1.284 Ergonomics No GC";
        GcCauseResult r = JdkGcCauseProvider.parseOutput(output);
        assertEquals("Ergonomics", r.lastGcCause());
        assertEquals("No GC", r.currentGcCause());
    }

    // GC in progress: LGCC == GCC, both multi-word
    @Test
    void parseOutput_gcInProgress_lgccEqualsGcc() {
        String output =
                "  S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT    CGC    CGCT     GCT    LGCC                 GCC\n" +
                "  0.00  50.00  80.00  60.00  95.00  88.00    101   1.300     2   0.050     5   0.020    1.370 Allocation Failure  Allocation Failure";
        GcCauseResult r = JdkGcCauseProvider.parseOutput(output);
        assertEquals("Allocation Failure", r.lastGcCause());
        assertEquals("Allocation Failure", r.currentGcCause());
    }

    @Test
    void parseOutput_nullReturnsEmpty() {
        GcCauseResult r = JdkGcCauseProvider.parseOutput(null);
        assertEquals("No GC", r.lastGcCause());
        assertEquals("No GC", r.currentGcCause());
    }

    @Test
    void parseOutput_blankReturnsEmpty() {
        GcCauseResult r = JdkGcCauseProvider.parseOutput("   ");
        assertEquals("No GC", r.lastGcCause());
        assertEquals("No GC", r.currentGcCause());
    }

    @Test
    void parseOutput_onlyHeaderReturnsEmpty() {
        String onlyHeader =
                "  S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT    CGC    CGCT     GCT    LGCC GCC";
        GcCauseResult r = JdkGcCauseProvider.parseOutput(onlyHeader);
        assertEquals("No GC", r.lastGcCause());
        assertEquals("No GC", r.currentGcCause());
    }
}
