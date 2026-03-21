package io.argus.cli.provider;

import io.argus.cli.model.HistoResult;

public interface HistoProvider extends DiagnosticProvider {

    HistoResult getHistogram(long pid, int topN);
}
