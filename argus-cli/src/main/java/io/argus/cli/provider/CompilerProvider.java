package io.argus.cli.provider;

import io.argus.diagnostics.model.CompilerResult;

public interface CompilerProvider extends DiagnosticProvider {

    CompilerResult getCompilerInfo(long pid);
}
