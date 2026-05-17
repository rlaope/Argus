package io.argus.cli.provider.jdk;

import io.argus.cli.provider.NmtProvider;
import io.argus.diagnostics.model.NmtResult;

/**
 * CLI registry adapter that wraps the standalone
 * {@link io.argus.diagnostics.jcmd.JdkNmtProvider} so it can plug into
 * {@code ProviderRegistry} via the CLI's {@link NmtProvider} contract.
 */
public final class JdkNmtProvider implements NmtProvider {

    private final io.argus.diagnostics.jcmd.JdkNmtProvider delegate =
            new io.argus.diagnostics.jcmd.JdkNmtProvider();

    @Override
    public boolean isAvailable(long pid) {
        return delegate.isAvailable(pid);
    }

    @Override
    public int priority() {
        return delegate.priority();
    }

    @Override
    public String source() {
        return delegate.source();
    }

    @Override
    public NmtResult getNativeMemory(long pid) {
        return delegate.getNativeMemory(pid);
    }
}
