package io.argus.cli.provider;

import io.argus.cli.model.GcUtilResult;

public interface GcUtilProvider extends DiagnosticProvider {

    GcUtilResult getGcUtil(long pid);
}
