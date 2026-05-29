package io.argus.server.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and renders the {@code StructuredTaskScope} tree from a JDK VT-aware
 * thread dump in JSON form, as produced by
 * {@code jcmd <pid> Thread.dump_to_file -format=json <file>}.
 *
 * <p>The JDK JSON dump exposes {@code threadDump.threadContainers[]}, where each
 * container has a {@code container} name, a {@code parent} container name, an
 * {@code owner} thread id (the thread that opened the scope), and the
 * {@code threads[]} forked into it. A {@code StructuredTaskScope} surfaces as a
 * container whose name starts with {@code java.util.concurrent.StructuredTaskScope}.
 *
 * <p>This view reconstructs the parent/child nesting of those scope containers
 * and renders the forked subtasks grouped under their owning scope. It is a
 * read-only analysis helper with no external JSON dependency: it ships a small
 * tokenizer scoped to this one document shape.
 */
public final class StructuredConcurrencyView {

    private static final String SCOPE_PREFIX = "java.util.concurrent.StructuredTaskScope";

    /** A forked subtask running inside a scope. */
    public record Subtask(long tid, String name, List<String> stack) {
    }

    /** A {@code StructuredTaskScope} node: its forked subtasks plus any nested child scopes. */
    public static final class ScopeNode {
        private final String name;
        private final long ownerTid;
        private final List<Subtask> subtasks = new ArrayList<>();
        private final List<ScopeNode> children = new ArrayList<>();

        ScopeNode(String name, long ownerTid) {
            this.name = name;
            this.ownerTid = ownerTid;
        }

        public String name() { return name; }
        public long ownerTid() { return ownerTid; }
        public List<Subtask> subtasks() { return subtasks; }
        public List<ScopeNode> children() { return children; }
    }

    private StructuredConcurrencyView() {
    }

    /**
     * Parses the JDK JSON thread dump and returns the roots of the scope forest.
     * Scopes whose {@code parent} is another scope nest under it; scopes whose
     * parent is the {@code <root>} container (or unknown) are returned as roots.
     *
     * @param json the JSON dump text
     * @return ordered list of root {@link ScopeNode}s (empty when no scopes present)
     */
    public static List<ScopeNode> parse(String json) {
        List<Container> containers = readContainers(json);

        Map<String, ScopeNode> scopeByName = new LinkedHashMap<>();
        for (Container c : containers) {
            if (isScope(c.name)) {
                ScopeNode node = new ScopeNode(c.name, c.ownerTid);
                for (Subtask t : c.threads) {
                    node.subtasks().add(t);
                }
                scopeByName.put(c.name, node);
            }
        }

        List<ScopeNode> roots = new ArrayList<>();
        for (Container c : containers) {
            if (!isScope(c.name)) {
                continue;
            }
            ScopeNode node = scopeByName.get(c.name);
            ScopeNode parentScope = c.parent == null ? null : scopeByName.get(c.parent);
            if (parentScope != null) {
                parentScope.children().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }

    /**
     * Renders the scope forest as an indented text tree.
     *
     * @param json the JSON dump text
     * @return a multi-line tree rendering; a single line when no scopes are present
     */
    public static String render(String json) {
        List<ScopeNode> roots = parse(json);
        if (roots.isEmpty()) {
            return "No StructuredTaskScope instances found.\n";
        }
        StringBuilder sb = new StringBuilder();
        for (ScopeNode root : roots) {
            renderNode(root, 0, sb);
        }
        return sb.toString();
    }

    private static void renderNode(ScopeNode node, int depth, StringBuilder sb) {
        String indent = "  ".repeat(depth);
        sb.append(indent)
          .append("Scope ").append(shortName(node.name()))
          .append(" (owner tid=").append(node.ownerTid())
          .append(", ").append(node.subtasks().size()).append(" subtask(s))\n");
        for (Subtask t : node.subtasks()) {
            sb.append(indent).append("  - ").append(t.name())
              .append(" [tid=").append(t.tid()).append("]");
            if (!t.stack().isEmpty()) {
                sb.append(" @ ").append(t.stack().get(0));
            }
            sb.append('\n');
        }
        for (ScopeNode child : node.children()) {
            renderNode(child, depth + 1, sb);
        }
    }

    private static boolean isScope(String containerName) {
        return containerName != null && containerName.startsWith(SCOPE_PREFIX);
    }

    private static String shortName(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot >= 0 ? fullName.substring(dot + 1) : fullName;
    }

    // --- Minimal JSON reader for the known thread-dump shape ----------------

    private static final class Container {
        String name;
        String parent;
        long ownerTid = -1;
        final List<Subtask> threads = new ArrayList<>();
    }

    /**
     * Walks the JSON character stream and emits one {@link Container} per object
     * in {@code threadContainers[]}. This is intentionally tolerant: it keys off
     * the {@code "container"} field to start a record and the surrounding object
     * braces to bound it, rather than fully validating the document.
     */
    private static List<Container> readContainers(String json) {
        List<Container> result = new ArrayList<>();
        int idx = json.indexOf("\"threadContainers\"");
        if (idx < 0) {
            return result;
        }
        int arrStart = json.indexOf('[', idx);
        if (arrStart < 0) {
            return result;
        }

        int i = arrStart + 1;
        int depth = 0;
        int objStart = -1;
        while (i < json.length()) {
            char ch = json.charAt(i);
            if (ch == '"') {
                i = skipString(json, i);
                continue;
            }
            if (ch == '{') {
                if (depth == 0) {
                    objStart = i;
                }
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    result.add(parseContainer(json.substring(objStart, i + 1)));
                    objStart = -1;
                }
            } else if (ch == ']' && depth == 0) {
                break;
            }
            i++;
        }
        return result;
    }

    private static Container parseContainer(String obj) {
        Container c = new Container();
        c.name = stringField(obj, "container");
        c.parent = stringField(obj, "parent");
        String owner = stringField(obj, "owner");
        c.ownerTid = parseLong(owner, -1);

        int threadsIdx = obj.indexOf("\"threads\"");
        if (threadsIdx >= 0) {
            int arrStart = obj.indexOf('[', threadsIdx);
            int arrEnd = matchBracket(obj, arrStart);
            if (arrStart >= 0 && arrEnd > arrStart) {
                parseThreads(obj.substring(arrStart + 1, arrEnd), c.threads);
            }
        }
        return c;
    }

    private static void parseThreads(String arrBody, List<Subtask> out) {
        int i = 0;
        int depth = 0;
        int objStart = -1;
        while (i < arrBody.length()) {
            char ch = arrBody.charAt(i);
            if (ch == '"') {
                i = skipString(arrBody, i);
                continue;
            }
            if (ch == '{') {
                if (depth == 0) {
                    objStart = i;
                }
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String tObj = arrBody.substring(objStart, i + 1);
                    long tid = parseLong(stringField(tObj, "tid"), -1);
                    String name = stringField(tObj, "name");
                    out.add(new Subtask(tid, name == null ? "" : name, parseStack(tObj)));
                    objStart = -1;
                }
            }
            i++;
        }
    }

    private static List<String> parseStack(String threadObj) {
        List<String> frames = new ArrayList<>();
        int stackIdx = threadObj.indexOf("\"stack\"");
        if (stackIdx < 0) {
            return frames;
        }
        int arrStart = threadObj.indexOf('[', stackIdx);
        int arrEnd = matchBracket(threadObj, arrStart);
        if (arrStart < 0 || arrEnd <= arrStart) {
            return frames;
        }
        String body = threadObj.substring(arrStart + 1, arrEnd);
        int i = 0;
        while (i < body.length()) {
            char ch = body.charAt(i);
            if (ch == '"') {
                int end = skipString(body, i);
                frames.add(unescape(body.substring(i + 1, end - 1)));
                i = end;
                continue;
            }
            i++;
        }
        return frames;
    }

    /** Reads a top-level string field {@code "key":"value"} from a JSON object body. */
    private static String stringField(String obj, String key) {
        String needle = "\"" + key + "\"";
        int k = obj.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int colon = obj.indexOf(':', k + needle.length());
        if (colon < 0) {
            return null;
        }
        int p = colon + 1;
        while (p < obj.length() && Character.isWhitespace(obj.charAt(p))) {
            p++;
        }
        if (p >= obj.length()) {
            return null;
        }
        if (obj.charAt(p) == '"') {
            int end = skipString(obj, p);
            return unescape(obj.substring(p + 1, end - 1));
        }
        // null / number
        int end = p;
        while (end < obj.length() && ",}] \n\r\t".indexOf(obj.charAt(end)) < 0) {
            end++;
        }
        String raw = obj.substring(p, end).trim();
        return "null".equals(raw) ? null : raw;
    }

    /** Returns the index just past the closing quote of a string starting at {@code start} ('"'). */
    private static int skipString(String s, int start) {
        int i = start + 1;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '"') {
                return i + 1;
            }
            i++;
        }
        return s.length();
    }

    /** Index of the {@code ]} matching the {@code [} at {@code open}, ignoring brackets in strings. */
    private static int matchBracket(String s, int open) {
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        int i = open;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '"') {
                i = skipString(s, i);
                continue;
            }
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    default -> sb.append(next);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
