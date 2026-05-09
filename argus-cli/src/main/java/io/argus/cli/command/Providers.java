package io.argus.cli.command;

import io.argus.cli.config.Messages;
import io.argus.cli.provider.DiagnosticProvider;

/**
 * Resolution helpers for {@link DiagnosticProvider} lookups.
 *
 * <p>Most CLI commands do the same thing on a missing provider: print the
 * localized {@code error.provider.none} message and bail out. This helper
 * folds that 5-line block into a single call that throws
 * {@link CommandExitException} with exit code 1.
 */
public final class Providers {

    private Providers() {}

    /**
     * Returns {@code provider}, or throws {@link CommandExitException}{@code (1)}
     * after printing the localized "no provider available" message to stderr
     * if {@code provider} is {@code null}.
     */
    public static <T extends DiagnosticProvider> T require(T provider, long pid, Messages messages) {
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            throw new CommandExitException(1);
        }
        return provider;
    }
}
