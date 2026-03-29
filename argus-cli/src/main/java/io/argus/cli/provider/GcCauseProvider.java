package io.argus.cli.provider;

import io.argus.cli.model.GcCauseResult;

public interface GcCauseProvider extends DiagnosticProvider {

    GcCauseResult getGcCause(long pid);
}
