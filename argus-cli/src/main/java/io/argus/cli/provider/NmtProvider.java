package io.argus.cli.provider;

import io.argus.cli.model.NmtResult;

public interface NmtProvider extends DiagnosticProvider {

    NmtResult getNativeMemory(long pid);
}
