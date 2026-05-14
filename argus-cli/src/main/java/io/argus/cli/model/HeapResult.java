package io.argus.cli.model;

import io.argus.cli.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

import java.util.Map;

/**
 * Heap memory usage result.
 */
public final class HeapResult implements JsonWritable {
    private final long used;
    private final long committed;
    private final long max;
    private final Map<String, SpaceInfo> spaces;

    public HeapResult(long used, long committed, long max, Map<String, SpaceInfo> spaces) {
        this.used = used;
        this.committed = committed;
        this.max = max;
        this.spaces = spaces;
    }

    public long used() { return used; }
    public long committed() { return committed; }
    public long max() { return max; }
    public Map<String, SpaceInfo> spaces() { return spaces; }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"used\":").append(used)
           .append(",\"committed\":").append(committed)
           .append(",\"max\":").append(max)
           .append(",\"spaces\":{");
        boolean first = true;
        for (Map.Entry<String, SpaceInfo> e : spaces.entrySet()) {
            if (!first) out.append(',');
            SpaceInfo s = e.getValue();
            out.append('"').append(RichRenderer.escapeJson(e.getKey())).append("\":")
               .append("{\"name\":\"").append(RichRenderer.escapeJson(s.name())).append('"')
               .append(",\"used\":").append(s.used())
               .append(",\"committed\":").append(s.committed())
               .append(",\"max\":").append(s.max())
               .append('}');
            first = false;
        }
        out.append("}}");
    }

    public static final class SpaceInfo {
        private final String name;
        private final long used;
        private final long committed;
        private final long max;

        public SpaceInfo(String name, long used, long committed, long max) {
            this.name = name;
            this.used = used;
            this.committed = committed;
            this.max = max;
        }

        public String name() { return name; }
        public long used() { return used; }
        public long committed() { return committed; }
        public long max() { return max; }
    }
}
