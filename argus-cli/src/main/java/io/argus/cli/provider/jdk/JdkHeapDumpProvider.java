package io.argus.cli.provider.jdk;

import io.argus.cli.model.HeapDumpResult;
import io.argus.cli.provider.HeapDumpProvider;

import java.io.File;

/**
 * JDK-based heap dump provider using {@code jcmd GC.heap_dump}.
 *
 * <p>Live-only mode: {@code jcmd <pid> GC.heap_dump <path>}
 * <p>All-objects mode: {@code jcmd <pid> GC.heap_dump -all <path>}
 */
public final class JdkHeapDumpProvider implements HeapDumpProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String source() {
        return "jdk";
    }

    @Override
    public HeapDumpResult heapDump(long pid, String filePath, boolean liveOnly) {
        try {
            String command;
            if (liveOnly) {
                command = "GC.heap_dump " + filePath;
            } else {
                command = "GC.heap_dump -all " + filePath;
            }

            JcmdExecutor.execute(pid, command);

            File f = new File(filePath);
            long sizeBytes = f.exists() ? f.length() : 0L;
            return new HeapDumpResult("ok", filePath, sizeBytes, null);
        } catch (Exception e) {
            return new HeapDumpResult("error", filePath, 0L, e.getMessage());
        }
    }
}
