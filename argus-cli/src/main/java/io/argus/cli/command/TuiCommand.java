package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.tui.TuiApp;
import io.argus.core.command.CommandGroup;

import java.util.Map;

/**
 * Launches the k9s-style interactive terminal UI.
 * Browse all commands by category, see descriptions, execute with Enter.
 *
 * Usage: argus tui [pid]
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
        long pid = 0;
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                try { pid = Long.parseLong(arg); } catch (NumberFormatException ignored) {}
            }
        }

        TuiApp app = new TuiApp(allCommands, config, registry, messages, pid);
        app.run();
    }
}
