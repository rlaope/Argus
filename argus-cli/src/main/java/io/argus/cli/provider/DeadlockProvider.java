package io.argus.cli.provider;

import io.argus.cli.model.DeadlockResult;

public interface DeadlockProvider extends DiagnosticProvider {

    DeadlockResult detectDeadlocks(long pid);
}
