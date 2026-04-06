package io.argus.cli.model;

import java.util.List;

/**
 * Result of loaded class search.
 */
public final class SearchClassResult {

    private final List<ClassInfo> classes;
    private final int totalMatches;
    private final String pattern;

    public SearchClassResult(List<ClassInfo> classes, int totalMatches, String pattern) {
        this.classes = classes;
        this.totalMatches = totalMatches;
        this.pattern = pattern;
    }

    public List<ClassInfo> classes() { return classes; }
    public int totalMatches() { return totalMatches; }
    public String pattern() { return pattern; }

    public static final class ClassInfo {
        private final String name;
        private final long instanceCount;
        private final long totalBytes;

        public ClassInfo(String name, long instanceCount, long totalBytes) {
            this.name = name;
            this.instanceCount = instanceCount;
            this.totalBytes = totalBytes;
        }

        public String name() { return name; }
        public long instanceCount() { return instanceCount; }
        public long totalBytes() { return totalBytes; }
    }
}
