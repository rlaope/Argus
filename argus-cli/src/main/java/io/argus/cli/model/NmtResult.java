package io.argus.cli.model;

import io.argus.cli.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

import java.util.List;

/**
 * Snapshot of native memory tracking from jcmd VM.native_memory summary.
 */
public final class NmtResult implements JsonWritable {
    private final long totalReservedKB;
    private final long totalCommittedKB;
    private final List<NmtCategory> categories;
    private final boolean nmtNotEnabled;

    public NmtResult(long totalReservedKB, long totalCommittedKB, List<NmtCategory> categories) {
        this.totalReservedKB = totalReservedKB;
        this.totalCommittedKB = totalCommittedKB;
        this.categories = categories;
        this.nmtNotEnabled = false;
    }

    private NmtResult(boolean nmtNotEnabled) {
        this.totalReservedKB = 0;
        this.totalCommittedKB = 0;
        this.categories = List.of();
        this.nmtNotEnabled = nmtNotEnabled;
    }

    /** Returns a sentinel result indicating that NMT is not enabled on the target JVM. */
    public static NmtResult notEnabled() {
        return new NmtResult(true);
    }

    /** True when jcmd reported "Native memory tracking is not enabled". */
    public boolean isNmtNotEnabled() { return nmtNotEnabled; }

    public long totalReservedKB() { return totalReservedKB; }
    public long totalCommittedKB() { return totalCommittedKB; }
    public List<NmtCategory> categories() { return categories; }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"totalReservedKB\":").append(totalReservedKB)
           .append(",\"totalCommittedKB\":").append(totalCommittedKB)
           .append(",\"categories\":[");
        boolean first = true;
        for (NmtCategory cat : categories) {
            if (!first) out.append(',');
            out.append("{\"name\":\"").append(RichRenderer.escapeJson(cat.name())).append('"')
               .append(",\"reservedKB\":").append(cat.reservedKB())
               .append(",\"committedKB\":").append(cat.committedKB())
               .append('}');
            first = false;
        }
        out.append("]}");
    }

    public static final class NmtCategory {
        private final String name;
        private final long reservedKB;
        private final long committedKB;

        public NmtCategory(String name, long reservedKB, long committedKB) {
            this.name = name;
            this.reservedKB = reservedKB;
            this.committedKB = committedKB;
        }

        public String name() { return name; }
        public long reservedKB() { return reservedKB; }
        public long committedKB() { return committedKB; }
    }
}
