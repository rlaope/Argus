package io.argus.cli.provider;

import io.argus.cli.model.HeapResult;

public interface HeapProvider extends DiagnosticProvider {

    HeapResult getHeapInfo(long pid);
}
