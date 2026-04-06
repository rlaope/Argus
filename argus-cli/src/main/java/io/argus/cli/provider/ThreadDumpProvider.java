package io.argus.cli.provider;

import io.argus.cli.model.ThreadDumpResult;

/**
 * Provider for full thread dump capture.
 */
public interface ThreadDumpProvider extends DiagnosticProvider {
    ThreadDumpResult dumpThreads(long pid);
}
