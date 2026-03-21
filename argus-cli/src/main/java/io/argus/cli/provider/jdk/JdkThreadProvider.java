package io.argus.cli.provider.jdk;

import io.argus.cli.model.ThreadResult;
import io.argus.cli.provider.ThreadProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ThreadProvider that uses {@code jcmd Thread.print} to obtain thread dump data.
 */
public final class JdkThreadProvider implements ThreadProvider {

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
    public ThreadResult getThreadDump(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "Thread.print");
        } catch (RuntimeException e) {
            return new ThreadResult(0, 0, 0, Map.of(), List.of(), List.of());
        }

        List<ThreadResult.ThreadInfo> threads = new ArrayList<>();
        List<ThreadResult.DeadlockInfo> deadlocks = new ArrayList<>();
        Map<String, Integer> stateDistribution = new HashMap<>();

        boolean inDeadlockSection = false;
        String currentThreadName = null;
        boolean currentVirtual = false;
        String currentState = null;

        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Deadlock detection
            if (trimmed.contains("Found one Java-level deadlock") || trimmed.contains("Found a total of")) {
                inDeadlockSection = true;
            }

            if (inDeadlockSection) {
                // Parse deadlock block: look for thread name pairs
                // Format: "  "thread-name":" lines paired with "waiting to lock monitor"
                if (trimmed.startsWith("\"") && trimmed.endsWith("\":")) {
                    String t1 = extractThreadName(trimmed);
                    // Look ahead for the second thread
                    for (int j = i + 1; j < Math.min(i + 10, lines.length); j++) {
                        String next = lines[j].trim();
                        if (next.startsWith("\"") && next.endsWith("\":") && !next.equals(trimmed)) {
                            String t2 = extractThreadName(next);
                            // Look for lock class
                            String lockClass = "";
                            for (int k = j + 1; k < Math.min(j + 5, lines.length); k++) {
                                String lockLine = lines[k].trim();
                                if (lockLine.startsWith("waiting to lock")) {
                                    int ltIdx = lockLine.indexOf('<');
                                    int gtIdx = lockLine.indexOf('>');
                                    if (ltIdx >= 0 && gtIdx > ltIdx) {
                                        lockClass = lockLine.substring(gtIdx + 1).trim();
                                        if (lockClass.startsWith("(") && lockClass.contains(")")) {
                                            lockClass = lockClass.substring(1, lockClass.indexOf(')'));
                                        }
                                    }
                                    break;
                                }
                            }
                            deadlocks.add(new ThreadResult.DeadlockInfo(t1, t2, lockClass));
                            break;
                        }
                    }
                }
                continue;
            }

            // Thread header line: starts with a quote
            // Format: '"thread-name" #N [daemon] [virtual] prio=N os_prio=N ...'
            if (trimmed.startsWith("\"") && !trimmed.startsWith("\"Found")) {
                // Flush previous thread if any
                if (currentThreadName != null && currentState != null) {
                    threads.add(new ThreadResult.ThreadInfo(currentThreadName, currentState, currentVirtual));
                    stateDistribution.merge(currentState, 1, Integer::sum);
                }

                currentThreadName = extractThreadName(trimmed);
                currentVirtual = trimmed.contains("virtual") || trimmed.contains("VirtualThread");
                currentState = null;
            }

            // State line: "   java.lang.Thread.State: RUNNABLE"
            if (trimmed.startsWith("java.lang.Thread.State:")) {
                String[] parts = trimmed.split(":", 2);
                if (parts.length == 2) {
                    currentState = parts[1].trim().split("\\s+")[0];
                }
            }
        }

        // Flush the last thread
        if (currentThreadName != null && currentState != null) {
            threads.add(new ThreadResult.ThreadInfo(currentThreadName, currentState, currentVirtual));
            stateDistribution.merge(currentState, 1, Integer::sum);
        }

        int totalThreads = threads.size();
        int virtualThreads = (int) threads.stream().filter(ThreadResult.ThreadInfo::virtual).count();
        int platformThreads = totalThreads - virtualThreads;

        return new ThreadResult(
                totalThreads,
                virtualThreads,
                platformThreads,
                Map.copyOf(stateDistribution),
                List.copyOf(deadlocks),
                List.copyOf(threads)
        );
    }

    private static String extractThreadName(String headerLine) {
        int start = headerLine.indexOf('"');
        if (start < 0) return headerLine;
        int end = headerLine.indexOf('"', start + 1);
        if (end < 0) return headerLine.substring(start + 1);
        return headerLine.substring(start + 1, end);
    }
}
