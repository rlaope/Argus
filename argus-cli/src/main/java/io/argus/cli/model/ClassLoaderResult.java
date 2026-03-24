package io.argus.cli.model;

import java.util.List;

/**
 * Snapshot of classloader hierarchy from jcmd VM.classloaders.
 */
public final class ClassLoaderResult {
    private final List<ClassLoaderInfo> loaders;
    private final long totalClasses;

    public ClassLoaderResult(List<ClassLoaderInfo> loaders, long totalClasses) {
        this.loaders = loaders;
        this.totalClasses = totalClasses;
    }

    public List<ClassLoaderInfo> loaders() { return loaders; }
    public long totalClasses() { return totalClasses; }

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
