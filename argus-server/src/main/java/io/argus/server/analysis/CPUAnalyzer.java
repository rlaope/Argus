package io.argus.server.analysis;

import io.argus.core.event.CPUEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Analyzes CPU load events and provides statistics.
 *
 * <p>Tracks CPU usage over time and maintains a rolling window
 * of CPU snapshots for charting.
 */
public final class CPUAnalyzer {

    private static final int MAX_HISTORY_SIZE = 60; // 60 seconds of data at 1s intervals

    private final List<CPUSnapshot> history = new CopyOnWriteArrayList<>();
    private final AtomicLong totalSamples = new AtomicLong(0);

    // Current state
    private volatile double currentJvmUser = 0;
    private volatile double currentJvmSystem = 0;
    private volatile double currentMachineTotal = 0;
    private volatile Instant lastUpdateTime = null;

    // Peak tracking
    private volatile double peakJvmTotal = 0;
    private volatile double peakMachineTotal = 0;

    /**
     * Records a CPU event for analysis.
     *
     * @param event the CPU event to record
     */
    public void recordCPUEvent(CPUEvent event) {
        totalSamples.incrementAndGet();

        // Update current state
        currentJvmUser = event.jvmUser();
        currentJvmSystem = event.jvmSystem();
        currentMachineTotal = event.machineTotal();
        lastUpdateTime = event.timestamp();

        // Track peaks
        double jvmTotal = event.jvmTotal();
        if (jvmTotal > peakJvmTotal) {
            peakJvmTotal = jvmTotal;
        }
        if (event.machineTotal() > peakMachineTotal) {
            peakMachineTotal = event.machineTotal();
        }

        // Add to history
        CPUSnapshot snapshot = new CPUSnapshot(
                event.timestamp(),
                event.jvmUser(),
                event.jvmSystem(),
                event.machineTotal()
        );

        history.add(snapshot);
        while (history.size() > MAX_HISTORY_SIZE) {
            history.removeFirst();
        }
    }

    /**
     * Returns the CPU analysis results.
     *
     * @return the CPU analysis result
     */
    public CPUAnalysisResult getAnalysis() {
        // Calculate averages from history
        double avgJvmUser = 0;
        double avgJvmSystem = 0;
        double avgMachineTotal = 0;

        if (!history.isEmpty()) {
            for (CPUSnapshot snapshot : history) {
                avgJvmUser += snapshot.jvmUser();
                avgJvmSystem += snapshot.jvmSystem();
                avgMachineTotal += snapshot.machineTotal();
            }
            int size = history.size();
            avgJvmUser /= size;
            avgJvmSystem /= size;
            avgMachineTotal /= size;
        }

        return new CPUAnalysisResult(
                totalSamples.get(),
                currentJvmUser,
                currentJvmSystem,
                currentJvmUser + currentJvmSystem,
                currentMachineTotal,
                avgJvmUser + avgJvmSystem,
                avgMachineTotal,
                peakJvmTotal,
                peakMachineTotal,
                new ArrayList<>(history),
                lastUpdateTime
        );
    }

    /**
     * Returns the CPU history for charting.
     *
     * @return list of CPU snapshots
     */
    public List<CPUSnapshot> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Returns the current JVM CPU usage (0.0-1.0).
     *
     * @return current JVM CPU usage
     */
    public double getCurrentJvmCpu() {
        return currentJvmUser + currentJvmSystem;
    }

    /**
     * Returns the current machine CPU usage (0.0-1.0).
     *
     * @return current machine CPU usage
     */
    public double getCurrentMachineCpu() {
        return currentMachineTotal;
    }

    /**
     * Clears all recorded data.
     */
    public void clear() {
        history.clear();
        totalSamples.set(0);
        currentJvmUser = 0;
        currentJvmSystem = 0;
        currentMachineTotal = 0;
        lastUpdateTime = null;
        peakJvmTotal = 0;
        peakMachineTotal = 0;
    }

    /**
     * Snapshot of CPU state at a point in time.
     */
    public static final class CPUSnapshot {
        private final Instant timestamp;
        private final double jvmUser;
        private final double jvmSystem;
        private final double machineTotal;

        public CPUSnapshot(Instant timestamp, double jvmUser, double jvmSystem, double machineTotal) {
            this.timestamp = timestamp;
            this.jvmUser = jvmUser;
            this.jvmSystem = jvmSystem;
            this.machineTotal = machineTotal;
        }

        public Instant timestamp() { return timestamp; }
        public double jvmUser() { return jvmUser; }
        public double jvmSystem() { return jvmSystem; }
        public double machineTotal() { return machineTotal; }

        /**
         * Returns the total JVM CPU usage.
         */
        public double jvmTotal() {
            return jvmUser + jvmSystem;
        }

        /**
         * Returns the JVM CPU percentage.
         */
        public double jvmPercent() {
            return jvmTotal() * 100;
        }

        /**
         * Returns the machine CPU percentage.
         */
        public double machinePercent() {
            return machineTotal * 100;
        }
    }

    /**
     * Result of CPU analysis.
     */
    public static final class CPUAnalysisResult {
        private final long totalSamples;
        private final double currentJvmUser;
        private final double currentJvmSystem;
        private final double currentJvmTotal;
        private final double currentMachineTotal;
        private final double avgJvmTotal;
        private final double avgMachineTotal;
        private final double peakJvmTotal;
        private final double peakMachineTotal;
        private final List<CPUSnapshot> history;
        private final Instant lastUpdateTime;

        public CPUAnalysisResult(long totalSamples, double currentJvmUser, double currentJvmSystem,
                                 double currentJvmTotal, double currentMachineTotal,
                                 double avgJvmTotal, double avgMachineTotal,
                                 double peakJvmTotal, double peakMachineTotal,
                                 List<CPUSnapshot> history, Instant lastUpdateTime) {
            this.totalSamples = totalSamples;
            this.currentJvmUser = currentJvmUser;
            this.currentJvmSystem = currentJvmSystem;
            this.currentJvmTotal = currentJvmTotal;
            this.currentMachineTotal = currentMachineTotal;
            this.avgJvmTotal = avgJvmTotal;
            this.avgMachineTotal = avgMachineTotal;
            this.peakJvmTotal = peakJvmTotal;
            this.peakMachineTotal = peakMachineTotal;
            this.history = history;
            this.lastUpdateTime = lastUpdateTime;
        }

        public long totalSamples() { return totalSamples; }
        public double currentJvmUser() { return currentJvmUser; }
        public double currentJvmSystem() { return currentJvmSystem; }
        public double currentJvmTotal() { return currentJvmTotal; }
        public double currentMachineTotal() { return currentMachineTotal; }
        public double avgJvmTotal() { return avgJvmTotal; }
        public double avgMachineTotal() { return avgMachineTotal; }
        public double peakJvmTotal() { return peakJvmTotal; }
        public double peakMachineTotal() { return peakMachineTotal; }
        public List<CPUSnapshot> history() { return history; }
        public Instant lastUpdateTime() { return lastUpdateTime; }

        /**
         * Returns the current JVM CPU percentage.
         */
        public double currentJvmPercent() {
            return currentJvmTotal * 100;
        }

        /**
         * Returns the current machine CPU percentage.
         */
        public double currentMachinePercent() {
            return currentMachineTotal * 100;
        }
    }
}
