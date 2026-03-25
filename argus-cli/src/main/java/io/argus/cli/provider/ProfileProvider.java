package io.argus.cli.provider;

import io.argus.cli.model.ProfileResult;

public interface ProfileProvider extends DiagnosticProvider {
    ProfileResult profile(long pid, String type, int durationSec);
    ProfileResult flameGraph(long pid, String type, int durationSec, String outputFile);
}
