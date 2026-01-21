package io.argus.agent;

import io.argus.agent.config.AgentConfig;
import io.argus.agent.jfr.JfrStreamingEngine;
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
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code -Dargus.buffer.size=65536} - Ring buffer size</li>
 *   <li>{@code -Dargus.server.enabled=true} - Enable dashboard server</li>
 *   <li>{@code -Dargus.server.port=8080} - Server port</li>
 * </ul>
 *
 * @see AgentConfig
 * @see JfrStreamingEngine
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
    private static volatile AgentConfig config;

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
        // Print banner
        String version = ArgusAgent.class.getPackage().getImplementationVersion();
        System.out.printf(BANNER, version != null ? version : "dev");

        // Load configuration
        config = AgentConfig.fromSystemProperties();

        // Initialize event buffer
        eventBuffer = new RingBuffer<>(config.getBufferSize());

        // Start JFR streaming engine
        System.out.println("[Argus] Initializing JFR streaming engine...");
        engine = new JfrStreamingEngine(eventBuffer);
        engine.start();

        // Start server if enabled
        if (config.isServerEnabled()) {
            startServer();
        }

        // Register shutdown hook
        registerShutdownHook();

        System.out.println("[Argus] Agent initialized successfully");
        System.out.printf("[Argus] Ring buffer size: %d%n", config.getBufferSize());
    }

    private static void startServer() {
        server = new ArgusServer(config.getServerPort(), eventBuffer);
        Thread.ofPlatform()
                .name("argus-server")
                .daemon(true)
                .start(() -> {
                    try {
                        server.start();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        System.err.println("[Argus] Server error: " + e.getMessage());
                    }
                });
        System.out.printf("[Argus] WebSocket server starting on port %d%n", config.getServerPort());
        System.out.printf("[Argus] Connect to ws://localhost:%d/events%n", config.getServerPort());
    }

    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Argus] Shutting down...");
            if (server != null) {
                server.stop();
            }
            if (engine != null) {
                engine.stop();
            }
        }, "argus-shutdown"));
    }

    /**
     * Returns the agent configuration.
     *
     * @return agent config
     */
    public static AgentConfig getConfig() {
        return config;
    }

    /**
     * Returns the event buffer for consumers.
     *
     * @return event buffer
     */
    public static RingBuffer<VirtualThreadEvent> getEventBuffer() {
        return eventBuffer;
    }

    /**
     * Returns the JFR streaming engine.
     *
     * @return JFR engine
     */
    public static JfrStreamingEngine getEngine() {
        return engine;
    }

    /**
     * Returns the WebSocket server (if enabled).
     *
     * @return server instance or null
     */
    public static ArgusServer getServer() {
        return server;
    }
}
