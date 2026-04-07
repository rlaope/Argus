package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * Interactive wizard to initialize the Argus CLI configuration.
 */
public final class InitCommand implements Command {

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".argus", "config.properties");

    @Override
    public String name() {
        return "init";
    }

    @Override public CommandGroup group() { return CommandGroup.MONITORING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.init.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        boolean force = hasFlag(args, "--force");

        if (CliConfig.isInitialized() && !force) {
            System.out.println(messages.get("init.already", CONFIG_FILE));
            return;
        }

        System.out.println(messages.get("init.welcome"));
        System.out.println();

        String lang = selectLanguage();

        CliConfig newConfig = new CliConfig(
                lang,
                config.defaultSource(),
                config.color(),
                config.format(),
                config.defaultPort()
        );

        try {
            CliConfig.save(newConfig);
            System.out.println();
            System.out.println(messages.get("init.saved", CONFIG_FILE));
        } catch (IOException e) {
            System.err.println("Error saving config: " + e.getMessage());
        }
    }

    private static String selectLanguage() {
        System.out.println("? Select language:");
        System.out.println("  [1] English");
        System.out.println("  [2] \ud55c\uad6d\uc5b4");
        System.out.println("  [3] \u65e5\u672c\u8a9e");
        System.out.println("  [4] \u4e2d\u6587");
        System.out.print("> ");

        Scanner scanner = new Scanner(System.in);
        String input = scanner.hasNextLine() ? scanner.nextLine().trim() : "1";

        if (input.equals("2")) return "ko";
        else if (input.equals("3")) return "ja";
        else if (input.equals("4")) return "zh";
        else return "en";
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) return true;
        }
        return false;
    }
}
