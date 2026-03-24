package io.argus.cli.provider;

import io.argus.cli.model.VmFlagResult;

public interface VmFlagProvider extends DiagnosticProvider {

    VmFlagResult getVmFlags(long pid);

    String setVmFlag(long pid, String flag, String value);
}
