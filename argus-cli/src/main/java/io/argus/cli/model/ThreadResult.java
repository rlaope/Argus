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
    private final int daemonThreads;
    private final int peakThreads;
    private final Map<String, Integer> stateDistribution;
    private final List<DeadlockInfo> deadlocks;
    private final List<ThreadInfo> threads;

    public ThreadResult(int totalThreads, int virtualThreads, int platformThreads,
                        Map<String, Integer> stateDistribution,
                        List<DeadlockInfo> deadlocks, List<ThreadInfo> threads) {
        this(totalThreads, virtualThreads, platformThreads, 0, 0, stateDistribution, deadlocks, threads);
    }

    public ThreadResult(int totalThreads, int virtualThreads, int platformThreads,
                        int daemonThreads, int peakThreads,
                        Map<String, Integer> stateDistribution,
                        List<DeadlockInfo> deadlocks, List<ThreadInfo> threads) {
        this.totalThreads = totalThreads;
        this.virtualThreads = virtualThreads;
        this.platformThreads = platformThreads;
        this.daemonThreads = daemonThreads;
        this.peakThreads = peakThreads;
        this.stateDistribution = stateDistribution;
        this.deadlocks = deadlocks;
        this.threads = threads;
    }

    public int totalThreads() { return totalThreads; }
    public int virtualThreads() { return virtualThreads; }
    public int platformThreads() { return platformThreads; }
    public int daemonThreads() { return daemonThreads; }
    public int peakThreads() { return peakThreads; }
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
        private final boolean daemon;
        private final long cpuTimeNs;

        public ThreadInfo(String name, String state, boolean virtual) {
            this(name, state, virtual, false, -1);
        }

        public ThreadInfo(String name, String state, boolean virtual, boolean daemon, long cpuTimeNs) {
            this.name = name;
            this.state = state;
            this.virtual = virtual;
            this.daemon = daemon;
            this.cpuTimeNs = cpuTimeNs;
        }

        public String name() { return name; }
        public String state() { return state; }
        public boolean virtual() { return virtual; }
        public boolean daemon() { return daemon; }
        public long cpuTimeNs() { return cpuTimeNs; }
    }
}
