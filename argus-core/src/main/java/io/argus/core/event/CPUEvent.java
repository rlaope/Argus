package io.argus.core.event;

import java.time.Instant;

/**
 * Represents a CPU load event captured by the Argus agent.
 *
 * @param timestamp    the event timestamp
 * @param jvmUser      JVM user CPU usage (0.0-1.0)
 * @param jvmSystem    JVM system CPU usage (0.0-1.0)
 * @param machineTotal total machine CPU usage (0.0-1.0)
 */
public final class CPUEvent {
    private final Instant timestamp;
    private final double jvmUser;
    private final double jvmSystem;
    private final double machineTotal;

    public CPUEvent(Instant timestamp, double jvmUser, double jvmSystem, double machineTotal) {
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
     * Creates a CPU load event.
     */
    public static CPUEvent of(Instant timestamp, double jvmUser,
                              double jvmSystem, double machineTotal) {
        return new CPUEvent(timestamp, jvmUser, jvmSystem, machineTotal);
    }

    /**
     * Returns the total JVM CPU usage (user + system).
     */
    public double jvmTotal() {
        return jvmUser + jvmSystem;
    }

    /**
     * Returns the JVM user CPU usage as a percentage (0-100).
     */
    public double jvmUserPercent() {
        return jvmUser * 100.0;
    }

    /**
     * Returns the JVM system CPU usage as a percentage (0-100).
     */
    public double jvmSystemPercent() {
        return jvmSystem * 100.0;
    }

    /**
     * Returns the total JVM CPU usage as a percentage (0-100).
     */
    public double jvmTotalPercent() {
        return jvmTotal() * 100.0;
    }

    /**
     * Returns the machine total CPU usage as a percentage (0-100).
     */
    public double machineTotalPercent() {
        return machineTotal * 100.0;
    }
}
