package io.argus.cli.provider;

import io.argus.cli.model.MetaspaceResult;

public interface MetaspaceProvider extends DiagnosticProvider {

    MetaspaceResult getMetaspaceInfo(long pid);
}
