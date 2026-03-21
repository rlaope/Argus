package io.argus.cli;

import io.argus.cli.command.Command;
import io.argus.cli.command.GcCommand;
import io.argus.cli.command.HeapCommand;
import io.argus.cli.command.HistoCommand;
import io.argus.cli.command.InfoCommand;
import io.argus.cli.command.InitCommand;
import io.argus.cli.command.PsCommand;
import io.argus.cli.command.ThreadsCommand;
import io.argus.cli.command.TopCommand;
import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main entry point for the Argus CLI.
 *
 * <p>Usage: {@code argus <command> [<pid>] [options]}
 *
 * <p>Global options are parsed first; the remaining args are forwarded to the command.
 */
public final class ArgusCli {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 9202;

    public static void main(String[] args) {
        CliConfig base = CliConfig.load();

        // Global option state (mutable during parse)
        String source = base.defaultSource();
        boolean color = base.color();
        String lang = base.lang();
        String format = base.format();
        String host = DEFAULT_HOST;
        int port = base.defaultPort();
        boolean help = false;
        String commandName = null;
        int commandArgStart = 0;

        // Parse global options up to the first non-option token (the command name)
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (commandName == null && !arg.startsWith("--")) {
                commandName = arg;
                commandArgStart = i + 1;
                break;
            }

            if (arg.startsWith("--source=")) {
                source = arg.substring(9);
            } else if (arg.equals("--no-color")) {
                color = false;
            } else if (arg.startsWith("--lang=")) {
                lang = arg.substring(7);
            } else if (arg.startsWith("--format=")) {
                format = arg.substring(9);
            } else if (arg.startsWith("--host=")) {
                host = arg.substring(7);
            } else if (arg.equals("--host") && i + 1 < args.length) {
                host = args[++i];
            } else if (arg.startsWith("--port=")) {
                try { port = Integer.parseInt(arg.substring(7)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--port") && i + 1 < args.length) {
                try { port = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--help") || arg.equals("-h")) {
                help = true;
            }
        }

        // Apply CLI flag overrides on top of file-based config
        CliConfig config = new CliConfig(lang, source, color, format, port);

        Messages messages = new Messages(lang);
        ProviderRegistry registry = new ProviderRegistry(host, port);

        // Register all commands
        Map<String, Command> commands = new LinkedHashMap<>();
        register(commands, new InitCommand());
        register(commands, new PsCommand());
        register(commands, new HistoCommand());
        register(commands, new ThreadsCommand());
        register(commands, new GcCommand());
        register(commands, new HeapCommand());
        register(commands, new InfoCommand());
        register(commands, new TopCommand());

        if (help || commandName == null) {
            printUsage(commands, messages);
            return;
        }

        Command command = commands.get(commandName.toLowerCase());
        if (command == null) {
            System.err.println("Unknown command: " + commandName);
            System.err.println("Run 'argus --help' to see available commands.");
            System.exit(1);
        }

        // Build sub-args: everything after the command name
        String[] subArgs = new String[args.length - commandArgStart];
        System.arraycopy(args, commandArgStart, subArgs, 0, subArgs.length);

        command.execute(subArgs, config, registry, messages);
    }

    private static void register(Map<String, Command> map, Command cmd) {
        map.put(cmd.name(), cmd);
    }

    private static void printUsage(Map<String, Command> commands, Messages messages) {
        System.out.println("Argus CLI - JVM Diagnostic Tool");
        System.out.println();
        System.out.println("Usage: argus <command> [<pid>] [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  init          Initialize CLI configuration");
        System.out.println("  ps            List running JVM processes");
        System.out.println("  histo <pid>   Show heap object histogram");
        System.out.println("  threads <pid> Show thread dump summary");
        System.out.println("  gc <pid>      Show GC statistics");
        System.out.println("  heap <pid>    Show heap memory usage");
        System.out.println("  info <pid>    Show JVM information");
        System.out.println("  top           Real-time JVM monitoring");
        System.out.println();
        System.out.println("Global Options:");
        System.out.println("  --source=auto|agent|jdk   Data source (default: auto)");
        System.out.println("  --no-color                Disable colors");
        System.out.println("  --lang=en|ko|ja|zh        Output language");
        System.out.println("  --format=table|json       Output format (default: table)");
        System.out.println("  --host HOST               Agent host (default: localhost)");
        System.out.println("  --port PORT               Agent port (default: 9202)");
        System.out.println("  --help                    Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  argus ps");
        System.out.println("  argus histo 12345");
        System.out.println("  argus histo 12345 --top 50");
        System.out.println("  argus threads 12345 --source=agent");
        System.out.println("  argus gc 12345 --format=json");
        System.out.println("  argus top --port 9202");
    }
}
