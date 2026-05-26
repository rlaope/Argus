package io.argus.server.command;

import io.argus.core.command.CommandRegistry;
import io.argus.core.command.DiagnosticCommand;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes diagnostic commands using the SPI-based {@link CommandRegistry}.
 *
 * <p>All commands are discovered via {@link java.util.ServiceLoader} — no hardcoded
 * switch statements or static maps. Adding a new command requires only:
 * <ol>
 *   <li>A class implementing {@link DiagnosticCommand}</li>
 *   <li>One line in {@code META-INF/services/io.argus.core.command.DiagnosticCommand}</li>
 * </ol>
 *
 * <p>The dashboard console automatically picks up new commands via {@code /api/commands}.
 */
public final class ServerCommandExecutor {

    private static final CommandRegistry REGISTRY = CommandRegistry.load();
    private static final ServerContext CONTEXT = new ServerContext();

    /**
     * Returns all commands that are suitable for one-click invocation from the web Console.
     * Filters out commands that override {@link DiagnosticCommand#supportsWebConsole()} to false
     * (destructive ones like {@code gcrun}, file-output, long-running, etc.).
     * Used by {@code /api/commands} endpoint.
     */
    public static Map<String, CommandInfo> getAvailableCommands() {
        Map<String, CommandInfo> result = new LinkedHashMap<>();
        for (DiagnosticCommand cmd : REGISTRY.all()) {
            if (!cmd.supportsWebConsole()) continue;
            result.put(cmd.id(), new CommandInfo(
                    cmd.id(),
                    cmd.group().displayName().toLowerCase(),
                    cmd.description()));
        }
        return result;
    }

    /**
     * Execute a command by id and return the text output.
     * Used by {@code /api/exec?cmd=xxx} endpoint.
     *
     * <p>Defense in depth: rejects any command whose {@link DiagnosticCommand#supportsWebConsole()}
     * returns false by throwing {@link WebConsoleRejectedException}, even if the caller knows
     * its id. Keeps {@code /api/exec} aligned with {@code /api/commands}, and routes the
     * rejection through the HTTP error path so the frontend renders it as an error instead of
     * a success.
     *
     * @throws WebConsoleRejectedException if the command exists but opts out of the web console
     */
    public static String execute(String command) {
        DiagnosticCommand cmd = REGISTRY.find(command);
        if (cmd == null) {
            return "Unknown command: " + command;
        }
        if (!cmd.supportsWebConsole()) {
            throw new WebConsoleRejectedException(command);
        }
        return cmd.execute(CONTEXT);
    }

    /**
     * Returns process info for the current JVM. Used by dashboard header.
     */
    public static String getProcessInfo() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        long pid = rt.getPid();
        String mainClass = System.getProperty("sun.java.command", "unknown");
        if (mainClass.contains(" ")) mainClass = mainClass.substring(0, mainClass.indexOf(' '));
        return pid + " " + mainClass;
    }

    /**
     * Command metadata for API serialization. Kept for backward compatibility
     * with existing {@code /api/commands} JSON format.
     */
    public static final class CommandInfo {
        private final String name;
        private final String group;
        private final String description;

        public CommandInfo(String name, String group, String description) {
            this.name = name;
            this.group = group;
            this.description = description;
        }

        public String name() { return name; }
        public String group() { return group; }
        public String description() { return description; }
    }
}
