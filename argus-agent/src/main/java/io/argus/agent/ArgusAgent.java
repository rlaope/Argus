package io.argus.agent;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.VirtualThreadEvent;

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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Argus] Shutting down...");
            if (engine != null) {
                engine.stop();
            }
        }, "argus-shutdown"));

        System.out.println("[Argus] Agent initialized successfully");
        System.out.printf("[Argus] Ring buffer size: %d%n", bufferSize);
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
}
