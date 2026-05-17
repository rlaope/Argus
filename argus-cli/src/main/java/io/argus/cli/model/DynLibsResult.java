package io.argus.cli.model;

import io.argus.diagnostics.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

import java.util.List;

/**
 * Native libraries loaded in the JVM.
 */
public final class DynLibsResult implements JsonWritable {
    private final int totalCount;
    private final List<LibInfo> libraries;

    public DynLibsResult(int totalCount, List<LibInfo> libraries) {
        this.totalCount = totalCount;
        this.libraries = libraries;
    }

    public int totalCount() { return totalCount; }
    public List<LibInfo> libraries() { return libraries; }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"totalCount\":").append(totalCount).append(",\"libraries\":[");
        for (int i = 0; i < libraries.size(); i++) {
            LibInfo lib = libraries.get(i);
            if (i > 0) out.append(',');
            out.append("{\"path\":\"").append(RichRenderer.escapeJson(lib.path())).append('"')
               .append(",\"category\":\"").append(lib.category()).append("\"}");
        }
        out.append("]}");
    }

    public static final class LibInfo {
        private final String path;
        private final String category; // "jdk", "app", "system"

        public LibInfo(String path, String category) {
            this.path = path;
            this.category = category;
        }

        public String path() { return path; }
        public String category() { return category; }
    }
}
