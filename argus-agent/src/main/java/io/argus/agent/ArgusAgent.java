package io.argus.agent;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.VirtualThreadEvent;
import io.argus.server.ArgusServer;

import java.lang.instrument.Instrumentation;

/**
 * Argus Java Agent entry point.
 *
 * <p>This agent attaches to a JVM and starts the JFR streaming engine
 * to capture virtual thread events in real-time.
 *
 * <p>Usage:
 * <pre>
 * java -javaagent:argus-agent.jar --enable-preview -jar your-app.jar
 * </pre>
 */
public final class ArgusAgent {

    private static final String BANNER = """

              _____
             /  _  \\_______  ____  __ __  ______
            /  /_\\  \\_  __ \\/ ___\\|  |  \\/  ___/
           /    |    \\  | \\/ /_/  >  |  /\\___ \\
           \\____|__  /__|  \\___  /|____//____  >
                   \\/     /_____/            \\/

           Virtual Thread Profiler v%s
           """;

    private static volatile JfrStreamingEngine engine;
    private static volatile RingBuffer<VirtualThreadEvent> eventBuffer;
    private static volatile ArgusServer server;

    private ArgusAgent() {
    }

    /**
     * Premain entry point for static agent attachment.
     *
     * @param agentArgs arguments passed to the agent
     * @param inst      the instrumentation instance
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        initialize(agentArgs);
    }

    /**
     * Agentmain entry point for dynamic agent attachment.
     *
     * @param agentArgs arguments passed to the agent
     * @param inst      the instrumentation instance
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        initialize(agentArgs);
    }

    private static void initialize(String agentArgs) {
        String version = ArgusAgent.class.getPackage().getImplementationVersion();
        System.out.printf(BANNER, version != null ? version : "dev");

        int bufferSize = getBufferSize();
        eventBuffer = new RingBuffer<>(bufferSize);

        System.out.println("[Argus] Initializing JFR streaming engine...");
        engine = new JfrStreamingEngine(eventBuffer);
        engine.start();

        // Optionally start WebSocket server
        if (isServerEnabled()) {
            startServer();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Argus] Shutting down...");
            if (server != null) {
                server.stop();
            }
            if (engine != null) {
                engine.stop();
            }
        }, "argus-shutdown"));

        System.out.println("[Argus] Agent initialized successfully");
        System.out.printf("[Argus] Ring buffer size: %d%n", bufferSize);
    }

    private static boolean isServerEnabled() {
        return Boolean.parseBoolean(System.getProperty("argus.server.enabled", "false"));
    }

    private static void startServer() {
        int port = Integer.parseInt(System.getProperty("argus.server.port", "8080"));
        server = new ArgusServer(port, eventBuffer);
        try {
            Thread serverThread = Thread.ofPlatform()
                    .name("argus-server")
                    .daemon(true)
                    .start(() -> {
                        try {
                            server.start();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            System.out.printf("[Argus] WebSocket server starting on port %d%n", port);
            System.out.printf("[Argus] Connect to ws://localhost:%d/events%n", port);
        } catch (Exception e) {
            System.err.println("[Argus] Failed to start server: " + e.getMessage());
        }
    }

    private static int getBufferSize() {
        String sizeStr = System.getProperty("argus.buffer.size", "65536");
        try {
            return Integer.parseInt(sizeStr);
        } catch (NumberFormatException e) {
            System.err.println("[Argus] Invalid buffer size, using default: 65536");
            return 65536;
        }
    }

    /**
     * Returns the event buffer for consumers.
     */
    public static RingBuffer<VirtualThreadEvent> getEventBuffer() {
        return eventBuffer;
    }

    /**
     * Returns the JFR streaming engine.
     */
    public static JfrStreamingEngine getEngine() {
        return engine;
    }

    /**
     * Returns the WebSocket server (if enabled).
     */
    public static ArgusServer getServer() {
        return server;
    }
}
