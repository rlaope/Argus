package io.argus.cli.provider;

import io.argus.cli.model.JfrResult;

public interface JfrProvider extends DiagnosticProvider {

    JfrResult startRecording(long pid, int durationSec, String filename);

    JfrResult stopRecording(long pid);

    JfrResult checkRecording(long pid);

    JfrResult dumpRecording(long pid, String filename);
}
