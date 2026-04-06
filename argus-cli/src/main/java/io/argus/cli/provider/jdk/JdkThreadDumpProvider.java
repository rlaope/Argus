package io.argus.cli.provider.jdk;

import io.argus.cli.model.ThreadDumpResult;
import io.argus.cli.model.ThreadDumpResult.ThreadInfo;
import io.argus.cli.provider.ThreadDumpProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ThreadDumpProvider that uses {@code jcmd Thread.print} to capture full thread dump.
 */
public final class JdkThreadDumpProvider implements ThreadDumpProvider {

    // "Thread-0" #12 daemon prio=5 os_prio=31 cpu=1.23ms elapsed=45.67s tid=0x00007f nid=0x5703 ...
    private static final Pattern THREAD_HEADER = Pattern.compile(
            "^\"(.+?)\"\\s+#(\\d+)\\s*(daemon\\s+)?prio=(\\d+).*?tid=(0x[0-9a-f]+).*?nid=(0x[0-9a-f]+)");

    // java.lang.Thread.State: RUNNABLE
    private static final Pattern STATE_PATTERN = Pattern.compile(
            "java\\.lang\\.Thread\\.State:\\s+(\\S+)");

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
    public ThreadDumpResult dumpThreads(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "Thread.print");
        } catch (RuntimeException e) {
            return new ThreadDumpResult(0, List.of(), "");
        }
        return parseOutput(output);
    }

    static ThreadDumpResult parseOutput(String output) {
        if (output == null || output.isEmpty()) {
            return new ThreadDumpResult(0, List.of(), "");
        }

        String[] lines = output.split("\n");
        List<ThreadInfo> threads = new ArrayList<>();

        String currentName = null;
        long currentTid = 0;
        long currentNid = 0;
        String currentState = "UNKNOWN";
        boolean currentDaemon = false;
        int currentPrio = 5;
        List<String> currentStack = new ArrayList<>();
        List<String> currentLocks = new ArrayList<>();
        String currentWaiting = "";

        for (String line : lines) {
            String trimmed = line.trim();

            Matcher headerMatcher = THREAD_HEADER.matcher(trimmed);
            if (headerMatcher.find()) {
                // Flush previous thread
                if (currentName != null) {
                    threads.add(new ThreadInfo(currentName, currentTid, currentNid,
                            currentState, currentDaemon, currentPrio,
                            List.copyOf(currentStack), List.copyOf(currentLocks), currentWaiting));
                }

                currentName = headerMatcher.group(1);
                currentTid = parseLong(headerMatcher.group(5));
                currentNid = parseLong(headerMatcher.group(6));
                currentDaemon = headerMatcher.group(3) != null;
                currentPrio = Integer.parseInt(headerMatcher.group(4));
                currentState = "UNKNOWN";
                currentStack = new ArrayList<>();
                currentLocks = new ArrayList<>();
                currentWaiting = "";
                continue;
            }

            if (currentName == null) continue;

            Matcher stateMatcher = STATE_PATTERN.matcher(trimmed);
            if (stateMatcher.find()) {
                currentState = stateMatcher.group(1);
                continue;
            }

            if (trimmed.startsWith("at ")) {
                currentStack.add(trimmed.substring(3));
            } else if (trimmed.startsWith("- locked <")) {
                currentLocks.add(extractBetween(trimmed, '<', '>'));
            } else if (trimmed.startsWith("- waiting on <") || trimmed.startsWith("- parking to wait for <")) {
                currentWaiting = extractBetween(trimmed, '<', '>');
            }
        }

        // Flush last thread
        if (currentName != null) {
            threads.add(new ThreadInfo(currentName, currentTid, currentNid,
                    currentState, currentDaemon, currentPrio,
                    List.copyOf(currentStack), List.copyOf(currentLocks), currentWaiting));
        }

        return new ThreadDumpResult(threads.size(), List.copyOf(threads), output);
    }

    private static long parseLong(String hex) {
        try {
            return Long.decode(hex);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extractBetween(String line, char open, char close) {
        int start = line.indexOf(open);
        int end = line.indexOf(close, start + 1);
        if (start >= 0 && end > start) {
            return line.substring(start + 1, end);
        }
        return "";
    }
}
