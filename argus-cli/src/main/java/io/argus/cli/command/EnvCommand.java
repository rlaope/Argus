package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.json.JsonOutput;
import io.argus.cli.model.EnvResult;
import io.argus.cli.provider.EnvProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Shows JVM launch environment: command line, java home, classpath, VM args.
 */
public final class EnvCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "env"; }

    @Override public CommandGroup group() { return CommandGroup.PROCESS; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.env.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        long pid = CommandUtils.parsePidOrExit(args, messages);

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--source=")) sourceOverride = args[i].substring(9);
            else if (args[i].equals("--format=json")) json = true;
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        EnvProvider provider = Providers.require(registry.find(EnvProvider.class, pid, sourceOverride), pid, messages);

        EnvResult result = provider.getEnv(pid);

        if (json) { JsonOutput.println(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "env", messages.get("desc.env")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.env"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Command line
        String cmdLabel = AnsiStyle.style(useColor, AnsiStyle.BOLD) + messages.get("env.command") + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(cmdLabel, WIDTH));
        if (!result.commandLine().isEmpty()) {
            System.out.println(RichRenderer.boxLine("  " + RichRenderer.truncate(result.commandLine(), WIDTH - 8), WIDTH));
        }
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Java Home
        printField(useColor, messages.get("env.javahome"), result.javaHome());

        // Working Dir
        printField(useColor, messages.get("env.workdir"), result.workingDir());

        // Classpath
        if (!result.classPath().isEmpty()) {
            String cpLabel = AnsiStyle.style(useColor, AnsiStyle.BOLD) + messages.get("env.classpath") + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(cpLabel, WIDTH));
            for (String entry : result.classPath().split(":")) {
                if (!entry.isEmpty()) {
                    System.out.println(RichRenderer.boxLine("  " + RichRenderer.truncate(entry, WIDTH - 8), WIDTH));
                }
            }
            System.out.println(RichRenderer.emptyLine(WIDTH));
        }

        // VM Args
        if (!result.vmArgs().isEmpty()) {
            String argsLabel = AnsiStyle.style(useColor, AnsiStyle.BOLD) + messages.get("env.vmargs") + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(argsLabel, WIDTH));
            for (String arg : result.vmArgs()) {
                String color = arg.startsWith("-XX:") ? AnsiStyle.style(useColor, AnsiStyle.CYAN) : "";
                String reset = color.isEmpty() ? "" : AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine("  " + color + arg + reset, WIDTH));
            }
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printField(boolean useColor, String label, String value) {
        String line = AnsiStyle.style(useColor, AnsiStyle.BOLD) + label + AnsiStyle.style(useColor, AnsiStyle.RESET)
                + "  " + value;
        System.out.println(RichRenderer.boxLine(line, WIDTH));
    }

}
