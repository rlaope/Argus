package io.argus.cli.provider;

import io.argus.cli.model.PoolResult;

public interface PoolProvider extends DiagnosticProvider {

    PoolResult getPoolInfo(long pid);
}
