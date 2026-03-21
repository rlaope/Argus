package io.argus.cli.provider;

import io.argus.cli.model.InfoResult;

public interface InfoProvider extends DiagnosticProvider {

    InfoResult getVmInfo(long pid);
}
