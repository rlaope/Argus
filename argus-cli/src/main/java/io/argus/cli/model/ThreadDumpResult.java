package io.argus.cli.model;

import java.util.List;

/**
 * Result of a full thread dump capture via jcmd Thread.print.
 */
public final class ThreadDumpResult {

    private final int totalThreads;
    private final List<ThreadInfo> threads;
    private final String rawOutput;

    public ThreadDumpResult(int totalThreads, List<ThreadInfo> threads, String rawOutput) {
        this.totalThreads = totalThreads;
        this.threads = threads;
        this.rawOutput = rawOutput;
    }

    public int totalThreads() { return totalThreads; }
    public List<ThreadInfo> threads() { return threads; }
    public String rawOutput() { return rawOutput; }

    public static final class ThreadInfo {
        private final String name;
        private final long tid;
        private final long nid;
        private final String state;
        private final boolean daemon;
        private final int priority;
        private final List<String> stackTrace;
        private final List<String> locksHeld;
        private final String waitingOn;

        public ThreadInfo(String name, long tid, long nid, String state, boolean daemon,
                          int priority, List<String> stackTrace, List<String> locksHeld, String waitingOn) {
            this.name = name;
            this.tid = tid;
            this.nid = nid;
            this.state = state;
            this.daemon = daemon;
            this.priority = priority;
            this.stackTrace = stackTrace;
            this.locksHeld = locksHeld;
            this.waitingOn = waitingOn;
        }

        public String name() { return name; }
        public long tid() { return tid; }
        public long nid() { return nid; }
        public String state() { return state; }
        public boolean daemon() { return daemon; }
        public int priority() { return priority; }
        public List<String> stackTrace() { return stackTrace; }
        public List<String> locksHeld() { return locksHeld; }
        public String waitingOn() { return waitingOn; }
    }
}
