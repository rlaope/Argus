package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.core.command.CommandGroup;

/**
 * Base interface for all Argus CLI commands.
 *
 * <p>Each command declares its {@link CommandGroup} for categorized help output
 * and shared metadata with the server's SPI-based command system.
 */
public interface Command {

    /**
     * The command name used to route from the CLI entry point.
     */
    String name();

    /**
     * Logical group for categorized help output. Shared with server SPI via
     * {@link io.argus.core.command.CommandGroup}.
     */
    default CommandGroup group() { return CommandGroup.MONITORING; }

    /**
     * Short description shown in the help/usage output.
     */
    String description(Messages messages);

    /**
     * Executes the command with the provided args, config, registry, and messages.
     */
    void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages);
}
