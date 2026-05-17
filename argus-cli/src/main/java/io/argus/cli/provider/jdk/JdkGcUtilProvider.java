package io.argus.cli.provider.jdk;

import io.argus.cli.provider.GcUtilProvider;
import io.argus.diagnostics.model.GcUtilResult;

/**
 * CLI registry adapter that wraps the standalone
 * {@link io.argus.diagnostics.jcmd.JdkGcUtilProvider} so it can plug into
 * {@code ProviderRegistry} via the CLI's {@link GcUtilProvider} contract.
 */
public final class JdkGcUtilProvider implements GcUtilProvider {

    private final io.argus.diagnostics.jcmd.JdkGcUtilProvider delegate =
            new io.argus.diagnostics.jcmd.JdkGcUtilProvider();

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
    public GcUtilResult getGcUtil(long pid) {
        return delegate.getGcUtil(pid);
    }
}
