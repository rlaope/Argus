package io.argus.cli.provider;

import io.argus.cli.model.GcResult;

public interface GcProvider extends DiagnosticProvider {

    GcResult getGcInfo(long pid);
}
