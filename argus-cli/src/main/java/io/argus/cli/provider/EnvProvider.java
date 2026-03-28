package io.argus.cli.provider;

import io.argus.cli.model.EnvResult;

public interface EnvProvider extends DiagnosticProvider {

    EnvResult getEnv(long pid);
}
