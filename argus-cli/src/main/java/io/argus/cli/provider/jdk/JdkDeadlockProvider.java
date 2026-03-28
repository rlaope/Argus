package io.argus.cli.provider.jdk;

import io.argus.cli.model.DeadlockResult;
import io.argus.cli.model.DeadlockResult.DeadlockChain;
import io.argus.cli.model.DeadlockResult.DeadlockThread;
import io.argus.cli.provider.DeadlockProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * DeadlockProvider that uses {@code jcmd Thread.print} to detect deadlocks.
 */
public final class JdkDeadlockProvider implements DeadlockProvider {

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
    public DeadlockResult detectDeadlocks(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "Thread.print");
        } catch (RuntimeException e) {
            return new DeadlockResult(0, List.of());
        }
        return parseOutput(output);
    }

    /**
     * Parses jcmd Thread.print output and extracts deadlock information.
     * Package-private for testing.
     */
    static DeadlockResult parseOutput(String output) {
        if (output == null || output.isEmpty()) {
            return new DeadlockResult(0, List.of());
        }

        String[] lines = output.split("\n");
        int deadlockSectionStart = -1;

        // Find the deadlock section
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.contains("Found one Java-level deadlock")
                    || trimmed.contains("Found") && trimmed.contains("Java-level deadlock")) {
                deadlockSectionStart = i;
                break;
            }
        }

        if (deadlockSectionStart < 0) {
            return new DeadlockResult(0, List.of());
        }

        // Parse deadlock chains
        // jcmd deadlock output format:
        //
        // Found one Java-level deadlock:
        // =============================
        // "Thread-1":
        //   waiting to lock monitor 0x..., (object 0x..., a java.lang.Object),
        //   which is held by "Thread-2"
        //
        // "Thread-2":
        //   waiting to lock monitor 0x..., (object 0x..., a java.lang.Object),
        //   which is held by "Thread-1"
        //
        // Java stack information for the threads listed above:
        // ===================================================
        // "Thread-1":
        //   at com.example.Foo.bar(Foo.java:42)
        //   - waiting to lock <0x000000076b2a8f70> (a java.lang.Object)
        //   at ...
        //
        // "Thread-2":
        //   at com.example.Foo.baz(Foo.java:67)
        //   ...

        List<DeadlockChain> chains = new ArrayList<>();
        List<DeadlockThread> currentChain = new ArrayList<>();

        // Phase 1: Parse the deadlock declaration section (before "Java stack information")
        int stackInfoStart = -1;
        List<String[]> threadPairs = new ArrayList<>(); // [threadName, waitingLock, waitingLockClass, heldByThread]

        String currentThread = null;
        String waitingLock = "";
        String waitingLockClass = "";
        String heldBy = null;

        for (int i = deadlockSectionStart + 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("Java stack information")) {
                stackInfoStart = i;
                // Flush last pair
                if (currentThread != null) {
                    threadPairs.add(new String[]{currentThread, waitingLock, waitingLockClass, heldBy != null ? heldBy : ""});
                }
                break;
            }

            if (trimmed.startsWith("\"") && trimmed.endsWith("\":")) {
                // Flush previous thread pair
                if (currentThread != null) {
                    threadPairs.add(new String[]{currentThread, waitingLock, waitingLockClass, heldBy != null ? heldBy : ""});
                }
                currentThread = extractThreadName(trimmed);
                waitingLock = "";
                waitingLockClass = "";
                heldBy = null;
            }

            if (trimmed.startsWith("waiting to lock")) {
                waitingLock = extractLockAddress(trimmed);
                waitingLockClass = extractLockClass(trimmed);
            }

            if (trimmed.startsWith("which is held by")) {
                heldBy = extractThreadName(trimmed);
            }
        }

        // If no stack info section found, flush remaining
        if (stackInfoStart < 0 && currentThread != null) {
            threadPairs.add(new String[]{currentThread, waitingLock, waitingLockClass, heldBy != null ? heldBy : ""});
        }

        // Phase 2: Parse stack info to get state and top frame for each thread
        java.util.Map<String, String> threadStates = new java.util.HashMap<>();
        java.util.Map<String, String> threadStackTops = new java.util.HashMap<>();
        java.util.Map<String, String> threadHeldLocks = new java.util.HashMap<>();
        java.util.Map<String, String> threadHeldLockClasses = new java.util.HashMap<>();

        if (stackInfoStart >= 0) {
            String stackThread = null;
            boolean foundFirstFrame = false;

            for (int i = stackInfoStart + 1; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();

                // End of deadlock info
                if (trimmed.startsWith("Found") && trimmed.contains("total of")) {
                    break;
                }

                if (trimmed.startsWith("\"") && trimmed.endsWith("\":")) {
                    stackThread = extractThreadName(trimmed);
                    foundFirstFrame = false;
                }

                if (stackThread != null && trimmed.startsWith("at ") && !foundFirstFrame) {
                    threadStackTops.put(stackThread, trimmed.substring(3));
                    foundFirstFrame = true;
                }

                if (stackThread != null && trimmed.startsWith("java.lang.Thread.State:")) {
                    String[] parts = trimmed.split(":", 2);
                    if (parts.length == 2) {
                        threadStates.put(stackThread, parts[1].trim().split("\\s+")[0]);
                    }
                }

                if (stackThread != null && trimmed.startsWith("- locked <")) {
                    String addr = extractLockFromStackLine(trimmed);
                    String cls = extractLockClassFromStackLine(trimmed);
                    threadHeldLocks.put(stackThread, addr);
                    threadHeldLockClasses.put(stackThread, cls);
                }
            }
        }

        // Phase 3: Also scan full thread dump for state info if not found in stack section
        if (threadStates.isEmpty()) {
            String scanThread = null;
            for (int i = 0; i < deadlockSectionStart; i++) {
                String trimmed = lines[i].trim();
                if (trimmed.startsWith("\"") && !trimmed.startsWith("\"Found")) {
                    scanThread = extractThreadName(trimmed);
                }
                if (scanThread != null && trimmed.startsWith("java.lang.Thread.State:")) {
                    String[] parts = trimmed.split(":", 2);
                    if (parts.length == 2) {
                        threadStates.put(scanThread, parts[1].trim().split("\\s+")[0]);
                    }
                }
            }
        }

        // Phase 4: Build chains from thread pairs
        // Group threads into chains by following the held-by links
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Map<String, String[]> pairMap = new java.util.HashMap<>();
        for (String[] pair : threadPairs) {
            pairMap.put(pair[0], pair);
        }

        for (String[] pair : threadPairs) {
            String startName = pair[0];
            if (visited.contains(startName)) continue;

            List<DeadlockThread> chain = new ArrayList<>();
            String current = startName;

            while (current != null && !visited.contains(current)) {
                visited.add(current);
                String[] p = pairMap.get(current);
                if (p == null) break;

                chain.add(new DeadlockThread(
                        p[0],
                        threadStates.getOrDefault(p[0], "BLOCKED"),
                        p[1],
                        p[2],
                        threadHeldLocks.getOrDefault(p[0], ""),
                        threadHeldLockClasses.getOrDefault(p[0], ""),
                        threadStackTops.getOrDefault(p[0], "")
                ));

                current = p[3];
                // If we loop back to start, the chain is complete
                if (startName.equals(current)) break;
            }

            if (!chain.isEmpty()) {
                chains.add(new DeadlockChain(List.copyOf(chain)));
            }
        }

        return new DeadlockResult(chains.size(), List.copyOf(chains));
    }

    private static String extractThreadName(String line) {
        int start = line.indexOf('"');
        if (start < 0) return line.trim();
        int end = line.indexOf('"', start + 1);
        if (end < 0) return line.substring(start + 1).trim();
        return line.substring(start + 1, end);
    }

    private static String extractLockAddress(String line) {
        // "waiting to lock monitor 0x00007f89a8003f80" or "waiting to lock <0x00000007ac6e8f70>"
        int ltIdx = line.indexOf('<');
        int gtIdx = line.indexOf('>');
        if (ltIdx >= 0 && gtIdx > ltIdx) {
            return line.substring(ltIdx + 1, gtIdx);
        }
        // Try monitor address format
        int monIdx = line.indexOf("monitor ");
        if (monIdx >= 0) {
            String rest = line.substring(monIdx + 8).trim();
            int comma = rest.indexOf(',');
            return comma >= 0 ? rest.substring(0, comma).trim() : rest.split("\\s+")[0];
        }
        return "";
    }

    private static String extractLockClass(String line) {
        // "(object 0x..., a java.lang.Object)" or "(a java.lang.Object)"
        int aIdx = line.lastIndexOf(" a ");
        if (aIdx >= 0) {
            String rest = line.substring(aIdx + 3).trim();
            int paren = rest.indexOf(')');
            return paren >= 0 ? rest.substring(0, paren).trim() : rest.split("[,\\s]")[0];
        }
        return "";
    }

    private static String extractLockFromStackLine(String line) {
        // "- locked <0x00000007ac6e8f70> (a java.lang.Object)"
        int ltIdx = line.indexOf('<');
        int gtIdx = line.indexOf('>');
        if (ltIdx >= 0 && gtIdx > ltIdx) {
            return line.substring(ltIdx + 1, gtIdx);
        }
        return "";
    }

    private static String extractLockClassFromStackLine(String line) {
        int aIdx = line.lastIndexOf("(a ");
        if (aIdx >= 0) {
            String rest = line.substring(aIdx + 3).trim();
            int paren = rest.indexOf(')');
            return paren >= 0 ? rest.substring(0, paren).trim() : rest;
        }
        return "";
    }
}
