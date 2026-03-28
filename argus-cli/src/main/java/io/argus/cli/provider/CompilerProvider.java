package io.argus.cli.provider;

import io.argus.cli.model.CompilerResult;

public interface CompilerProvider extends DiagnosticProvider {

    CompilerResult getCompilerInfo(long pid);
}
