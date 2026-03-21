package io.argus.cli.provider;

import io.argus.cli.model.ProcessInfo;

import java.util.List;

public interface ProcessProvider extends DiagnosticProvider {

    List<ProcessInfo> listProcesses();
}
