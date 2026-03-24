package io.argus.cli.model;

import java.util.List;

/**
 * Snapshot of classloader hierarchy from jcmd VM.classloaders.
 */
public record ClassLoaderResult(List<ClassLoaderInfo> loaders, long totalClasses) {

    public record ClassLoaderInfo(String name, long classCount, String parent) {}
}
