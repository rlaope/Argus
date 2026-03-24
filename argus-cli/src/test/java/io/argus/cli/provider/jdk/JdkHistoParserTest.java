package io.argus.cli.provider.jdk;

import io.argus.cli.model.HistoResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdkHistoParserTest {

    private static final String SAMPLE_OUTPUT =
            " num     #instances         #bytes  class name (module)\n" +
            "-------------------------------------------------------\n" +
            "   1:        123456       12345678  [B (java.base@21)\n" +
            "   2:         98765        9876543  java.lang.String (java.base@21)\n" +
            "   3:          5000         500000  java.util.HashMap$Node (java.base@21)\n" +
            "Total        227221       22722221";

    @Test
    void parseOutput_typicalHistogramOutput() {
        HistoResult r = JdkHistoProvider.parseOutput(SAMPLE_OUTPUT, 10);
        assertEquals(3, r.entries().size());
        assertEquals(227221L,   r.totalInstances());
        assertEquals(22722221L, r.totalBytes());
    }

    @Test
    void parseOutput_firstEntryParsedCorrectly() {
        HistoResult r = JdkHistoProvider.parseOutput(SAMPLE_OUTPUT, 10);
        HistoResult.Entry e = r.entries().get(0);
        assertEquals(1,         e.rank());
        assertEquals("[B",      e.className());
        assertEquals(123456L,   e.instances());
        assertEquals(12345678L, e.bytes());
    }

    @Test
    void parseOutput_secondEntryStripsModuleInfo() {
        HistoResult r = JdkHistoProvider.parseOutput(SAMPLE_OUTPUT, 10);
        HistoResult.Entry e = r.entries().get(1);
        assertEquals(2,                  e.rank());
        assertEquals("java.lang.String", e.className());
        assertEquals(98765L,             e.instances());
        assertEquals(9876543L,           e.bytes());
    }

    @Test
    void parseOutput_thirdEntryStripsModuleInfo() {
        HistoResult r = JdkHistoProvider.parseOutput(SAMPLE_OUTPUT, 10);
        HistoResult.Entry e = r.entries().get(2);
        assertEquals("java.util.HashMap$Node", e.className());
    }

    @Test
    void parseOutput_topNLimitsEntries() {
        HistoResult r = JdkHistoProvider.parseOutput(SAMPLE_OUTPUT, 2);
        assertEquals(2, r.entries().size());
        // Total line is still parsed regardless of topN
        assertEquals(227221L, r.totalInstances());
    }

    @Test
    void parseOutput_nullReturnsEmpty() {
        HistoResult r = JdkHistoProvider.parseOutput(null, 10);
        assertTrue(r.entries().isEmpty());
        assertEquals(0L, r.totalInstances());
        assertEquals(0L, r.totalBytes());
    }

    @Test
    void parseOutput_blankReturnsEmpty() {
        HistoResult r = JdkHistoProvider.parseOutput("   ", 10);
        assertTrue(r.entries().isEmpty());
    }

    @Test
    void parseOutput_noSeparatorLineSkipsAllData() {
        // Without "---" separator, no entries should be parsed
        String noSep =
                " num     #instances         #bytes  class name\n" +
                "   1:        100         1000  [B\n" +
                "Total        100         1000";
        HistoResult r = JdkHistoProvider.parseOutput(noSep, 10);
        assertTrue(r.entries().isEmpty());
        // Total line is also behind pastHeader guard, so totals stay 0
        assertEquals(0L, r.totalInstances());
    }

    @Test
    void parseOutput_topNZeroReturnsNoEntries() {
        HistoResult r = JdkHistoProvider.parseOutput(SAMPLE_OUTPUT, 0);
        assertEquals(List.of(), r.entries());
        // Total line still parsed
        assertEquals(227221L, r.totalInstances());
    }
}
