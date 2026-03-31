package io.argus.cli.model;

import java.util.List;

/**
 * VM unified logging configuration.
 */
public final class VmLogResult {
    private final String rawOutput;
    private final List<String> logConfigs;

    public VmLogResult(String rawOutput, List<String> logConfigs) {
        this.rawOutput = rawOutput;
        this.logConfigs = logConfigs;
    }

    public String rawOutput() { return rawOutput; }
    public List<String> logConfigs() { return logConfigs; }
}
