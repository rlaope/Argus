package io.argus.server.analysis;

import io.argus.core.event.ExecutionSampleEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Analyzes execution sample events for CPU profiling.
 *
 * <p>Tracks hot methods and provides method profiling statistics.
 */
public final class MethodProfilingAnalyzer {

    private static final int TOP_METHODS_LIMIT = 20;

    private final AtomicLong totalSamples = new AtomicLong(0);
    private final Map<String, AtomicLong> methodSampleCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> packageSampleCounts = new ConcurrentHashMap<>();

    /**
     * Records an execution sample event for analysis.
     *
     * @param event the execution sample event to record
     */
    public void recordExecutionSample(ExecutionSampleEvent event) {
        if (event == null) {
            return;
        }

        totalSamples.incrementAndGet();

        // Track by fully qualified method name
        String methodKey = event.fullyQualifiedMethod();
        methodSampleCounts.computeIfAbsent(methodKey, k -> new AtomicLong()).incrementAndGet();

        // Track by package
        String packageName = event.packageName();
        if (packageName != null && !packageName.isEmpty()) {
            packageSampleCounts.computeIfAbsent(packageName, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /**
     * Returns the method profiling analysis results.
     *
     * @return the profiling analysis result
     */
    public MethodProfilingResult getAnalysis() {
        long total = totalSamples.get();

        // Get top methods
        List<HotMethod> topMethods = methodSampleCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(TOP_METHODS_LIMIT)
                .map(e -> {
                    String fullMethod = e.getKey();
                    long count = e.getValue().get();
                    double percentage = total > 0 ? (count * 100.0) / total : 0;

                    // Parse class and method name
                    int lastDot = fullMethod.lastIndexOf('.');
                    String className = lastDot > 0 ? fullMethod.substring(0, lastDot) : "Unknown";
                    String methodName = lastDot > 0 ? fullMethod.substring(lastDot + 1) : fullMethod;

                    return new HotMethod(className, methodName, count, percentage);
                })
                .toList();

        // Build package distribution
        Map<String, Long> packageDistribution = new ConcurrentHashMap<>();
        packageSampleCounts.forEach((pkg, count) -> packageDistribution.put(pkg, count.get()));

        return new MethodProfilingResult(total, topMethods, packageDistribution);
    }

    /**
     * Returns the top hot methods.
     *
     * @param limit maximum number of methods to return
     * @return list of hot methods
     */
    public List<HotMethod> getTopMethods(int limit) {
        long total = totalSamples.get();

        return methodSampleCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(limit)
                .map(e -> {
                    String fullMethod = e.getKey();
                    long count = e.getValue().get();
                    double percentage = total > 0 ? (count * 100.0) / total : 0;

                    int lastDot = fullMethod.lastIndexOf('.');
                    String className = lastDot > 0 ? fullMethod.substring(0, lastDot) : "Unknown";
                    String methodName = lastDot > 0 ? fullMethod.substring(lastDot + 1) : fullMethod;

                    return new HotMethod(className, methodName, count, percentage);
                })
                .toList();
    }

    /**
     * Clears all recorded data.
     */
    public void clear() {
        totalSamples.set(0);
        methodSampleCounts.clear();
        packageSampleCounts.clear();
    }

    /**
     * A hot method identified by CPU profiling.
     */
    public static final class HotMethod {
        private final String className;
        private final String methodName;
        private final long sampleCount;
        private final double percentage;

        public HotMethod(String className, String methodName, long sampleCount, double percentage) {
            this.className = className;
            this.methodName = methodName;
            this.sampleCount = sampleCount;
            this.percentage = percentage;
        }

        public String className() { return className; }
        public String methodName() { return methodName; }
        public long sampleCount() { return sampleCount; }
        public double percentage() { return percentage; }

        /**
         * Returns the fully qualified method name.
         */
        public String fullyQualifiedName() {
            return className + "." + methodName;
        }
    }

    /**
     * Result of method profiling analysis.
     */
    public static final class MethodProfilingResult {
        private final long totalSamples;
        private final List<HotMethod> topMethods;
        private final Map<String, Long> packageDistribution;

        public MethodProfilingResult(long totalSamples, List<HotMethod> topMethods,
                                     Map<String, Long> packageDistribution) {
            this.totalSamples = totalSamples;
            this.topMethods = topMethods;
            this.packageDistribution = packageDistribution;
        }

        public long totalSamples() { return totalSamples; }
        public List<HotMethod> topMethods() { return topMethods; }
        public Map<String, Long> packageDistribution() { return packageDistribution; }
    }
}
