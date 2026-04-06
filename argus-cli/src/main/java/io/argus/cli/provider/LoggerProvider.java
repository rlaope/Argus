package io.argus.cli.provider;

import io.argus.cli.model.LoggerResult;

/**
 * Provider for runtime logger inspection and control.
 */
public interface LoggerProvider extends DiagnosticProvider {
    LoggerResult listLoggers(long pid);
    String setLogLevel(long pid, String loggerName, String level);
}
