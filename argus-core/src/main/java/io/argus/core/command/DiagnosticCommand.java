package io.argus.core.command;

/**
 * Service Provider Interface for diagnostic commands.
 *
 * <p>Each command is a single class that declares its metadata (id, group, description)
 * and knows how to execute in a given {@link CommandContext}. Commands are discovered
 * automatically via {@link java.util.ServiceLoader}.
 *
 * <p>Implementations should be stateless and thread-safe. The {@link #execute} method
 * returns a plain text result string; JSON formatting is handled by the caller.
 *
 * <p>Example:
 * <pre>
 * public final class BuffersCommand implements DiagnosticCommand {
 *     public String id()          { return "buffers"; }
 *     public CommandGroup group() { return CommandGroup.MEMORY; }
 *     public String description() { return "NIO buffer pool statistics"; }
 *
 *     public String execute(CommandContext ctx) {
 *         // implementation
 *     }
 * }
 * </pre>
 *
 * @see CommandRegistry
 * @see CommandContext
 */
public interface DiagnosticCommand {

    /** Unique command identifier (lowercase, no spaces). Used in CLI and API routing. */
    String id();

    /** Logical group for UI categorization. */
    CommandGroup group();

    /** One-line description shown in help and command listings. */
    String description();

    /**
     * Execute the command in the given context.
     *
     * @param ctx execution context (in-process or external)
     * @return command output as plain text
     * @throws UnsupportedOperationException if this command doesn't support the given context type
     */
    String execute(CommandContext ctx);

    /**
     * Whether this command supports in-process execution (agent/server mode).
     * Default: true.
     */
    default boolean supportsInProcess() { return true; }

    /**
     * Whether this command supports external execution (CLI mode).
     * Default: true.
     */
    default boolean supportsExternal() { return true; }
}
