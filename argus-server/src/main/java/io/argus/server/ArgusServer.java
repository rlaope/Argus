package io.argus.server;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.VirtualThreadEvent;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.io.InputStream;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket server for streaming virtual thread events to clients.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>ws://host:port/events - WebSocket stream of events</li>
 *   <li>GET /health - Health check endpoint</li>
 *   <li>GET /metrics - Metrics endpoint (JSON)</li>
 * </ul>
 */
public final class ArgusServer {

    private static final System.Logger LOG = System.getLogger(ArgusServer.class.getName());
    private static final int DEFAULT_PORT = 9202;
    private static final String WEBSOCKET_PATH = "/events";

    private final int port;
    private final RingBuffer<VirtualThreadEvent> eventBuffer;
    private final ChannelGroup clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalEvents = new AtomicLong(0);
    private final AtomicLong startEvents = new AtomicLong(0);
    private final AtomicLong endEvents = new AtomicLong(0);
    private final AtomicLong pinnedEvents = new AtomicLong(0);
    private final AtomicLong submitFailedEvents = new AtomicLong(0);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().name("argus-server-scheduler").daemon(true).unstarted(r)
    );

    // Track active threads for sending state to new clients
    private final Map<Long, VirtualThreadEvent> activeThreads = new ConcurrentHashMap<>();
    // Keep recent events for new clients (limited to 100)
    private final Deque<String> recentEvents = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT_EVENTS = 100;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public ArgusServer(int port, RingBuffer<VirtualThreadEvent> eventBuffer) {
        this.port = port;
        this.eventBuffer = eventBuffer;
    }

    /**
     * Starts the WebSocket server.
     */
    public void start() throws InterruptedException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server already running");
        }

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
                                .addLast(new ArgusServerHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        serverChannel = bootstrap.bind(port).sync().channel();
        LOG.log(System.Logger.Level.INFO, "Started on port " + port);
        LOG.log(System.Logger.Level.INFO, "Dashboard: http://localhost:" + port + "/");
        LOG.log(System.Logger.Level.INFO, "WebSocket endpoint: ws://localhost:" + port + WEBSOCKET_PATH);
        LOG.log(System.Logger.Level.INFO, "Metrics endpoint: http://localhost:" + port + "/metrics");
        LOG.log(System.Logger.Level.INFO, "Health endpoint: http://localhost:" + port + "/health");

        // Start event broadcasting
        startEventBroadcaster();
    }

    private void startEventBroadcaster() {
        scheduler.scheduleAtFixedRate(() -> {
            if (eventBuffer == null) {
                return;
            }

            eventBuffer.drain(event -> {
                // Update metrics
                totalEvents.incrementAndGet();
                switch (event.eventType()) {
                    case VIRTUAL_THREAD_START -> {
                        startEvents.incrementAndGet();
                        activeThreads.put(event.threadId(), event);
                    }
                    case VIRTUAL_THREAD_END -> {
                        endEvents.incrementAndGet();
                        activeThreads.remove(event.threadId());
                    }
                    case VIRTUAL_THREAD_PINNED -> pinnedEvents.incrementAndGet();
                    case VIRTUAL_THREAD_SUBMIT_FAILED -> submitFailedEvents.incrementAndGet();
                }

                // Serialize event
                String json = serializeEvent(event);

                // Store in recent events
                recentEvents.addLast(json);
                while (recentEvents.size() > MAX_RECENT_EVENTS) {
                    recentEvents.removeFirst();
                }

                // Broadcast to WebSocket clients
                if (!clients.isEmpty()) {
                    TextWebSocketFrame frame = new TextWebSocketFrame(json);
                    clients.writeAndFlush(frame.retain());
                    frame.release();
                }
            });
        }, 10, 10, TimeUnit.MILLISECONDS);
    }

    private String serializeEvent(VirtualThreadEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"type\":\"").append(getShortTypeName(event.eventType())).append("\",");
        sb.append("\"threadId\":").append(event.threadId()).append(",");
        if (event.threadName() != null) {
            sb.append("\"threadName\":\"").append(escapeJson(event.threadName())).append("\",");
        }
        if (event.carrierThread() > 0) {
            sb.append("\"carrierThread\":").append(event.carrierThread()).append(",");
        }
        sb.append("\"timestamp\":\"").append(event.timestamp()).append("\"");
        if (event.duration() > 0) {
            sb.append(",\"duration\":").append(event.duration());
        }
        if (event.stackTrace() != null) {
            sb.append(",\"stackTrace\":\"").append(escapeJson(event.stackTrace())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String getShortTypeName(io.argus.core.event.EventType eventType) {
        return switch (eventType) {
            case VIRTUAL_THREAD_START -> "START";
            case VIRTUAL_THREAD_END -> "END";
            case VIRTUAL_THREAD_PINNED -> "PINNED";
            case VIRTUAL_THREAD_SUBMIT_FAILED -> "SUBMIT_FAILED";
        };
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Sends recent events to a newly connected WebSocket client.
     */
    private void sendRecentEvents(Channel channel) {
        // Send all recent events to the client
        for (String eventJson : recentEvents) {
            channel.write(new TextWebSocketFrame(eventJson));
        }
        channel.flush();
    }

    /**
     * Stops the server.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        scheduler.shutdown();
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
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the number of connected clients.
     */
    public int getClientCount() {
        return clients.size();
    }

    private class ArgusServerHandler extends SimpleChannelInboundHandler<Object> {

        private WebSocketServerHandshaker handshaker;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest request) {
                handleHttpRequest(ctx, request);
            } else if (msg instanceof WebSocketFrame frame) {
                handleWebSocketFrame(ctx, frame);
            }
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri();

            // Handle health check
            if ("/health".equals(uri)) {
                String response = "{\"status\":\"healthy\",\"clients\":" + clients.size() + "}";
                sendHttpResponse(ctx, request, HttpResponseStatus.OK, response, "application/json");
                return;
            }

            // Handle metrics endpoint
            if ("/metrics".equals(uri)) {
                long total = totalEvents.get();
                long started = startEvents.get();
                long ended = endEvents.get();
                long pinned = pinnedEvents.get();
                long submitFailed = submitFailedEvents.get();
                int active = activeThreads.size();

                String response = String.format(
                        "{\"totalEvents\":%d,\"startEvents\":%d,\"endEvents\":%d,\"activeThreads\":%d,\"pinnedEvents\":%d,\"submitFailedEvents\":%d,\"connectedClients\":%d}",
                        total, started, ended, active, pinned, submitFailed, clients.size()
                );
                sendHttpResponse(ctx, request, HttpResponseStatus.OK, response, "application/json");
                return;
            }

            // Handle WebSocket upgrade
            if (WEBSOCKET_PATH.equals(uri)) {
                WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                        "ws://" + request.headers().get(HttpHeaderNames.HOST) + WEBSOCKET_PATH,
                        null, true);
                handshaker = wsFactory.newHandshaker(request);

                if (handshaker == null) {
                    WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                } else {
                    handshaker.handshake(ctx.channel(), request).addListener(future -> {
                        if (future.isSuccess()) {
                            clients.add(ctx.channel());
                            LOG.log(System.Logger.Level.DEBUG, "Client connected: {0} (total: {1})",
                                    ctx.channel().remoteAddress(), clients.size());
                            // Send recent events to the new client
                            sendRecentEvents(ctx.channel());
                        }
                    });
                }
                return;
            }

            // Handle static files
            if (serveStaticFile(ctx, request, uri)) {
                return;
            }

            // 404 for other paths
            sendHttpResponse(ctx, request, HttpResponseStatus.NOT_FOUND, "Not Found", "text/plain");
        }

        private boolean serveStaticFile(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
            // Map URI to classpath resource
            String resourcePath;
            String contentType;

            if ("/".equals(uri) || "/index.html".equals(uri)) {
                resourcePath = "public/index.html";
                contentType = "text/html; charset=UTF-8";
            } else if (uri.startsWith("/css/") && uri.endsWith(".css")) {
                resourcePath = "public" + uri;
                contentType = "text/css; charset=UTF-8";
            } else if (uri.startsWith("/js/") && uri.endsWith(".js")) {
                resourcePath = "public" + uri;
                contentType = "application/javascript; charset=UTF-8";
            } else {
                return false;
            }

            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    return false;
                }
                byte[] bytes = is.readAllBytes();
                ByteBuf buf = Unpooled.wrappedBuffer(bytes);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
                response.headers()
                        .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                        .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                        .set(HttpHeaderNames.CACHE_CONTROL, "no-cache");

                ChannelFuture future = ctx.writeAndFlush(response);
                if (!HttpUtil.isKeepAlive(request)) {
                    future.addListener(ChannelFutureListener.CLOSE);
                }
                return true;
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Error serving static file {0}: {1}", uri, e.getMessage());
                return false;
            }
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof CloseWebSocketFrame) {
                handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
                return;
            }

            if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
                return;
            }

            if (frame instanceof TextWebSocketFrame textFrame) {
                // Echo back for now (can be used for commands later)
                String text = textFrame.text();
                if ("ping".equalsIgnoreCase(text)) {
                    ctx.writeAndFlush(new TextWebSocketFrame("pong"));
                }
            }
        }

        private void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest request,
                                      HttpResponseStatus status, String content, String contentType) {
            ByteBuf buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, buf);
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());

            ChannelFuture future = ctx.writeAndFlush(response);
            if (!HttpUtil.isKeepAlive(request)) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            clients.remove(ctx.channel());
            LOG.log(System.Logger.Level.DEBUG, "Client disconnected: {0} (total: {1})",
                    ctx.channel().remoteAddress(), clients.size());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.log(System.Logger.Level.WARNING, "Connection error: {0}", cause.getMessage());
            ctx.close();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("argus.server.port", DEFAULT_PORT);
        RingBuffer<VirtualThreadEvent> buffer = new RingBuffer<>();

        ArgusServer server = new ArgusServer(port, buffer);
        server.start();

        // Keep running
        Thread.currentThread().join();
    }
}
