package io.argus.aggregator.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a hierarchical flamegraph tree from collapsed-stack {@code stack -> count}
 * maps and renders it to the JSON shape a d3-flamegraph component expects.
 *
 * <p>Collapsed stacks are semicolon-delimited frames, leaf last
 * ({@code "root;a;b" -> count}), exactly as async-profiler emits and as
 * {@link ProfileStore#merged} returns. A single map may contain multiple
 * distinct roots; they are gathered under a synthetic {@code "root"} node so the
 * output is always a single tree (what the renderer wants).
 *
 * <h2>Output shapes</h2>
 * <p>{@link #toJson} — a plain flamegraph node:
 * <pre>{@code {"name":"root","value":N,"children":[{"name":"a","value":..,"children":[...]}]}}</pre>
 * where {@code value} is the inclusive sample count (sum of the subtree).
 *
 * <p>{@link #diffToJson} — a differential node carrying head/base counts and a
 * signed delta per frame so the frontend can color grown (+) / shrunk (−) frames:
 * <pre>{@code {"name":"a","value":head,"base":B,"head":H,"delta":H-B,"children":[...]}}</pre>
 *
 * <p>Pure, allocation-bounded by the number of distinct frames — no Jackson, no
 * external deps. Java-17 safe.
 */
public final class FlameTree {

    private FlameTree() {}

    private static final String ROOT = "root";

    /** A mutable tree node accumulated while inserting collapsed stacks. */
    private static final class Node {
        final String name;
        long value;          // inclusive count for the plain tree
        long head;           // inclusive head count for the diff tree
        long base;           // inclusive base count for the diff tree
        // LinkedHashMap keeps first-seen child order stable for deterministic output.
        final Map<String, Node> children = new LinkedHashMap<>();

        Node(String name) {
            this.name = name;
        }

        Node child(String frame) {
            return children.computeIfAbsent(frame, Node::new);
        }
    }

    // ── Plain flamegraph ──────────────────────────────────────────────────────

    /**
     * Builds the flamegraph tree for one merged {@code stack -> count} map and
     * returns it as d3-flamegraph JSON. Each node's {@code value} is the
     * inclusive sample count of its subtree.
     */
    public static String toJson(Map<String, Long> collapsed) {
        Node root = new Node(ROOT);
        if (collapsed != null) {
            for (Map.Entry<String, Long> e : collapsed.entrySet()) {
                long count = e.getValue() == null ? 0L : e.getValue();
                if (count <= 0L) {
                    continue;
                }
                insert(root, e.getKey(), count);
            }
        }
        StringBuilder sb = new StringBuilder(256);
        appendPlain(sb, root);
        return sb.toString();
    }

    private static void insert(Node root, String stack, long count) {
        root.value += count;
        Node cur = root;
        for (String frame : splitFrames(stack)) {
            cur = cur.child(frame);
            cur.value += count;
        }
    }

    private static void appendPlain(StringBuilder sb, Node node) {
        sb.append("{\"name\":\"").append(escape(node.name)).append("\",")
          .append("\"value\":").append(node.value)
          .append(",\"children\":[");
        boolean first = true;
        for (Node c : node.children.values()) {
            if (!first) sb.append(',');
            first = false;
            appendPlain(sb, c);
        }
        sb.append("]}");
    }

    // ── Differential flamegraph ────────────────────────────────────────────────

    /**
     * Builds a differential flamegraph from a {@code base} window and a
     * {@code head} window. Each node carries inclusive {@code head} / {@code base}
     * counts and a signed {@code delta = head - base}; {@code value} is the head
     * count (so the rendered width tracks the current profile). Frames present
     * only in head have {@code base == 0} (positive delta); frames present only in
     * base have {@code head == 0} (negative delta).
     */
    public static String diffToJson(Map<String, Long> base, Map<String, Long> head) {
        Node root = new Node(ROOT);
        accumulate(root, head, true);
        accumulate(root, base, false);
        StringBuilder sb = new StringBuilder(256);
        appendDiff(sb, root);
        return sb.toString();
    }

    private static void accumulate(Node root, Map<String, Long> collapsed, boolean isHead) {
        if (collapsed == null) {
            return;
        }
        for (Map.Entry<String, Long> e : collapsed.entrySet()) {
            long count = e.getValue() == null ? 0L : e.getValue();
            if (count <= 0L) {
                continue;
            }
            if (isHead) root.head += count; else root.base += count;
            Node cur = root;
            for (String frame : splitFrames(e.getKey())) {
                cur = cur.child(frame);
                if (isHead) cur.head += count; else cur.base += count;
            }
        }
    }

    private static void appendDiff(StringBuilder sb, Node node) {
        long delta = node.head - node.base;
        sb.append("{\"name\":\"").append(escape(node.name)).append("\",")
          .append("\"value\":").append(node.head)
          .append(",\"head\":").append(node.head)
          .append(",\"base\":").append(node.base)
          .append(",\"delta\":").append(delta)
          .append(",\"children\":[");
        boolean first = true;
        for (Node c : node.children.values()) {
            if (!first) sb.append(',');
            first = false;
            appendDiff(sb, c);
        }
        sb.append("]}");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Splits a collapsed stack into frames, dropping empty segments. */
    private static List<String> splitFrames(String stack) {
        List<String> out = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return out;
        }
        int start = 0;
        for (int i = 0; i < stack.length(); i++) {
            if (stack.charAt(i) == ';') {
                if (i > start) {
                    out.add(stack.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < stack.length()) {
            out.add(stack.substring(start));
        }
        return out;
    }

    /** Minimal JSON string escaper (frame names rarely need it, but be safe). */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
