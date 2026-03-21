package io.argus.cli.provider;

import io.argus.cli.model.ThreadResult;

public interface ThreadProvider extends DiagnosticProvider {

    ThreadResult getThreadDump(long pid);
}
