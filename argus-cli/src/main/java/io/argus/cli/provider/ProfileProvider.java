package io.argus.cli.provider;

import io.argus.cli.model.ProfileResult;
import io.argus.cli.provider.jdk.AsProfOptions;

public interface ProfileProvider extends DiagnosticProvider {
    ProfileResult profile(long pid, String type, int durationSec);
    ProfileResult flameGraph(long pid, String type, int durationSec, String outputFile);

    // Session-mode operations: start/stop/dump/status map directly to asprof subcommands.
    ProfileResult start(long pid, String type);
    ProfileResult stop(long pid, String outputFile, String outputFormat);
    ProfileResult dump(long pid, String outputFile, String outputFormat);
    ProfileResult status(long pid);

    // Advanced overloads that pass AsProfOptions through to the underlying tool.
    // Default implementations delegate to the no-options variants for backward compat.
    default ProfileResult profile(long pid, String type, int durationSec, AsProfOptions opts) {
        return profile(pid, type, durationSec);
    }
    default ProfileResult flameGraph(long pid, String type, int durationSec,
                                     String outputFile, AsProfOptions opts) {
        return flameGraph(pid, type, durationSec, outputFile);
    }
    default ProfileResult start(long pid, String type, AsProfOptions opts) {
        return start(pid, type);
    }
}
