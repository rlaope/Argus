package io.argus.cli.provider;

import io.argus.cli.provider.agent.AgentClient;
import io.argus.cli.provider.agent.AgentGcProvider;
import io.argus.cli.provider.agent.AgentHeapProvider;
import io.argus.cli.provider.agent.AgentThreadProvider;
import io.argus.cli.provider.jdk.JdkGcProvider;
import io.argus.cli.provider.jdk.JdkGcUtilProvider;
import io.argus.cli.provider.jdk.JdkHeapDumpProvider;
import io.argus.cli.provider.jdk.JdkHeapProvider;
import io.argus.cli.provider.jdk.JdkHistoProvider;
import io.argus.cli.provider.jdk.JdkInfoProvider;
import io.argus.cli.provider.jdk.JdkClassLoaderProvider;
import io.argus.cli.provider.jdk.AsProfProvider;
import io.argus.cli.provider.jdk.JdkJfrProvider;
import io.argus.cli.provider.jdk.JdkNmtProvider;
import io.argus.cli.provider.jdk.JdkProcessProvider;
import io.argus.cli.provider.jdk.JdkSysPropsProvider;
import io.argus.cli.provider.jdk.JdkThreadProvider;
import io.argus.cli.provider.jdk.JdkVmFlagProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    private final List<HistoProvider> histoProviders = new ArrayList<>();
    private final List<ThreadProvider> threadProviders = new ArrayList<>();
    private final List<GcProvider> gcProviders = new ArrayList<>();
    private final List<HeapProvider> heapProviders = new ArrayList<>();
    private final List<InfoProvider> infoProviders = new ArrayList<>();
    private final List<ProcessProvider> processProviders = new ArrayList<>();
    private final List<GcUtilProvider> gcUtilProviders = new ArrayList<>();
    private final List<SysPropsProvider> sysPropsProviders = new ArrayList<>();
    private final List<VmFlagProvider> vmFlagProviders = new ArrayList<>();
    private final List<NmtProvider> nmtProviders = new ArrayList<>();
    private final List<ClassLoaderProvider> classLoaderProviders = new ArrayList<>();
    private final List<JfrProvider> jfrProviders = new ArrayList<>();
    private final List<ProfileProvider> profileProviders = new ArrayList<>();
    private final List<HeapDumpProvider> heapDumpProviders = new ArrayList<>();

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

    // -------------------------------------------------------------------------
    // Provider registration
    // -------------------------------------------------------------------------

    private void registerJdkProviders() {
        histoProviders.add(new JdkHistoProvider());
        threadProviders.add(new JdkThreadProvider());
        gcProviders.add(new JdkGcProvider());
        heapProviders.add(new JdkHeapProvider());
        infoProviders.add(new JdkInfoProvider());
        processProviders.add(new JdkProcessProvider());
        gcUtilProviders.add(new JdkGcUtilProvider());
        sysPropsProviders.add(new JdkSysPropsProvider());
        vmFlagProviders.add(new JdkVmFlagProvider());
        nmtProviders.add(new JdkNmtProvider());
        classLoaderProviders.add(new JdkClassLoaderProvider());
        jfrProviders.add(new JdkJfrProvider());
        profileProviders.add(new AsProfProvider());
        heapDumpProviders.add(new JdkHeapDumpProvider());
    }

    private void registerAgentProviders(String host, int port) {
        AgentClient client = new AgentClient(host, port);
        threadProviders.add(new AgentThreadProvider(client));
        gcProviders.add(new AgentGcProvider(client));
        heapProviders.add(new AgentHeapProvider(client));
    }

    // -------------------------------------------------------------------------
    // Provider lookup
    // -------------------------------------------------------------------------

    /**
     * Finds the best HistoProvider for the given PID.
     *
     * @param pid            target process ID
     * @param sourceOverride "auto" or null for automatic selection; otherwise a source name
     * @return the best available provider, or {@code null} if none qualify
     */
    public HistoProvider findHistoProvider(long pid, String sourceOverride) {
        return findBest(histoProviders, pid, sourceOverride);
    }

    /**
     * Finds the best ThreadProvider for the given PID.
     */
    public ThreadProvider findThreadProvider(long pid, String sourceOverride) {
        return findBest(threadProviders, pid, sourceOverride);
    }

    /**
     * Finds the best GcProvider for the given PID.
     */
    public GcProvider findGcProvider(long pid, String sourceOverride) {
        return findBest(gcProviders, pid, sourceOverride);
    }

    /**
     * Finds the best HeapProvider for the given PID.
     */
    public HeapProvider findHeapProvider(long pid, String sourceOverride) {
        return findBest(heapProviders, pid, sourceOverride);
    }

    /**
     * Finds the best InfoProvider for the given PID.
     */
    public InfoProvider findInfoProvider(long pid, String sourceOverride) {
        return findBest(infoProviders, pid, sourceOverride);
    }

    /**
     * Finds the best ProcessProvider. PID is irrelevant for process listing.
     */
    public ProcessProvider findProcessProvider() {
        return findBest(processProviders, 0L, null);
    }

    /**
     * Finds the best GcUtilProvider for the given PID.
     */
    public GcUtilProvider findGcUtilProvider(long pid, String sourceOverride) {
        return findBest(gcUtilProviders, pid, sourceOverride);
    }

    /**
     * Finds the best SysPropsProvider for the given PID.
     */
    public SysPropsProvider findSysPropsProvider(long pid, String sourceOverride) {
        return findBest(sysPropsProviders, pid, sourceOverride);
    }

    /**
     * Finds the best VmFlagProvider for the given PID.
     */
    public VmFlagProvider findVmFlagProvider(long pid, String sourceOverride) {
        return findBest(vmFlagProviders, pid, sourceOverride);
    }

    /**
     * Finds the best NmtProvider for the given PID.
     */
    public NmtProvider findNmtProvider(long pid, String sourceOverride) {
        return findBest(nmtProviders, pid, sourceOverride);
    }

    /**
     * Finds the best ClassLoaderProvider for the given PID.
     */
    public ClassLoaderProvider findClassLoaderProvider(long pid, String sourceOverride) {
        return findBest(classLoaderProviders, pid, sourceOverride);
    }

    /**
     * Finds the best JfrProvider for the given PID.
     */
    public JfrProvider findJfrProvider(long pid, String sourceOverride) {
        return findBest(jfrProviders, pid, sourceOverride);
    }

    /**
     * Finds the best ProfileProvider for the given PID.
     */
    public ProfileProvider findProfileProvider(long pid, String sourceOverride) {
        return findBest(profileProviders, pid, sourceOverride);
    }

    /**
     * Finds the best HeapDumpProvider for the given PID.
     */
    public HeapDumpProvider findHeapDumpProvider(long pid, String sourceOverride) {
        return findBest(heapDumpProviders, pid, sourceOverride);
    }

    // -------------------------------------------------------------------------
    // Generic selection logic
    // -------------------------------------------------------------------------

    private <T extends DiagnosticProvider> T findBest(List<T> candidates, long pid, String sourceOverride) {
        boolean useOverride = sourceOverride != null && !sourceOverride.equalsIgnoreCase("auto");

        return candidates.stream()
                .filter(p -> !useOverride || p.source().equalsIgnoreCase(sourceOverride))
                .filter(p -> p.isAvailable(pid))
                .max(Comparator.comparingInt(DiagnosticProvider::priority))
                .orElse(null);
    }
}
