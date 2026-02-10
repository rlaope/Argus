package io.argus.server.analysis;

import io.argus.core.event.ExecutionSampleEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Analyzes execution sample events and builds flame graph data.
 *
 * <p>Aggregates stack traces into a tree structure suitable for d3-flamegraph
 * visualization and collapsed stack format for external tools.
 */
public final class FlameGraphAnalyzer {

    private static final long DEFAULT_WINDOW_MS = 60_000; // 60 seconds

    private final AtomicLong totalSamples = new AtomicLong(0);
    private final long windowMs;
    private volatile long windowStartTime;
    private FlameNode root = new FlameNode("root");

    public FlameGraphAnalyzer() {
        this(DEFAULT_WINDOW_MS);
    }

    public FlameGraphAnalyzer(long windowMs) {
        this.windowMs = windowMs;
        this.windowStartTime = System.currentTimeMillis();
    }

    /**
     * Records an execution sample event for flame graph aggregation.
     *
     * @param event the execution sample event to record
     */
    public void recordSample(ExecutionSampleEvent event) {
        if (event == null || event.stackTrace() == null || event.stackTrace().isBlank()) {
            return;
        }

        totalSamples.incrementAndGet();

        List<String> frames = parseStackTrace(event.stackTrace());
        if (frames.isEmpty()) {
            return;
        }

        synchronized (root) {
            // Auto-rotate: if time window has passed, start fresh
            if (System.currentTimeMillis() - windowStartTime > windowMs) {
                root = new FlameNode("root");
                windowStartTime = System.currentTimeMillis();
            }

            root.value++;
            FlameNode current = root;
            for (String frame : frames) {
                current = current.children.computeIfAbsent(frame, FlameNode::new);
                current.value++;
            }
        }
    }

    /**
     * Returns the flame graph data as d3-flamegraph compatible JSON.
     *
     * @return JSON string for d3-flamegraph
     */
    public String getFlameGraphJson() {
        synchronized (root) {
            StringBuilder sb = new StringBuilder(4096);
            appendNodeJson(sb, root);
            return sb.toString();
        }
    }

    /**
     * Returns the flame graph data in collapsed stack format.
     *
     * <p>Each line: {@code frame1;frame2;frame3 count}
     *
     * @return collapsed stacks string
     */
    public String getCollapsedStacks() {
        synchronized (root) {
            StringBuilder sb = new StringBuilder(4096);
            List<String> path = new ArrayList<>();
            collectCollapsedStacks(root, path, sb);
            return sb.toString();
        }
    }

    /**
     * Returns the total number of samples recorded.
     *
     * @return total sample count
     */
    public long getTotalSamples() {
        return totalSamples.get();
    }

    /**
     * Clears all recorded data and resets the time window.
     */
    public void clear() {
        synchronized (root) {
            totalSamples.set(0);
            root = new FlameNode("root");
            windowStartTime = System.currentTimeMillis();
        }
    }

    /**
     * Returns the remaining seconds in the current time window.
     */
    public int getWindowRemainingSeconds() {
        long elapsed = System.currentTimeMillis() - windowStartTime;
        long remaining = Math.max(0, windowMs - elapsed);
        return (int) (remaining / 1000);
    }

    /**
     * Parses a stack trace string into a list of frame names.
     * JFR stack traces are top-frame-first; this reverses them so
     * the root (entry point) is first for flame graph rendering.
     */
    private List<String> parseStackTrace(String stackTrace) {
        String[] lines = stackTrace.split("\n");
        List<String> frames = new ArrayList<>(lines.length);

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("at ")) {
                String frame = trimmed.substring(3);
                // Remove source info: "class.method(line:N)" -> "class.method"
                int parenIdx = frame.indexOf('(');
                if (parenIdx > 0) {
                    frame = frame.substring(0, parenIdx);
                }
                if (!frame.isEmpty()) {
                    frames.add(frame);
                }
            }
        }

        // Reverse: JFR is leaf-first, flame graph needs root-first
        java.util.Collections.reverse(frames);
        return frames;
    }

    private void appendNodeJson(StringBuilder sb, FlameNode node) {
        sb.append("{\"name\":\"");
        escapeJson(sb, node.name);
        sb.append("\",\"value\":").append(node.value);

        if (!node.children.isEmpty()) {
            sb.append(",\"children\":[");
            boolean first = true;
            for (FlameNode child : node.children.values()) {
                if (!first) sb.append(',');
                first = false;
                appendNodeJson(sb, child);
            }
            sb.append(']');
        }

        sb.append('}');
    }

    private void collectCollapsedStacks(FlameNode node, List<String> path, StringBuilder sb) {
        if (node.children.isEmpty() && !path.isEmpty()) {
            // Leaf node: output the path with count
            sb.append(String.join(";", path));
            sb.append(' ').append(node.value).append('\n');
            return;
        }

        for (Map.Entry<String, FlameNode> entry : node.children.entrySet()) {
            path.add(entry.getKey());
            collectCollapsedStacks(entry.getValue(), path, sb);
            path.remove(path.size() - 1);
        }
    }

    private void escapeJson(StringBuilder sb, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
    }

    /**
     * Internal tree node for flame graph aggregation.
     */
    private static final class FlameNode {
        final String name;
        int value;
        final Map<String, FlameNode> children = new ConcurrentHashMap<>();

        FlameNode(String name) {
            this.name = name;
        }
    }
}
