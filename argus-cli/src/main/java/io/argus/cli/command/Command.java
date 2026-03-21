package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;

/**
 * Base interface for all Argus CLI commands.
 */
public interface Command {

    /**
     * The command name used to route from the CLI entry point.
     */
    String name();

    /**
     * Short description shown in the help/usage output.
     */
    String description(Messages messages);

    /**
     * Executes the command with the provided args, config, registry, and messages.
     *
     * @param args     command-specific arguments (everything after the command name)
     * @param config   resolved CLI configuration (may include flag overrides)
     * @param registry provider registry for obtaining data providers
     * @param messages i18n message bundle
     */
    void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages);
}
