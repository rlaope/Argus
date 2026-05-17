package io.argus.cli.model;

import io.argus.diagnostics.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

import java.util.List;

/**
 * Deadlock detection result with detailed chain information.
 */
public final class DeadlockResult implements JsonWritable {
    private final int deadlockCount;
    private final List<DeadlockChain> chains;

    public DeadlockResult(int deadlockCount, List<DeadlockChain> chains) {
        this.deadlockCount = deadlockCount;
        this.chains = chains;
    }

    public int deadlockCount() { return deadlockCount; }
    public List<DeadlockChain> chains() { return chains; }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"deadlockCount\":").append(deadlockCount)
           .append(",\"chains\":[");
        for (int c = 0; c < chains.size(); c++) {
            DeadlockChain chain = chains.get(c);
            if (c > 0) out.append(',');
            out.append("{\"threads\":[");
            for (int t = 0; t < chain.threads().size(); t++) {
                DeadlockThread thread = chain.threads().get(t);
                if (t > 0) out.append(',');
                out.append("{\"name\":\"").append(RichRenderer.escapeJson(thread.name())).append('"')
                   .append(",\"state\":\"").append(RichRenderer.escapeJson(thread.state())).append('"')
                   .append(",\"waitingLock\":\"").append(RichRenderer.escapeJson(thread.waitingLock())).append('"')
                   .append(",\"waitingLockClass\":\"").append(RichRenderer.escapeJson(thread.waitingLockClass())).append('"')
                   .append(",\"heldLock\":\"").append(RichRenderer.escapeJson(thread.heldLock())).append('"')
                   .append(",\"heldLockClass\":\"").append(RichRenderer.escapeJson(thread.heldLockClass())).append('"')
                   .append(",\"stackTop\":\"").append(RichRenderer.escapeJson(thread.stackTop())).append('"')
                   .append('}');
            }
            out.append("]}");
        }
        out.append("]}");
    }

    /**
     * A single deadlock chain involving two or more threads.
     */
    public static final class DeadlockChain {
        private final List<DeadlockThread> threads;

        public DeadlockChain(List<DeadlockThread> threads) {
            this.threads = threads;
        }

        public List<DeadlockThread> threads() { return threads; }
    }

    /**
     * A thread involved in a deadlock, with the lock it holds and the lock it waits for.
     */
    public static final class DeadlockThread {
        private final String name;
        private final String state;
        private final String waitingLock;
        private final String waitingLockClass;
        private final String heldLock;
        private final String heldLockClass;
        private final String stackTop;

        public DeadlockThread(String name, String state, String waitingLock, String waitingLockClass,
                              String heldLock, String heldLockClass, String stackTop) {
            this.name = name;
            this.state = state;
            this.waitingLock = waitingLock;
            this.waitingLockClass = waitingLockClass;
            this.heldLock = heldLock;
            this.heldLockClass = heldLockClass;
            this.stackTop = stackTop;
        }

        public String name() { return name; }
        public String state() { return state; }
        public String waitingLock() { return waitingLock; }
        public String waitingLockClass() { return waitingLockClass; }
        public String heldLock() { return heldLock; }
        public String heldLockClass() { return heldLockClass; }
        public String stackTop() { return stackTop; }
    }
}
