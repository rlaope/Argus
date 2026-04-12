package io.argus.cli.gclog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GcPhaseAnalyzerTest {

    @TempDir Path tempDir;

    @Test
    void parsePhaseEvents_fromDebugLog() throws IOException {
        String log = """
                [0.234s][debug][gc,phases] GC(0) Pre Evacuate Collection Set: 0.1ms
                [0.234s][debug][gc,phases] GC(0) Merge Heap Roots: 0.3ms
                [0.234s][debug][gc,phases] GC(0) Evacuate Collection Set: 5.2ms
                [0.234s][debug][gc,phases] GC(0) Post Evacuate Collection Set: 1.1ms
                [0.234s][debug][gc,phases] GC(0) Other: 0.8ms
                [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 7.500ms
                """;
        Path file = tempDir.resolve("phases.log");
        Files.writeString(file, log);

        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(file);
        List<GcPhaseEvent> phases = result.phases();

        assertEquals(5, phases.size());
        assertEquals(0, phases.get(0).gcId());
        assertEquals("Pre Evacuate Collection Set", phases.get(0).phase());
        assertEquals(0.1, phases.get(0).durationMs(), 0.001);

        assertEquals("Evacuate Collection Set", phases.get(2).phase());
        assertEquals(5.2, phases.get(2).durationMs(), 0.001);
    }

    @Test
    void parsePhaseEvents_multipleGcCycles() throws IOException {
        String log = """
                [0.234s][debug][gc,phases] GC(0) Evacuate Collection Set: 5.2ms
                [0.234s][debug][gc,phases] GC(0) Other: 0.8ms
                [0.500s][debug][gc,phases] GC(1) Evacuate Collection Set: 7.4ms
                [0.500s][debug][gc,phases] GC(1) Other: 1.2ms
                [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 6.0ms
                [0.500s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 32M->10M(256M) 8.6ms
                """;
        Path file = tempDir.resolve("multi.log");
        Files.writeString(file, log);

        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(file);
        GcPhaseAnalyzer.PhaseAnalysis analysis = GcPhaseAnalyzer.analyze(result.phases());

        assertEquals(2, analysis.gcCount());
        assertEquals(2, analysis.phases().size());

        // "Evacuate Collection Set" avg = (5.2 + 7.4) / 2 = 6.3ms
        GcPhaseAnalyzer.PhaseStat evacuate = analysis.phases().stream()
                .filter(p -> p.phase().equals("Evacuate Collection Set"))
                .findFirst().orElseThrow();
        assertEquals(6.3, evacuate.avgMs(), 0.01);
        assertEquals(7.4, evacuate.maxMs(), 0.01);
    }

    @Test
    void percentageCalculation_sumsTo100() throws IOException {
        String log = """
                [0.234s][debug][gc,phases] GC(0) Phase A: 3.0ms
                [0.234s][debug][gc,phases] GC(0) Phase B: 7.0ms
                [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 10.0ms
                """;
        Path file = tempDir.resolve("pct.log");
        Files.writeString(file, log);

        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(file);
        GcPhaseAnalyzer.PhaseAnalysis analysis = GcPhaseAnalyzer.analyze(result.phases());

        double totalPct = analysis.phases().stream()
                .mapToDouble(GcPhaseAnalyzer.PhaseStat::percentOfTotal)
                .sum();
        assertEquals(100.0, totalPct, 0.01);

        GcPhaseAnalyzer.PhaseStat phaseA = analysis.phases().get(0);
        assertEquals(30.0, phaseA.percentOfTotal(), 0.01);

        GcPhaseAnalyzer.PhaseStat phaseB = analysis.phases().get(1);
        assertEquals(70.0, phaseB.percentOfTotal(), 0.01);
    }

    @Test
    void emptyPhaseList_returnsEmptyAnalysis() {
        GcPhaseAnalyzer.PhaseAnalysis analysis = GcPhaseAnalyzer.analyze(List.of());
        assertTrue(analysis.phases().isEmpty());
        assertEquals(0, analysis.gcCount());
    }

    @Test
    void nonPhaseLines_notParsedAsPhases() throws IOException {
        String log = """
                [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 7.500ms
                [0.235s][info][gc,heap] GC(0) Eden regions: 10->0(20)
                """;
        Path file = tempDir.resolve("nophases.log");
        Files.writeString(file, log);

        GcLogParser.ParseResult result = GcLogParser.parseWithPhases(file);
        assertTrue(result.phases().isEmpty());
        assertEquals(1, result.events().size());
    }
}
