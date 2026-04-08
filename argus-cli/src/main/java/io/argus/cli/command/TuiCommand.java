package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.tui.TuiApp;
import io.argus.core.command.CommandGroup;

import java.util.Map;

/**
 * Launches the k9s-style interactive terminal UI.
 * Shows JVM process list first, then command list after selecting a process.
 *
 * Usage: argus tui
 */
public final class TuiCommand implements Command {

    private final Map<String, Command> allCommands;

    public TuiCommand(Map<String, Command> allCommands) {
        this.allCommands = allCommands;
    }

    @Override public String name() { return "tui"; }
    @Override public CommandGroup group() { return CommandGroup.MONITORING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.tui.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        TuiApp app = new TuiApp(allCommands, config, registry, messages);
        app.run();
    }
}
