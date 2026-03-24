package io.argus.cli.provider;

import io.argus.cli.model.SysPropsResult;

public interface SysPropsProvider extends DiagnosticProvider {

    SysPropsResult getSystemProperties(long pid);
}
