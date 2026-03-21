package io.argus.cli.model;

import java.util.List;
import java.util.Map;

/**
 * Thread dump summary result.
 */
public record ThreadResult(
        int totalThreads,
        int virtualThreads,
        int platformThreads,
        Map<String, Integer> stateDistribution,
        List<DeadlockInfo> deadlocks,
        List<ThreadInfo> threads
) {
    public record DeadlockInfo(String thread1, String thread2, String lockClass) {}

    public record ThreadInfo(String name, String state, boolean virtual) {}
}
