package io.argus.cli.provider;

import io.argus.cli.model.StringTableResult;

public interface StringTableProvider extends DiagnosticProvider {

    StringTableResult getStringTableInfo(long pid);
}
