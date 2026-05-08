package io.argus.core.command;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Central registry for diagnostic commands. Discovers commands via {@link ServiceLoader}
 * and provides lookup by id, group, or execution capability.
 *
 * <p>Usage:
 * <pre>
 * CommandRegistry registry = CommandRegistry.load();
 * String result = registry.execute("buffers", context);
 * </pre>
 *
 * <p>Thread-safe after construction. Commands are loaded once and cached.
 */
public final class CommandRegistry {

    private final Map<String, DiagnosticCommand> commands;

    private CommandRegistry(Map<String, DiagnosticCommand> commands) {
        this.commands = commands;
    }

    /**
     * Load all commands via ServiceLoader discovery.
     * Scans the classpath for {@code META-INF/services/io.argus.core.command.DiagnosticCommand}.
     */
    public static CommandRegistry load() {
        Map<String, DiagnosticCommand> map = new LinkedHashMap<>();
        for (DiagnosticCommand cmd : ServiceLoader.load(DiagnosticCommand.class)) {
            map.put(cmd.id(), cmd);
        }
        return new CommandRegistry(Collections.unmodifiableMap(map));
    }

    /**
     * Create a registry from explicitly provided commands (for testing or manual registration).
     */
    public static CommandRegistry of(DiagnosticCommand... commands) {
        Map<String, DiagnosticCommand> map = new LinkedHashMap<>();
        for (DiagnosticCommand cmd : commands) {
            map.put(cmd.id(), cmd);
        }
        return new CommandRegistry(Collections.unmodifiableMap(map));
    }

    /** Find a command by id, or null if not found. */
    public DiagnosticCommand find(String id) {
        return commands.get(id);
    }

    /** Execute a command by id. Returns the output string. */
    public String execute(String id, CommandContext ctx) {
        DiagnosticCommand cmd = commands.get(id);
        if (cmd == null) {
            return "Unknown command: " + id;
        }
        return cmd.execute(ctx);
    }

    /** All registered commands. */
    public Collection<DiagnosticCommand> all() {
        return commands.values();
    }

    /** Commands filtered by group. */
    public List<DiagnosticCommand> byGroup(CommandGroup group) {
        return commands.values().stream()
                .filter(cmd -> cmd.group() == group)
                .collect(Collectors.toList());
    }

    /** Commands that support in-process execution. */
    public List<DiagnosticCommand> inProcessCommands() {
        return commands.values().stream()
                .filter(DiagnosticCommand::supportsInProcess)
                .collect(Collectors.toList());
    }

    /** Commands that support external execution. */
    public List<DiagnosticCommand> externalCommands() {
        return commands.values().stream()
                .filter(DiagnosticCommand::supportsExternal)
                .collect(Collectors.toList());
    }

    /** Number of registered commands. */
    public int size() {
        return commands.size();
    }
}
