package io.argus.cli;

import io.argus.cli.command.Command;
import io.argus.cli.command.CommandExitException;
import io.argus.cli.command.BuffersCommand;
import io.argus.cli.command.CiCommand;
import io.argus.cli.command.ClassStatCommand;
import io.argus.cli.command.CompareCommand;
import io.argus.cli.command.CompilerCommand;
import io.argus.cli.command.CompilerQueueCommand;
import io.argus.cli.command.EventsCommand;
import io.argus.cli.command.DeadlockCommand;
import io.argus.cli.command.DiffCommand;
import io.argus.cli.command.DoctorCommand;
import io.argus.cli.command.DynLibsCommand;
import io.argus.cli.command.EnvCommand;
import io.argus.cli.command.FinalizerCommand;
import io.argus.cli.command.FlameCommand;
import io.argus.cli.command.WatchCommand;
import io.argus.cli.command.GcCauseCommand;
import io.argus.cli.command.GcLogCommand;
import io.argus.cli.command.GcRunCommand;
import io.argus.cli.command.GcCommand;
import io.argus.cli.command.GcNewCommand;
import io.argus.cli.command.GcUtilCommand;
import io.argus.cli.command.HeapAnalyzeCommand;
import io.argus.cli.command.HeapCommand;
import io.argus.cli.command.HeapDumpCommand;
import io.argus.cli.command.HistoCommand;
import io.argus.cli.command.InfoCommand;
import io.argus.cli.command.InitCommand;
import io.argus.cli.command.JfrAnalyzeCommand;
import io.argus.cli.command.LoggerCommand;
import io.argus.cli.command.ClassLoaderCommand;
import io.argus.cli.command.JfrCommand;
import io.argus.cli.command.JmxCommand;
import io.argus.cli.command.MBeanCommand;
import io.argus.cli.command.MetaspaceCommand;
import io.argus.cli.command.PerfCounterCommand;
import io.argus.cli.command.NmtCommand;
import io.argus.cli.command.PoolCommand;
import io.argus.cli.command.ProfileCommand;
import io.argus.cli.command.PsCommand;
import io.argus.cli.command.ReportCommand;
import io.argus.cli.command.SearchClassCommand;
import io.argus.cli.command.SlowlogCommand;
import io.argus.cli.command.StringTableCommand;
import io.argus.cli.command.SuggestCommand;
import io.argus.cli.command.SymbolTableCommand;
import io.argus.cli.command.SysPropsCommand;
import io.argus.cli.command.ThreadDumpCommand;
import io.argus.cli.command.ThreadsCommand;
import io.argus.cli.command.TopCommand;
import io.argus.cli.command.TuiCommand;
import io.argus.cli.command.VmFlagCommand;
import io.argus.cli.command.VmLogCommand;
import io.argus.cli.command.VmSetCommand;
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

    private static final String VERSION = resolveVersion();

    private static String resolveVersion() {
        Package pkg = ArgusCli.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null) {
            return pkg.getImplementationVersion();
        }
        return "dev";
    }
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
        boolean version = false;
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
            } else if (arg.equals("--version") || arg.equals("-v") || arg.equals("-V")) {
                version = true;
            }
        }

        // Warn on invalid --port
        if (args.length > 0) {
            for (String a : args) {
                if (a.startsWith("--port=")) {
                    try { Integer.parseInt(a.substring(7)); } catch (NumberFormatException e) {
                        System.err.println("Warning: invalid --port value '" + a.substring(7) + "', using default: " + port);
                    }
                }
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
        register(commands, new GcUtilCommand());
        register(commands, new HeapCommand());
        register(commands, new SysPropsCommand());
        register(commands, new VmFlagCommand());
        register(commands, new NmtCommand());
        register(commands, new ClassLoaderCommand());
        register(commands, new ProfileCommand());
        register(commands, new JfrCommand());
        register(commands, new DiffCommand());
        register(commands, new ReportCommand());
        register(commands, new DoctorCommand());
        register(commands, new CiCommand());
        register(commands, new CompareCommand());
        register(commands, new SlowlogCommand());
        register(commands, new GcLogCommand());
        register(commands, new FlameCommand());
        register(commands, new SuggestCommand());
        register(commands, new InfoCommand());
        register(commands, new HeapDumpCommand());
        register(commands, new DeadlockCommand());
        register(commands, new ThreadDumpCommand());
        register(commands, new EnvCommand());
        register(commands, new BuffersCommand());
        register(commands, new GcRunCommand());
        register(commands, new LoggerCommand());
        register(commands, new EventsCommand());
        register(commands, new CompilerQueueCommand());
        register(commands, new SearchClassCommand());
        register(commands, new JfrAnalyzeCommand());
        register(commands, new CompilerCommand());
        register(commands, new FinalizerCommand());
        register(commands, new StringTableCommand());
        register(commands, new PoolCommand());
        register(commands, new GcCauseCommand());
        register(commands, new MetaspaceCommand());
        register(commands, new DynLibsCommand());
        register(commands, new VmSetCommand());
        register(commands, new VmLogCommand());
        register(commands, new JmxCommand());
        register(commands, new ClassStatCommand());
        register(commands, new GcNewCommand());
        register(commands, new SymbolTableCommand());
        register(commands, new HeapAnalyzeCommand());
        register(commands, new PerfCounterCommand());
        register(commands, new MBeanCommand());
        register(commands, new TopCommand());
        register(commands, new WatchCommand());
        register(commands, new TuiCommand(commands));

        if (version) {
            printVersion();
            return;
        }

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

        try {
            command.execute(subArgs, config, registry, messages);
        } catch (CommandExitException e) {
            System.exit(e.exitCode());
        } catch (Exception e) {
            System.err.println("Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            if (Boolean.getBoolean("argus.debug") || System.getenv("ARGUS_DEBUG") != null) {
                e.printStackTrace();
            } else {
                System.err.println("Run with -Dargus.debug=true for full stack trace.");
            }
            System.exit(2);
        }
    }

    private static void register(Map<String, Command> map, Command cmd) {
        map.put(cmd.name(), cmd);
    }

    private static void printVersion() {
        System.out.println("argus " + VERSION);
    }

    private static void printUsage(Map<String, Command> commands, Messages messages) {
        System.out.println();
        System.out.println("  \033[1m\033[36margus\033[0m - JVM Diagnostic Toolkit  \033[2mv" + VERSION + "\033[0m");
        System.out.println("  " + commands.size() + " commands | Java 11+ CLI | Java 17+ Dashboard | Java 21+ Full");
        System.out.println();
        System.out.println("  \033[1mUsage:\033[0m argus <command> [<pid>] [options]");
        System.out.println();

        // Group commands by CommandGroup
        var grouped = new java.util.LinkedHashMap<io.argus.core.command.CommandGroup,
                java.util.List<Command>>();
        for (io.argus.core.command.CommandGroup g : io.argus.core.command.CommandGroup.values()) {
            grouped.put(g, new java.util.ArrayList<>());
        }
        for (Command cmd : commands.values()) {
            grouped.computeIfAbsent(cmd.group(), k -> new java.util.ArrayList<>()).add(cmd);
        }

        for (var entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            System.out.println("  \033[1m" + entry.getKey().displayName() + ":\033[0m");
            for (Command cmd : entry.getValue()) {
                String desc = cmd.description(messages);
                System.out.printf("    \033[36m%-18s\033[0m %s%n", cmd.name(), desc);
            }
            System.out.println();
        }

        System.out.println("  \033[1mGlobal Options:\033[0m");
        System.out.println("    --source=auto|agent|jdk   Data source (default: auto)");
        System.out.println("    --no-color                Disable colors");
        System.out.println("    --lang=en|ko|ja|zh        Output language");
        System.out.println("    --format=table|json       Output format (default: table)");
        System.out.println("    --host HOST               Agent host (default: localhost)");
        System.out.println("    --port PORT               Agent port (default: 9202)");
        System.out.println("    --help, -h                Show this help");
        System.out.println("    --version, -v             Show version");
        System.out.println();
        System.out.println("  \033[1mExamples:\033[0m");
        System.out.println("    \033[36margus ps\033[0m                          List JVM processes");
        System.out.println("    \033[36margus info 12345\033[0m                  JVM info with CPU%");
        System.out.println("    \033[36margus threaddump 12345\033[0m            Full thread dump");
        System.out.println("    \033[36margus sc 12345 \"*.UserService\"\033[0m    Search loaded classes");
        System.out.println("    \033[36margus jfranalyze recording.jfr\033[0m    Analyze JFR file");
        System.out.println("    \033[36margus gc 12345 --format=json\033[0m      JSON output");
        System.out.println();
    }
}
