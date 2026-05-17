package io.argus.cli.provider.jdk;

import io.argus.cli.provider.CompilerProvider;
import io.argus.diagnostics.model.CompilerResult;

/**
 * CLI registry adapter that wraps the standalone
 * {@link io.argus.diagnostics.jcmd.JdkCompilerProvider} so it can plug into
 * {@code ProviderRegistry} via the CLI's {@link CompilerProvider} contract.
 */
public final class JdkCompilerProvider implements CompilerProvider {

    private final io.argus.diagnostics.jcmd.JdkCompilerProvider delegate =
            new io.argus.diagnostics.jcmd.JdkCompilerProvider();

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
    public CompilerResult getCompilerInfo(long pid) {
        return delegate.getCompilerInfo(pid);
    }
}
