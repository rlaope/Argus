package io.argus.cli.provider;

import io.argus.cli.model.FinalizerResult;

public interface FinalizerProvider extends DiagnosticProvider {

    FinalizerResult getFinalizerInfo(long pid);
}
