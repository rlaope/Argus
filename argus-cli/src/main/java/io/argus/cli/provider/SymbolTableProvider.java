package io.argus.cli.provider;

import io.argus.cli.model.SymbolTableResult;

public interface SymbolTableProvider extends DiagnosticProvider {

    SymbolTableResult getSymbolTableInfo(long pid);
}
