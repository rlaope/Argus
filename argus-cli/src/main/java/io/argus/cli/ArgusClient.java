package io.argus.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client that polls Argus server endpoints for metrics data.
 */
public final class ArgusClient {

    private final String baseUrl;
    private final HttpClient httpClient;

    public ArgusClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Fetches all metrics from the server in parallel.
     *
     * @return a complete metrics snapshot
     */
    public MetricsSnapshot fetchAll() {
        try {
            // Fetch all endpoints in parallel
            var healthFuture = fetchAsync("/health");
            var metricsFuture = fetchAsync("/metrics");
            var cpuFuture = fetchAsync("/cpu-metrics");
            var gcFuture = fetchAsync("/gc-analysis");
            var activeFuture = fetchAsync("/active-threads");
            var pinningFuture = fetchAsync("/pinning-analysis");
            var carrierFuture = fetchAsync("/carrier-threads");
            var profilingFuture = fetchAsync("/method-profiling");
            var metaspaceFuture = fetchAsync("/metaspace-metrics");
            var contentionFuture = fetchAsync("/contention-analysis");

            // Wait for all
            CompletableFuture.allOf(healthFuture, metricsFuture, cpuFuture, gcFuture,
                    activeFuture, pinningFuture, carrierFuture, profilingFuture,
                    metaspaceFuture, contentionFuture).join();

            String health = healthFuture.join();
            String metrics = metricsFuture.join();
            String cpu = cpuFuture.join();
            String gc = gcFuture.join();
            String active = activeFuture.join();
            String pinning = pinningFuture.join();
            String carrier = carrierFuture.join();
            String profiling = profilingFuture.join();
            String metaspace = metaspaceFuture.join();
            String contention = contentionFuture.join();

            return buildSnapshot(health, metrics, cpu, gc, active, pinning,
                    carrier, profiling, metaspace, contention);
        } catch (Exception e) {
            return MetricsSnapshot.disconnected();
        }
    }

    private CompletableFuture<String> fetchAsync(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> resp.statusCode() == 200 ? resp.body() : "")
                .exceptionally(e -> "");
    }

    private MetricsSnapshot buildSnapshot(String health, String metrics, String cpu,
                                          String gc, String active, String pinning,
                                          String carrier, String profiling,
                                          String metaspace, String contention) {
        int clientCount = jsonInt(health, "clients");

        long totalEvents = jsonLong(metrics, "totalEvents");
        long startEvents = jsonLong(metrics, "startEvents");
        long endEvents = jsonLong(metrics, "endEvents");
        long pinnedEvents = jsonLong(metrics, "pinnedEvents");
        int activeThreads = countJsonArrayElements(active);

        double cpuJvm = jsonDouble(cpu, "currentJvmPercent");
        double cpuMachine = jsonDouble(cpu, "currentMachinePercent");
        double cpuPeak = jsonDouble(cpu, "peakJvmTotal") * 100;

        long gcTotal = jsonLong(gc, "totalGCEvents");
        double gcPause = jsonDouble(gc, "totalPauseTimeMs");
        double gcOverhead = jsonDouble(gc, "gcOverheadPercent");
        long heapUsed = jsonLong(gc, "currentHeapUsed");
        long heapCommitted = jsonLong(gc, "currentHeapCommitted");

        double metaUsed = jsonDouble(metaspace, "currentUsedMB");
        long classes = jsonLong(metaspace, "currentClassCount");

        int carriers = jsonInt(carrier, "totalCarriers");
        double avgVt = jsonDouble(carrier, "avgVirtualThreadsPerCarrier");

        long totalPinned = jsonLong(pinning, "totalPinnedEvents");
        int uniqueStacks = jsonInt(pinning, "uniqueStackTraces");

        long samples = jsonLong(profiling, "totalSamples");
        List<MetricsSnapshot.HotMethodInfo> hotMethods = parseHotMethods(profiling);

        long contentionEvts = jsonLong(contention, "totalContentionEvents");
        double contentionTime = jsonDouble(contention, "totalContentionTimeMs");
        List<MetricsSnapshot.ContentionHotspot> hotspots = parseContentionHotspots(contention);

        return new MetricsSnapshot(true, clientCount, totalEvents, startEvents,
                endEvents, pinnedEvents, activeThreads, cpuJvm, cpuMachine, cpuPeak,
                gcTotal, gcPause, gcOverhead, heapUsed, heapCommitted,
                metaUsed, classes, carriers, avgVt, totalPinned, uniqueStacks,
                samples, hotMethods, contentionEvts, contentionTime, hotspots);
    }

    // Simple JSON value parsers (no external library)

    private static long jsonLong(String json, String key) {
        String val = extractJsonValue(json, key);
        if (val == null || val.isEmpty()) return 0;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int jsonInt(String json, String key) {
        return (int) jsonLong(json, key);
    }

    private static double jsonDouble(String json, String key) {
        String val = extractJsonValue(json, key);
        if (val == null || val.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String extractJsonValue(String json, String key) {
        if (json == null || json.isEmpty()) return null;
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        if (start >= json.length()) return null;

        char first = json.charAt(start);
        if (first == '"') {
            int end = json.indexOf('"', start + 1);
            return end > start ? json.substring(start + 1, end) : null;
        }

        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || c == ']') break;
            end++;
        }
        return json.substring(start, end).trim();
    }

    private static int countJsonArrayElements(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json.trim())) return 0;
        int count = 0;
        boolean inString = false;
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    if (depth == 1) count++;
                    depth++;
                } else if (c == '}') {
                    depth--;
                } else if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                }
            }
        }
        return count;
    }

    private static List<MetricsSnapshot.HotMethodInfo> parseHotMethods(String json) {
        List<MetricsSnapshot.HotMethodInfo> methods = new ArrayList<>();
        if (json == null || json.isEmpty()) return methods;

        int idx = json.indexOf("\"topMethods\":[");
        if (idx < 0) return methods;

        String section = json.substring(idx);
        int arrEnd = findMatchingBracket(section, section.indexOf('['));
        if (arrEnd < 0) return methods;
        String arr = section.substring(section.indexOf('['), arrEnd + 1);

        int pos = 0;
        while (pos < arr.length() && methods.size() < 5) {
            int objStart = arr.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = arr.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = arr.substring(objStart, objEnd + 1);

            String className = extractJsonStringValue(obj, "className");
            String methodName = extractJsonStringValue(obj, "methodName");
            double pct = jsonDouble(obj, "percentage");

            if (className != null && methodName != null) {
                methods.add(new MetricsSnapshot.HotMethodInfo(className, methodName, pct));
            }
            pos = objEnd + 1;
        }
        return methods;
    }

    private static List<MetricsSnapshot.ContentionHotspot> parseContentionHotspots(String json) {
        List<MetricsSnapshot.ContentionHotspot> hotspots = new ArrayList<>();
        if (json == null || json.isEmpty()) return hotspots;

        int idx = json.indexOf("\"hotspots\":[");
        if (idx < 0) return hotspots;

        String section = json.substring(idx);
        int arrEnd = findMatchingBracket(section, section.indexOf('['));
        if (arrEnd < 0) return hotspots;
        String arr = section.substring(section.indexOf('['), arrEnd + 1);

        int pos = 0;
        while (pos < arr.length() && hotspots.size() < 3) {
            int objStart = arr.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = arr.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = arr.substring(objStart, objEnd + 1);

            String monitor = extractJsonStringValue(obj, "monitorClass");
            long count = jsonLong(obj, "eventCount");

            if (monitor != null) {
                hotspots.add(new MetricsSnapshot.ContentionHotspot(monitor, count));
            }
            pos = objEnd + 1;
        }
        return hotspots;
    }

    private static String extractJsonStringValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }

    private static int findMatchingBracket(String s, int openIdx) {
        if (openIdx < 0) return -1;
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }
}
