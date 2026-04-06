package io.argus.cli.provider;

import io.argus.cli.model.BuffersResult;

/**
 * Provider for NIO buffer pool statistics.
 */
public interface BuffersProvider extends DiagnosticProvider {
    BuffersResult getBuffers(long pid);
}
