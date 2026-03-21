package io.argus.cli.provider.agent;

import io.argus.cli.model.ThreadResult;
import io.argus.cli.provider.ThreadProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ThreadProvider that fetches thread data from the Argus agent's {@code /thread-dump} endpoint.
 */
public final class AgentThreadProvider implements ThreadProvider {

    private final AgentClient client;

    public AgentThreadProvider(AgentClient client) {
        this.client = client;
    }

    @Override
    public boolean isAvailable(long pid) {
        return client.isReachable();
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public String source() {
        return "agent";
    }

    @Override
    public ThreadResult getThreadDump(long pid) {
        String json = client.fetch("/thread-dump");
        if (json == null || json.isEmpty()) {
            return new ThreadResult(0, 0, 0, Map.of(), List.of(), List.of());
        }

        int totalThreads = AgentClient.jsonInt(json, "totalThreads");
        int virtualThreads = AgentClient.jsonInt(json, "virtualThreads");
        int platformThreads = AgentClient.jsonInt(json, "platformThreads");

        // Parse state distribution from the JSON object if present
        Map<String, Integer> stateDistribution = parseStateDistribution(json);

        // Parse individual thread entries from a "threads" array
        List<ThreadResult.ThreadInfo> threads = parseThreadInfoList(json);

        // Fallback: if counts not in JSON, derive from thread list
        if (totalThreads == 0 && !threads.isEmpty()) {
            totalThreads = threads.size();
            virtualThreads = (int) threads.stream().filter(ThreadResult.ThreadInfo::virtual).count();
            platformThreads = totalThreads - virtualThreads;
        }

        return new ThreadResult(
                totalThreads,
                virtualThreads,
                platformThreads,
                Map.copyOf(stateDistribution),
                List.of(),   // deadlock detection not available via agent endpoint
                List.copyOf(threads)
        );
    }

    /**
     * Parses a simple JSON object like {@code "stateDistribution":{"RUNNABLE":10,"WAITING":3}}.
     */
    private static Map<String, Integer> parseStateDistribution(String json) {
        Map<String, Integer> dist = new HashMap<>();
        String marker = "\"stateDistribution\":";
        int idx = json.indexOf(marker);
        if (idx < 0) return dist;

        int objStart = json.indexOf('{', idx + marker.length());
        if (objStart < 0) return dist;
        int objEnd = json.indexOf('}', objStart);
        if (objEnd < 0) return dist;

        String block = json.substring(objStart + 1, objEnd);
        for (String pair : block.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2) continue;
            String stateKey = kv[0].trim().replace("\"", "");
            try {
                int count = Integer.parseInt(kv[1].trim());
                dist.put(stateKey, count);
            } catch (NumberFormatException ignored) {}
        }
        return dist;
    }

    /**
     * Parses the {@code "threads":[...]} array from the thread-dump JSON.
     * Each element is expected to be: {@code {"name":"...","state":"...","virtual":true/false}}.
     */
    private static List<ThreadResult.ThreadInfo> parseThreadInfoList(String json) {
        List<ThreadResult.ThreadInfo> list = new ArrayList<>();
        String marker = "\"threads\":[";
        int idx = json.indexOf(marker);
        if (idx < 0) return list;

        int arrStart = idx + marker.length() - 1;
        int arrEnd = findMatchingBracket(json, arrStart);
        if (arrEnd < 0) return list;

        String arr = json.substring(arrStart + 1, arrEnd);
        int pos = 0;
        while (pos < arr.length()) {
            int objStart = arr.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(arr, objStart);
            if (objEnd < 0) break;

            String obj = arr.substring(objStart, objEnd + 1);
            String name = AgentClient.jsonString(obj, "name");
            String state = AgentClient.jsonString(obj, "state");
            String virtualStr = extractJsonValue(obj, "virtual");
            boolean virtual = "true".equalsIgnoreCase(virtualStr);

            if (name != null && state != null) {
                list.add(new ThreadResult.ThreadInfo(name, state, virtual));
            }
            pos = objEnd + 1;
        }
        return list;
    }

    private static String extractJsonValue(String json, String key) {
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

    private static int findMatchingBracket(String s, int openIdx) {
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inStr = !inStr;
            else if (!inStr) {
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) return i; }
            }
        }
        return -1;
    }

    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inStr = !inStr;
            else if (!inStr) {
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) return i; }
            }
        }
        return -1;
    }
}
