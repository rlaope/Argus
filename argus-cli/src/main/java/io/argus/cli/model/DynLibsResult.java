package io.argus.cli.model;

import java.util.List;

/**
 * Native libraries loaded in the JVM.
 */
public final class DynLibsResult {
    private final int totalCount;
    private final List<LibInfo> libraries;

    public DynLibsResult(int totalCount, List<LibInfo> libraries) {
        this.totalCount = totalCount;
        this.libraries = libraries;
    }

    public int totalCount() { return totalCount; }
    public List<LibInfo> libraries() { return libraries; }

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
