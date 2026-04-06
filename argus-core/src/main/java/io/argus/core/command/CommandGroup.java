package io.argus.core.command;

/**
 * Logical grouping for diagnostic commands.
 * Used by UI to organize command buttons and by help output for categorization.
 */
public enum CommandGroup {
    PROCESS("Process & System"),
    MEMORY("Memory & GC"),
    THREADS("Threads"),
    RUNTIME("Runtime & Class Loading"),
    PROFILING("Profiling & Diagnostics"),
    MONITORING("Monitoring");

    private final String displayName;

    CommandGroup(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }
}
