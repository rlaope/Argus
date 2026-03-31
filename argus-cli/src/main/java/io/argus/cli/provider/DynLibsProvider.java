package io.argus.cli.provider;

import io.argus.cli.model.DynLibsResult;

public interface DynLibsProvider extends DiagnosticProvider {

    DynLibsResult getDynLibs(long pid);
}
