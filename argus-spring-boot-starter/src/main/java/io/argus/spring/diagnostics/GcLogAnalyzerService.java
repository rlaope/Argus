package io.argus.spring.diagnostics;

import io.argus.diagnostics.gclog.GcEvent;
import io.argus.diagnostics.gclog.GcLogAnalysis;
import io.argus.diagnostics.gclog.GcLogAnalyzer;
import io.argus.diagnostics.gclog.GcLogParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Parse a GC log and return aggregated analysis statistics.
 *
 * <p>Thin Spring-injectable wrapper around the static facades
 * {@link GcLogParser} and {@link GcLogAnalyzer}.
 */
public class GcLogAnalyzerService {

    /**
     * Parse and analyze a GC log file in one call.
     *
     * @param gcLogPath absolute or relative path to a GC log produced by
     *                  {@code -Xlog:gc*:file=...} or legacy {@code -Xloggc=...}
     */
    public GcLogAnalysis analyze(Path gcLogPath) throws IOException {
        List<GcEvent> events = GcLogParser.parse(gcLogPath);
        return GcLogAnalyzer.analyze(events);
    }

    /**
     * Analyze an already-parsed event list (e.g. from a custom collector).
     */
    public GcLogAnalysis analyze(List<GcEvent> events) {
        return GcLogAnalyzer.analyze(events);
    }
}
