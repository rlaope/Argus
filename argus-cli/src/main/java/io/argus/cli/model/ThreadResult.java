package io.argus.cli.model;

import java.util.List;
import java.util.Map;

/**
 * Thread dump summary result.
 */
public final class ThreadResult {
    private final int totalThreads;
    private final int virtualThreads;
    private final int platformThreads;
    private final Map<String, Integer> stateDistribution;
    private final List<DeadlockInfo> deadlocks;
    private final List<ThreadInfo> threads;

    public ThreadResult(int totalThreads, int virtualThreads, int platformThreads,
                        Map<String, Integer> stateDistribution,
                        List<DeadlockInfo> deadlocks, List<ThreadInfo> threads) {
        this.totalThreads = totalThreads;
        this.virtualThreads = virtualThreads;
        this.platformThreads = platformThreads;
        this.stateDistribution = stateDistribution;
        this.deadlocks = deadlocks;
        this.threads = threads;
    }

    public int totalThreads() { return totalThreads; }
    public int virtualThreads() { return virtualThreads; }
    public int platformThreads() { return platformThreads; }
    public Map<String, Integer> stateDistribution() { return stateDistribution; }
    public List<DeadlockInfo> deadlocks() { return deadlocks; }
    public List<ThreadInfo> threads() { return threads; }

    public static final class DeadlockInfo {
        private final String thread1;
        private final String thread2;
        private final String lockClass;

        public DeadlockInfo(String thread1, String thread2, String lockClass) {
            this.thread1 = thread1;
            this.thread2 = thread2;
            this.lockClass = lockClass;
        }

        public String thread1() { return thread1; }
        public String thread2() { return thread2; }
        public String lockClass() { return lockClass; }
    }

    public static final class ThreadInfo {
        private final String name;
        private final String state;
        private final boolean virtual;

        public ThreadInfo(String name, String state, boolean virtual) {
            this.name = name;
            this.state = state;
            this.virtual = virtual;
        }

        public String name() { return name; }
        public String state() { return state; }
        public boolean virtual() { return virtual; }
    }
}
