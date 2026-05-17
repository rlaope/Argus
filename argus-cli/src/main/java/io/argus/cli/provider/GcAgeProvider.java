package io.argus.cli.provider;

import io.argus.diagnostics.model.AgeDistribution;

/**
 * Provides object age distribution data for young generation survivor spaces.
 */
public interface GcAgeProvider extends DiagnosticProvider {

    AgeDistribution getAgeDistribution(long pid);
}
