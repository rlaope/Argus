package io.argus.cli.provider;

/**
 * Base interface for all diagnostic data providers.
 *
 * <p>Each provider has a priority (higher = preferred) and a source name
 * used for display and {@code --source} override matching.
 */
public interface DiagnosticProvider {

    /**
     * Checks if this provider can serve data for the given PID.
     */
    boolean isAvailable(long pid);

    /**
     * Priority for auto-detection. Higher values are preferred.
     * Agent=100, JFR=50, JDK=10.
     */
    int priority();

    /**
     * Source identifier: "agent", "jdk", or "jfr".
     */
    String source();
}
