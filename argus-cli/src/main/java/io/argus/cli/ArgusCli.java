package io.argus.cli;

import io.argus.cli.command.Command;
import io.argus.cli.command.ClassStatCommand;
import io.argus.cli.command.CompilerCommand;
import io.argus.cli.command.DeadlockCommand;
import io.argus.cli.command.DiffCommand;
import io.argus.cli.command.DynLibsCommand;
import io.argus.cli.command.EnvCommand;
import io.argus.cli.command.FinalizerCommand;
import io.argus.cli.command.GcCauseCommand;
import io.argus.cli.command.GcCommand;
import io.argus.cli.command.GcNewCommand;
import io.argus.cli.command.GcUtilCommand;
import io.argus.cli.command.HeapCommand;
import io.argus.cli.command.HeapDumpCommand;
import io.argus.cli.command.HistoCommand;
import io.argus.cli.command.InfoCommand;
import io.argus.cli.command.InitCommand;
import io.argus.cli.command.ClassLoaderCommand;
import io.argus.cli.command.JfrCommand;
import io.argus.cli.command.JmxCommand;
import io.argus.cli.command.MetaspaceCommand;
import io.argus.cli.command.NmtCommand;
import io.argus.cli.command.PoolCommand;
import io.argus.cli.command.ProfileCommand;
import io.argus.cli.command.PsCommand;
import io.argus.cli.command.ReportCommand;
import io.argus.cli.command.StringTableCommand;
import io.argus.cli.command.SymbolTableCommand;
import io.argus.cli.command.SysPropsCommand;
import io.argus.cli.command.ThreadDumpCommand;
import io.argus.cli.command.ThreadsCommand;
import io.argus.cli.command.TopCommand;
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

    private static final String VERSION = "0.6.0";
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
        register(commands, new InfoCommand());
        register(commands, new HeapDumpCommand());
        register(commands, new DeadlockCommand());
        register(commands, new ThreadDumpCommand());
        register(commands, new EnvCommand());
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
        register(commands, new TopCommand());

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

        command.execute(subArgs, config, registry, messages);
    }

    private static void register(Map<String, Command> map, Command cmd) {
        map.put(cmd.name(), cmd);
    }

    private static void printVersion() {
        System.out.println("argus " + VERSION);
    }

    private static void printUsage(Map<String, Command> commands, Messages messages) {
        System.out.println();
        System.out.println("  \033[1m\033[36margus\033[0m - JVM Diagnostic Tool  \033[2mv" + VERSION + "\033[0m");
        System.out.println();
        System.out.println("  \033[1mUsage:\033[0m argus <command> [<pid>] [options]");
        System.out.println();
        System.out.println("  \033[1mCommands:\033[0m");
        System.out.println("    init             Initialize CLI configuration");
        System.out.println("    ps               List running JVM processes");
        System.out.println("    histo  \033[2m<pid>\033[0m     Heap object histogram");
        System.out.println("    threads \033[2m<pid>\033[0m    Thread dump summary");
        System.out.println("    gc     \033[2m<pid>\033[0m     GC statistics");
        System.out.println("    gcutil \033[2m<pid>\033[0m     GC generation utilization (like jstat)");
        System.out.println("    heap     \033[2m<pid>\033[0m   Heap memory usage");
        System.out.println("    sysprops \033[2m<pid>\033[0m   JVM system properties");
        System.out.println("    vmflag   \033[2m<pid>\033[0m   Show or set VM flags");
        System.out.println("    nmt      \033[2m<pid>\033[0m   Native memory tracking");
        System.out.println("    classloader \033[2m<pid>\033[0m Class loader hierarchy");
        System.out.println("    profile  \033[2m<pid>\033[0m   CPU/allocation/lock profiling");
    System.out.println("    jfr      \033[2m<pid>\033[0m   Flight Recorder control");
        System.out.println("    diff \033[2m<pid>\033[0m     Heap snapshot diff (leak detection)");
        System.out.println("    report \033[2m<pid>\033[0m   Comprehensive diagnostic report");
        System.out.println("    info     \033[2m<pid>\033[0m   JVM information");
        System.out.println("    heapdump \033[2m<pid>\033[0m   Generate heap dump (with STW warning)");
        System.out.println("    deadlock \033[2m<pid>\033[0m   Detect Java-level deadlocks");
        System.out.println("    env      \033[2m<pid>\033[0m   JVM launch environment");
        System.out.println("    compiler \033[2m<pid>\033[0m   JIT compiler and code cache stats");
        System.out.println("    finalizer \033[2m<pid>\033[0m  Finalizer queue status");
        System.out.println("    stringtable \033[2m<pid>\033[0m String table statistics");
        System.out.println("    pool     \033[2m<pid>\033[0m   Thread pool analysis");
        System.out.println("    gccause  \033[2m<pid>\033[0m   GC cause with utilization stats");
        System.out.println("    metaspace \033[2m<pid>\033[0m  Detailed metaspace breakdown");
        System.out.println("    dynlibs  \033[2m<pid>\033[0m   Loaded native libraries");
        System.out.println("    vmset  \033[2m<pid> Flag=val\033[0m  Set VM flag at runtime");
        System.out.println("    vmlog    \033[2m<pid>\033[0m   JVM unified logging control");
        System.out.println("    jmx    \033[2m<pid> [cmd]\033[0m  JMX agent control");
        System.out.println("    classstat \033[2m<pid>\033[0m  Class loading statistics");
        System.out.println("    gcnew    \033[2m<pid>\033[0m   Young generation GC detail");
        System.out.println("    symboltable \033[2m<pid>\033[0m Symbol table statistics");
        System.out.println("    top              Real-time monitoring (agent required)");
        System.out.println();
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
        System.out.println("    \033[36margus ps\033[0m");
        System.out.println("    \033[36margus histo 12345\033[0m");
        System.out.println("    \033[36margus histo 12345 --top 50\033[0m");
        System.out.println("    \033[36margus threads 12345 --source=agent\033[0m");
        System.out.println("    \033[36margus gc 12345 --format=json\033[0m");
        System.out.println("    \033[36margus top --port 9202\033[0m");
        System.out.println();
    }
}
