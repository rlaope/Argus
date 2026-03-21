package io.argus.cli.command;

import io.argus.cli.ArgusClient;
import io.argus.cli.MetricsSnapshot;
import io.argus.cli.TerminalRenderer;
import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;

import java.io.IOException;

/**
 * Real-time JVM monitoring command. Delegates to the existing ArgusTop/ArgusClient/TerminalRenderer flow.
 */
public final class TopCommand implements Command {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 9202;
    private static final int DEFAULT_INTERVAL = 1;

    @Override
    public String name() {
        return "top";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.top.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        String host = DEFAULT_HOST;
        int port = config.defaultPort();
        int interval = DEFAULT_INTERVAL;
        boolean useColor = config.color();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--host=")) {
                host = arg.substring(7);
            } else if (arg.equals("--host") && i + 1 < args.length) {
                host = args[++i];
            } else if (arg.startsWith("--port=")) {
                try { port = Integer.parseInt(arg.substring(7)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--port") && i + 1 < args.length) {
                try { port = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--interval=")) {
                try { interval = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--interval") && i + 1 < args.length) {
                try { interval = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--no-color")) {
                useColor = false;
            }
        }

        ArgusClient client = new ArgusClient(host, port);
        TerminalRenderer renderer = new TerminalRenderer(useColor, host, port, interval);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.print("\033[?25h");
            System.out.println("\nArgus CLI stopped.");
        }));

        System.out.print("\033[?25l");
        System.out.println("Connecting to Argus server at " + host + ":" + port + "...");

        final long intervalMs = interval * 1000L;
        while (!Thread.currentThread().isInterrupted()) {
            MetricsSnapshot snapshot = client.fetchAll();
            renderer.render(snapshot);

            try {
                if (System.in.available() > 0) {
                    int ch = System.in.read();
                    if (ch == 'q' || ch == 'Q') {
                        break;
                    }
                }
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                // Ignore stdin errors
            }
        }

        System.out.print("\033[?25h");
    }
}
