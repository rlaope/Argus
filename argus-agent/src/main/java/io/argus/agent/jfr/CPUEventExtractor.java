package io.argus.agent.jfr;

import io.argus.core.event.CPUEvent;
import jdk.jfr.consumer.RecordedEvent;

import java.time.Instant;

/**
 * Extracts CPU event data from JFR RecordedEvent objects.
 *
 * <p>This class handles extraction of CPU load information
 * from the {@code jdk.CPULoad} JFR event.
 */
public final class CPUEventExtractor {

    /**
     * Extracts a CPUEvent from a jdk.CPULoad JFR event.
     *
     * @param event the JFR event
     * @return the extracted CPUEvent
     */
    public CPUEvent extractCPULoad(RecordedEvent event) {
        Instant timestamp = event.getStartTime();
        double jvmUser = extractJvmUser(event);
        double jvmSystem = extractJvmSystem(event);
        double machineTotal = extractMachineTotal(event);

        return CPUEvent.of(timestamp, jvmUser, jvmSystem, machineTotal);
    }

    private double extractJvmUser(RecordedEvent event) {
        // Try jvmUser field
        try {
            return event.getDouble("jvmUser");
        } catch (Exception ignored) {
        }

        // Try jvmUserCPU field
        try {
            return event.getDouble("jvmUserCPU");
        } catch (Exception ignored) {
        }

        return 0.0;
    }

    private double extractJvmSystem(RecordedEvent event) {
        // Try jvmSystem field
        try {
            return event.getDouble("jvmSystem");
        } catch (Exception ignored) {
        }

        // Try jvmSystemCPU field
        try {
            return event.getDouble("jvmSystemCPU");
        } catch (Exception ignored) {
        }

        return 0.0;
    }

    private double extractMachineTotal(RecordedEvent event) {
        // Try machineTotal field
        try {
            return event.getDouble("machineTotal");
        } catch (Exception ignored) {
        }

        // Try machineCPU field
        try {
            return event.getDouble("machineCPU");
        } catch (Exception ignored) {
        }

        // Try systemTotal field
        try {
            return event.getDouble("systemTotal");
        } catch (Exception ignored) {
        }

        return 0.0;
    }

    /**
     * Debug method to print all available fields in a CPU JFR event.
     */
    public void debugPrintFields(RecordedEvent event) {
        System.out.println("[Argus Debug] CPU Event: " + event.getEventType().getName());
        event.getFields().forEach(field -> {
            try {
                Object value = event.getValue(field.getName());
                System.out.printf("  %s (%s) = %s%n",
                        field.getName(), field.getTypeName(), value);
            } catch (Exception e) {
                System.out.printf("  %s (%s) = ERROR: %s%n",
                        field.getName(), field.getTypeName(), e.getMessage());
            }
        });
    }
}
