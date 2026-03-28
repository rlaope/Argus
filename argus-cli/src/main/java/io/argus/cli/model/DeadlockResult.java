package io.argus.cli.model;

import java.util.List;

/**
 * Deadlock detection result with detailed chain information.
 */
public final class DeadlockResult {
    private final int deadlockCount;
    private final List<DeadlockChain> chains;

    public DeadlockResult(int deadlockCount, List<DeadlockChain> chains) {
        this.deadlockCount = deadlockCount;
        this.chains = chains;
    }

    public int deadlockCount() { return deadlockCount; }
    public List<DeadlockChain> chains() { return chains; }

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
