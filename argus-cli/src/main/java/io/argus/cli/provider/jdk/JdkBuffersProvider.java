package io.argus.cli.provider.jdk;

import io.argus.cli.model.BuffersResult;
import io.argus.cli.model.BuffersResult.BufferPool;
import io.argus.cli.provider.BuffersProvider;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * BuffersProvider that queries BufferPoolMXBean via JMX attach.
 * Falls back to parsing VM.info output from jcmd.
 */
public final class JdkBuffersProvider implements BuffersProvider {

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
    public BuffersResult getBuffers(long pid) {
        // Try MXBean for local process first
        if (pid == ProcessHandle.current().pid()) {
            return getFromMxBean();
        }
        // For remote processes, parse from jcmd VM.info
        return getFromJcmd(pid);
    }

    private BuffersResult getFromMxBean() {
        List<BufferPoolMXBean> mxBeans = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        List<BufferPool> pools = new ArrayList<>();
        long totalCount = 0;
        long totalCapacity = 0;
        long totalUsed = 0;

        for (BufferPoolMXBean bean : mxBeans) {
            long count = bean.getCount();
            long capacity = bean.getTotalCapacity();
            long used = bean.getMemoryUsed();
            pools.add(new BufferPool(bean.getName(), count, capacity, used));
            totalCount += count;
            totalCapacity += capacity;
            totalUsed += used;
        }

        return new BuffersResult(List.copyOf(pools), totalCount, totalCapacity, totalUsed);
    }

    private BuffersResult getFromJcmd(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "VM.info");
        } catch (RuntimeException e) {
            return new BuffersResult(List.of(), 0, 0, 0);
        }
        return parseVmInfo(output);
    }

    static BuffersResult parseVmInfo(String output) {
        if (output == null || output.isEmpty()) {
            return new BuffersResult(List.of(), 0, 0, 0);
        }

        // VM.info contains a section like:
        // Direct buffer pool: count=10, total capacity=81920, memory used=81920
        // Mapped buffer pool: count=0, total capacity=0, memory used=0
        List<BufferPool> pools = new ArrayList<>();
        long totalCount = 0;
        long totalCapacity = 0;
        long totalUsed = 0;

        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains("buffer pool:") && trimmed.contains("count=")) {
                String name = trimmed.substring(0, trimmed.indexOf("buffer pool:")).trim();
                long count = extractLong(trimmed, "count=");
                long capacity = extractLong(trimmed, "total capacity=");
                long used = extractLong(trimmed, "memory used=");

                pools.add(new BufferPool(name, count, capacity, used));
                totalCount += count;
                totalCapacity += capacity;
                totalUsed += used;
            }
        }

        return new BuffersResult(List.copyOf(pools), totalCount, totalCapacity, totalUsed);
    }

    private static long extractLong(String line, String prefix) {
        int idx = line.indexOf(prefix);
        if (idx < 0) return 0;
        idx += prefix.length();
        StringBuilder sb = new StringBuilder();
        while (idx < line.length() && (Character.isDigit(line.charAt(idx)))) {
            sb.append(line.charAt(idx));
            idx++;
        }
        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
