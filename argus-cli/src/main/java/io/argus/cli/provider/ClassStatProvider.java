package io.argus.cli.provider;

import io.argus.cli.model.ClassStatResult;

public interface ClassStatProvider extends DiagnosticProvider {

    ClassStatResult getClassStats(long pid);
}
