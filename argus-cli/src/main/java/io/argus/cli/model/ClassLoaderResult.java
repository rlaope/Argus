package io.argus.cli.model;

import io.argus.diagnostics.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

import java.util.List;

/**
 * Snapshot of classloader hierarchy from jcmd VM.classloaders.
 */
public final class ClassLoaderResult implements JsonWritable {
    private final List<ClassLoaderInfo> loaders;
    private final long totalClasses;

    public ClassLoaderResult(List<ClassLoaderInfo> loaders, long totalClasses) {
        this.loaders = loaders;
        this.totalClasses = totalClasses;
    }

    public List<ClassLoaderInfo> loaders() { return loaders; }
    public long totalClasses() { return totalClasses; }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"loaders\":[");
        boolean first = true;
        for (ClassLoaderInfo loader : loaders) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"name\":\"").append(RichRenderer.escapeJson(loader.name())).append('"')
               .append(",\"classCount\":").append(loader.classCount());
            if (loader.parent() != null) {
                out.append(",\"parent\":\"").append(RichRenderer.escapeJson(loader.parent())).append('"');
            } else {
                out.append(",\"parent\":null");
            }
            out.append('}');
        }
        out.append("],\"totalClasses\":").append(totalClasses).append('}');
    }

    public static final class ClassLoaderInfo {
        private final String name;
        private final long classCount;
        private final String parent;

        public ClassLoaderInfo(String name, long classCount, String parent) {
            this.name = name;
            this.classCount = classCount;
            this.parent = parent;
        }

        public String name() { return name; }
        public long classCount() { return classCount; }
        public String parent() { return parent; }
    }
}
