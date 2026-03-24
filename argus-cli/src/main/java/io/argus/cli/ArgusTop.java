package io.argus.cli;

import java.io.IOException;

/**
 * Terminal-based JVM monitoring tool for Argus.
 *
 * <p>Connects to a running Argus server via HTTP and displays
 * real-time metrics in an htop-like terminal interface.
 *
 * <p>Usage: {@code java -jar argus-cli.jar [--host HOST] [--port PORT] [--interval SECS] [--no-color]}
 */
public final class ArgusTop {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 9202;
    private static final int DEFAULT_INTERVAL = 1;

    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        int interval = DEFAULT_INTERVAL;
        boolean color = true;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                case "-h":
                    if (i + 1 < args.length) host = args[++i];
                    break;
                case "--port":
                case "-p":
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                    break;
                case "--interval":
                case "-i":
                    if (i + 1 < args.length) interval = Integer.parseInt(args[++i]);
                    break;
                case "--no-color":
                    color = false;
                    break;
                case "--help":
                    printUsage();
                    return;
            }
        }

        ArgusClient client = new ArgusClient(host, port);
        TerminalRenderer renderer = new TerminalRenderer(color, host, port, interval);

        // Shutdown hook for clean exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.print("\033[?25h"); // Show cursor
            System.out.println("\nArgus CLI stopped.");
        }));

        // Hide cursor
        System.out.print("\033[?25l");

        System.out.println("Connecting to Argus server at " + host + ":" + port + "...");

        // Main loop
        final long intervalMs = interval * 1000L;
        while (!Thread.currentThread().isInterrupted()) {
            MetricsSnapshot snapshot = client.fetchAll();
            renderer.render(snapshot);

            try {
                // Check for 'q' key (non-blocking stdin check)
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

        System.out.print("\033[?25h"); // Show cursor
    }

    private static void printUsage() {
        System.out.println(
            "Argus Top - Terminal JVM Monitor\n"
            + "\n"
            + "Usage: argus-top [OPTIONS]\n"
            + "\n"
            + "Options:\n"
            + "  --host, -h HOST      Server host (default: localhost)\n"
            + "  --port, -p PORT      Server port (default: 9202)\n"
            + "  --interval, -i SECS  Refresh interval in seconds (default: 1)\n"
            + "  --no-color           Disable ANSI colors\n"
            + "  --help               Show this help\n"
            + "\n"
            + "Examples:\n"
            + "  argus-top\n"
            + "  argus-top --port 9202 --interval 2\n"
            + "  argus-top --host 192.168.1.100 --port 9202 --no-color\n"
        );
    }
}
