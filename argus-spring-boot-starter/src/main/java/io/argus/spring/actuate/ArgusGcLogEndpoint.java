package io.argus.spring.actuate;

import io.argus.diagnostics.gclog.GcLogAnalysis;
import io.argus.spring.ArgusProperties;
import io.argus.spring.diagnostics.GcLogAnalyzerService;
import io.argus.spring.diagnostics.GcScoreService;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot Actuator endpoint exposing GC log analysis.
 *
 * <p>HTTP path: {@code /actuator/argus-gc}. Reads the GC log path from
 * {@code argus.doctor.gc-log-path}; if unset, returns a status sentinel
 * instead of failing.
 */
@Endpoint(id = "argus-gc")
public class ArgusGcLogEndpoint {

    private final GcLogAnalyzerService analyzer;
    private final GcScoreService scorer;
    private final ArgusProperties properties;

    public ArgusGcLogEndpoint(GcLogAnalyzerService analyzer,
                              GcScoreService scorer,
                              ArgusProperties properties) {
        this.analyzer = analyzer;
        this.scorer = scorer;
        this.properties = properties;
    }

    /**
     * Parse the configured GC log path and return aggregated statistics
     * plus an overall GC health score.
     *
     * <p>HTTP: {@code GET /actuator/argus-gc}
     */
    @ReadOperation
    public Map<String, Object> analyze() {
        String configuredPath = properties.getDoctor().getGcLogPath();
        Map<String, Object> response = new LinkedHashMap<>();

        if (configuredPath == null || configuredPath.isBlank()) {
            response.put("status", "no_log_configured");
            response.put("hint", "Set argus.doctor.gc-log-path=<path-to-gc.log>");
            return response;
        }

        Path path = Path.of(configuredPath);
        if (!Files.exists(path)) {
            response.put("status", "log_not_found");
            response.put("path", configuredPath);
            return response;
        }

        try {
            GcLogAnalysis analysis = analyzer.analyze(path);
            response.put("status", "ok");
            response.put("path", configuredPath);
            response.put("analysis", toMap(analysis));
            response.put("score", toScoreMap(scorer.score(analysis)));
            return response;
        } catch (IOException e) {
            response.put("status", "parse_error");
            response.put("path", configuredPath);
            response.put("error", e.getMessage());
            return response;
        }
    }

    private Map<String, Object> toMap(GcLogAnalysis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalEvents", a.totalEvents());
        m.put("pauseEvents", a.pauseEvents());
        m.put("fullGcEvents", a.fullGcEvents());
        m.put("concurrentEvents", a.concurrentEvents());
        m.put("durationSec", a.durationSec());
        m.put("throughputPercent", a.throughputPercent());
        m.put("totalPauseMs", a.totalPauseMs());
        m.put("maxPauseMs", a.maxPauseMs());
        m.put("p50PauseMs", a.p50PauseMs());
        m.put("p95PauseMs", a.p95PauseMs());
        m.put("p99PauseMs", a.p99PauseMs());
        m.put("avgPauseMs", a.avgPauseMs());
        m.put("peakHeapKB", a.peakHeapKB());
        m.put("avgHeapAfterKB", a.avgHeapAfterKB());
        return m;
    }

    private Map<String, Object> toScoreMap(io.argus.diagnostics.gcscore.GcScoreResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overall", r.overall());
        m.put("grade", r.grade());
        m.put("summary", r.summary());
        m.put("hints", r.hints());
        return m;
    }
}
