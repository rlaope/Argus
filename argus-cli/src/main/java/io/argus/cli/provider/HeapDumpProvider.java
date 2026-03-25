package io.argus.cli.provider;

import io.argus.cli.model.HeapDumpResult;

/**
 * Provider interface for generating heap dumps from a target JVM process.
 */
public interface HeapDumpProvider extends DiagnosticProvider {

    /**
     * Triggers a heap dump on the target JVM.
     *
     * @param pid          target process ID
     * @param filePath     path for the output .hprof file
     * @param liveOnly     if true, only live objects are included (triggers GC first);
     *                     if false, all objects including garbage are included
     * @return result containing file path, size, or error message
     */
    HeapDumpResult heapDump(long pid, String filePath, boolean liveOnly);
}
