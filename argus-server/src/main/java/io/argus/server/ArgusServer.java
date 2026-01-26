package io.argus.server;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.CPUEvent;
import io.argus.core.event.GCEvent;
import io.argus.core.event.VirtualThreadEvent;
import io.argus.server.analysis.CarrierThreadAnalyzer;
import io.argus.server.analysis.CPUAnalyzer;
import io.argus.server.analysis.GCAnalyzer;
import io.argus.server.analysis.PinningAnalyzer;
import io.argus.server.handler.ArgusChannelHandler;
import io.argus.server.metrics.ServerMetrics;
import io.argus.server.serialization.EventJsonSerializer;
import io.argus.server.state.ActiveThreadsRegistry;
import io.argus.server.state.RecentEventsBuffer;
import io.argus.server.state.ThreadEventsBuffer;
import io.argus.server.state.ThreadStateManager;
import io.argus.server.websocket.EventBroadcaster;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket server for streaming virtual thread events to clients.
 *
 * <p>This server provides:
 * <ul>
 *   <li>WebSocket endpoint at /events for real-time event streaming</li>
 *   <li>HTTP endpoints for health checks and metrics</li>
 *   <li>Static file serving for the dashboard UI</li>
 * </ul>
 *
 * <p>The server uses a modular architecture with separate components for:
 * <ul>
 *   <li>{@link ServerMetrics} - Metrics aggregation</li>
 *   <li>{@link ActiveThreadsRegistry} - Active thread tracking</li>
 *   <li>{@link RecentEventsBuffer} - Recent events for new clients</li>
 *   <li>{@link EventBroadcaster} - Event broadcasting to WebSocket clients</li>
 *   <li>{@link EventJsonSerializer} - Event serialization</li>
 * </ul>
 */
public final class ArgusServer {

    private static final System.Logger LOG = System.getLogger(ArgusServer.class.getName());
    private static final int DEFAULT_PORT = 9202;
    private static final String WEBSOCKET_PATH = "/events";

    private final int port;
    private final RingBuffer<VirtualThreadEvent> eventBuffer;
    private final RingBuffer<GCEvent> gcEventBuffer;
    private final RingBuffer<CPUEvent> cpuEventBuffer;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Components
    private final ChannelGroup clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final ServerMetrics metrics = new ServerMetrics();
    private final ActiveThreadsRegistry activeThreads = new ActiveThreadsRegistry();
    private final RecentEventsBuffer recentEvents = new RecentEventsBuffer();
    private final ThreadEventsBuffer threadEvents = new ThreadEventsBuffer();
    private final PinningAnalyzer pinningAnalyzer = new PinningAnalyzer();
    private final CarrierThreadAnalyzer carrierAnalyzer = new CarrierThreadAnalyzer();
    private final GCAnalyzer gcAnalyzer = new GCAnalyzer();
    private final CPUAnalyzer cpuAnalyzer = new CPUAnalyzer();
    private final ThreadStateManager threadStateManager = new ThreadStateManager();
    private final EventJsonSerializer serializer = new EventJsonSerializer();
    private EventBroadcaster broadcaster;

    // Netty components
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /**
     * Creates a new Argus server with only virtual thread event buffer.
     *
     * @param port        the port to listen on
     * @param eventBuffer the ring buffer to read events from
     */
    public ArgusServer(int port, RingBuffer<VirtualThreadEvent> eventBuffer) {
        this(port, eventBuffer, null, null);
    }

    /**
     * Creates a new Argus server with GC and CPU event buffers.
     *
     * @param port           the port to listen on
     * @param eventBuffer    the ring buffer for virtual thread events
     * @param gcEventBuffer  the ring buffer for GC events (can be null)
     * @param cpuEventBuffer the ring buffer for CPU events (can be null)
     */
    public ArgusServer(int port, RingBuffer<VirtualThreadEvent> eventBuffer,
                       RingBuffer<GCEvent> gcEventBuffer, RingBuffer<CPUEvent> cpuEventBuffer) {
        this.port = port;
        this.eventBuffer = eventBuffer;
        this.gcEventBuffer = gcEventBuffer;
        this.cpuEventBuffer = cpuEventBuffer;
    }

    /**
     * Starts the server.
     *
     * @throws InterruptedException if interrupted while starting
     */
    public void start() throws InterruptedException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server already running");
        }

        // Initialize broadcaster
        broadcaster = new EventBroadcaster(
                eventBuffer, gcEventBuffer, cpuEventBuffer,
                clients, metrics, activeThreads, recentEvents, threadEvents,
                pinningAnalyzer, carrierAnalyzer, gcAnalyzer, cpuAnalyzer,
                threadStateManager, serializer);

        // Initialize Netty
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(65536))
                                .addLast(new WebSocketServerCompressionHandler())
                                .addLast(new ArgusChannelHandler(
                                        clients, metrics, activeThreads, threadEvents,
                                        gcAnalyzer, cpuAnalyzer, broadcaster));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        serverChannel = bootstrap.bind(port).sync().channel();

        // Start event broadcasting
        broadcaster.start();

        // Log startup info
        LOG.log(System.Logger.Level.INFO, "Started on port " + port);
        LOG.log(System.Logger.Level.INFO, "Dashboard: http://localhost:" + port + "/");
        LOG.log(System.Logger.Level.INFO, "WebSocket endpoint: ws://localhost:" + port + WEBSOCKET_PATH);
        LOG.log(System.Logger.Level.INFO, "Metrics endpoint: http://localhost:" + port + "/metrics");
        LOG.log(System.Logger.Level.INFO, "Health endpoint: http://localhost:" + port + "/health");
    }

    /**
     * Stops the server.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (broadcaster != null) {
            broadcaster.stop();
        }

        clients.close();

        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        LOG.log(System.Logger.Level.INFO, "Stopped");
    }

    /**
     * Returns true if the server is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the number of connected WebSocket clients.
     *
     * @return client count
     */
    public int getClientCount() {
        return clients.size();
    }

    /**
     * Returns the server metrics.
     *
     * @return metrics instance
     */
    public ServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * Returns the active threads registry.
     *
     * @return active threads registry
     */
    public ActiveThreadsRegistry getActiveThreads() {
        return activeThreads;
    }

    /**
     * Returns the pinning analyzer.
     *
     * @return pinning analyzer
     */
    public PinningAnalyzer getPinningAnalyzer() {
        return pinningAnalyzer;
    }

    /**
     * Returns the GC analyzer.
     *
     * @return GC analyzer
     */
    public GCAnalyzer getGcAnalyzer() {
        return gcAnalyzer;
    }

    /**
     * Returns the CPU analyzer.
     *
     * @return CPU analyzer
     */
    public CPUAnalyzer getCpuAnalyzer() {
        return cpuAnalyzer;
    }

    /**
     * Main entry point for standalone server.
     *
     * @param args command line arguments
     * @throws Exception if server fails to start
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("argus.server.port", DEFAULT_PORT);
        RingBuffer<VirtualThreadEvent> buffer = new RingBuffer<>();

        ArgusServer server = new ArgusServer(port, buffer);
        server.start();

        // Keep running
        Thread.currentThread().join();
    }
}
