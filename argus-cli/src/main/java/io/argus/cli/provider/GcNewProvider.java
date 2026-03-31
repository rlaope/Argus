package io.argus.cli.provider;

import io.argus.cli.model.GcNewResult;

public interface GcNewProvider extends DiagnosticProvider {

    GcNewResult getGcNew(long pid);
}
