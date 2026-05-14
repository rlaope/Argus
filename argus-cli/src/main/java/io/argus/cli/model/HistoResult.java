package io.argus.cli.model;

import io.argus.cli.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

import java.util.List;

/**
 * Heap object histogram result.
 */
public final class HistoResult implements JsonWritable {
    private final List<Entry> entries;
    private final long totalInstances;
    private final long totalBytes;

    public HistoResult(List<Entry> entries, long totalInstances, long totalBytes) {
        this.entries = entries;
        this.totalInstances = totalInstances;
        this.totalBytes = totalBytes;
    }

    public List<Entry> entries() { return entries; }
    public long totalInstances() { return totalInstances; }
    public long totalBytes() { return totalBytes; }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"totalInstances\":").append(totalInstances)
           .append(",\"totalBytes\":").append(totalBytes)
           .append(",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0) out.append(',');
            out.append("{\"rank\":").append(e.rank())
               .append(",\"className\":\"").append(RichRenderer.escapeJson(e.className())).append('"')
               .append(",\"instances\":").append(e.instances())
               .append(",\"bytes\":").append(e.bytes())
               .append('}');
        }
        out.append("]}");
    }

    public static final class Entry {
        private final int rank;
        private final String className;
        private final long instances;
        private final long bytes;

        public Entry(int rank, String className, long instances, long bytes) {
            this.rank = rank;
            this.className = className;
            this.instances = instances;
            this.bytes = bytes;
        }

        public int rank() { return rank; }
        public String className() { return className; }
        public long instances() { return instances; }
        public long bytes() { return bytes; }
    }
}
