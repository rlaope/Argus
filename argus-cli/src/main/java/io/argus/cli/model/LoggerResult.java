package io.argus.cli.model;

import java.util.List;

/**
 * Result of logger query — available loggers with current levels.
 */
public final class LoggerResult {

    private final List<LoggerInfo> loggers;
    private final String rawOutput;

    public LoggerResult(List<LoggerInfo> loggers, String rawOutput) {
        this.loggers = loggers;
        this.rawOutput = rawOutput;
    }

    public List<LoggerInfo> loggers() { return loggers; }
    public String rawOutput() { return rawOutput; }

    public static final class LoggerInfo {
        private final String name;
        private final String level;
        private final String source;

        public LoggerInfo(String name, String level, String source) {
            this.name = name;
            this.level = level;
            this.source = source;
        }

        public String name() { return name; }
        public String level() { return level; }
        public String source() { return source; }
    }
}
