package io.argus.cli.provider;

import io.argus.cli.provider.agent.AgentClient;
import io.argus.cli.provider.agent.AgentGcProvider;
import io.argus.cli.provider.agent.AgentHeapProvider;
import io.argus.cli.provider.agent.AgentThreadProvider;
import io.argus.cli.provider.jdk.AsProfProvider;
import io.argus.cli.provider.jdk.JdkBuffersProvider;
import io.argus.cli.provider.jdk.JdkClassLoaderProvider;
import io.argus.cli.provider.jdk.JdkClassStatProvider;
import io.argus.cli.provider.jdk.JdkCompilerProvider;
import io.argus.cli.provider.jdk.JdkDeadlockProvider;
import io.argus.cli.provider.jdk.JdkDynLibsProvider;
import io.argus.cli.provider.jdk.JdkEnvProvider;
import io.argus.cli.provider.jdk.JdkFinalizerProvider;
import io.argus.cli.provider.jdk.JdkGcAgeProvider;
import io.argus.cli.provider.jdk.JdkGcCauseProvider;
import io.argus.cli.provider.jdk.JdkGcNewProvider;
import io.argus.cli.provider.jdk.JdkGcProvider;
import io.argus.cli.provider.jdk.JdkGcUtilProvider;
import io.argus.cli.provider.jdk.JdkHeapDumpProvider;
import io.argus.cli.provider.jdk.JdkHeapProvider;
import io.argus.cli.provider.jdk.JdkHistoProvider;
import io.argus.cli.provider.jdk.JdkInfoProvider;
import io.argus.cli.provider.jdk.JdkJfrProvider;
import io.argus.cli.provider.jdk.JdkLoggerProvider;
import io.argus.cli.provider.jdk.JdkMetaspaceProvider;
import io.argus.cli.provider.jdk.JdkNmtProvider;
import io.argus.cli.provider.jdk.JdkPoolProvider;
import io.argus.cli.provider.jdk.JdkProcessProvider;
import io.argus.cli.provider.jdk.JdkSearchClassProvider;
import io.argus.cli.provider.jdk.JdkStringTableProvider;
import io.argus.cli.provider.jdk.JdkSymbolTableProvider;
import io.argus.cli.provider.jdk.JdkSysPropsProvider;
import io.argus.cli.provider.jdk.JdkThreadDumpProvider;
import io.argus.cli.provider.jdk.JdkThreadProvider;
import io.argus.cli.provider.jdk.JdkVmFlagProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry that holds all diagnostic providers and selects the best available
 * one for each capability type.
 *
 * <p>Selection rules:
 * <ol>
 *   <li>If {@code sourceOverride} is non-null and not {@code "auto"}, filter by source name.</li>
 *   <li>Filter by {@link DiagnosticProvider#isAvailable(long)}.</li>
 *   <li>Sort by {@link DiagnosticProvider#priority()} descending.</li>
 *   <li>Return first match, or {@code null} if none qualify.</li>
 * </ol>
 */
public final class ProviderRegistry {

    private final Map<Class<? extends DiagnosticProvider>, List<? extends DiagnosticProvider>> byCapability =
            new IdentityHashMap<>();

    /**
     * Creates a registry with all built-in JDK providers.
     * No agent providers are registered.
     */
    public ProviderRegistry() {
        registerJdkProviders();
    }

    /**
     * Creates a registry with JDK providers plus agent providers pointed at the given host/port.
     *
     * @param agentHost hostname of the Argus agent (e.g. "localhost")
     * @param agentPort port of the Argus agent (e.g. 9202)
     */
    public ProviderRegistry(String agentHost, int agentPort) {
        registerJdkProviders();
        registerAgentProviders(agentHost, agentPort);
    }

    /**
     * Returns the highest-priority available provider for the given capability, or {@code null}.
     *
     * @param capability     capability interface class (e.g. {@code HistoProvider.class})
     * @param pid            target process ID
     * @param sourceOverride "auto" or {@code null} for automatic selection; otherwise a source name
     */
    public <T extends DiagnosticProvider> T find(Class<T> capability, long pid, String sourceOverride) {
        @SuppressWarnings("unchecked")
        List<T> candidates = (List<T>) byCapability.get(capability);
        if (candidates == null) {
            return null;
        }
        return findBest(candidates, pid, sourceOverride);
    }

    /**
     * Convenience overload for capabilities where the PID is irrelevant (e.g. {@link ProcessProvider}).
     */
    public <T extends DiagnosticProvider> T find(Class<T> capability) {
        return find(capability, 0L, null);
    }

    private <T extends DiagnosticProvider> void register(Class<T> capability, T provider) {
        @SuppressWarnings("unchecked")
        List<T> list = (List<T>) byCapability.computeIfAbsent(capability, k -> new ArrayList<T>());
        list.add(provider);
    }

    private void registerJdkProviders() {
        register(HistoProvider.class, new JdkHistoProvider());
        register(ThreadProvider.class, new JdkThreadProvider());
        register(GcProvider.class, new JdkGcProvider());
        register(HeapProvider.class, new JdkHeapProvider());
        register(InfoProvider.class, new JdkInfoProvider());
        register(ProcessProvider.class, new JdkProcessProvider());
        register(GcUtilProvider.class, new JdkGcUtilProvider());
        register(SysPropsProvider.class, new JdkSysPropsProvider());
        register(VmFlagProvider.class, new JdkVmFlagProvider());
        register(NmtProvider.class, new JdkNmtProvider());
        register(ClassLoaderProvider.class, new JdkClassLoaderProvider());
        register(JfrProvider.class, new JdkJfrProvider());
        register(ProfileProvider.class, new AsProfProvider());
        register(HeapDumpProvider.class, new JdkHeapDumpProvider());
        register(DeadlockProvider.class, new JdkDeadlockProvider());
        register(EnvProvider.class, new JdkEnvProvider());
        register(CompilerProvider.class, new JdkCompilerProvider());
        register(FinalizerProvider.class, new JdkFinalizerProvider());
        register(StringTableProvider.class, new JdkStringTableProvider());
        register(PoolProvider.class, new JdkPoolProvider());
        register(GcCauseProvider.class, new JdkGcCauseProvider());
        register(MetaspaceProvider.class, new JdkMetaspaceProvider());
        register(DynLibsProvider.class, new JdkDynLibsProvider());
        register(ClassStatProvider.class, new JdkClassStatProvider());
        register(GcNewProvider.class, new JdkGcNewProvider());
        register(GcAgeProvider.class, new JdkGcAgeProvider());
        register(SymbolTableProvider.class, new JdkSymbolTableProvider());
        register(ThreadDumpProvider.class, new JdkThreadDumpProvider());
        register(BuffersProvider.class, new JdkBuffersProvider());
        register(LoggerProvider.class, new JdkLoggerProvider());
        register(SearchClassProvider.class, new JdkSearchClassProvider());
    }

    private void registerAgentProviders(String host, int port) {
        AgentClient client = new AgentClient(host, port);
        register(ThreadProvider.class, new AgentThreadProvider(client));
        register(GcProvider.class, new AgentGcProvider(client));
        register(HeapProvider.class, new AgentHeapProvider(client));
    }

    private <T extends DiagnosticProvider> T findBest(List<T> candidates, long pid, String sourceOverride) {
        boolean useOverride = sourceOverride != null && !sourceOverride.equalsIgnoreCase("auto");

        return candidates.stream()
                .filter(p -> !useOverride || p.source().equalsIgnoreCase(sourceOverride))
                .filter(p -> p.isAvailable(pid))
                .max(Comparator.comparingInt(DiagnosticProvider::priority))
                .orElse(null);
    }
}
