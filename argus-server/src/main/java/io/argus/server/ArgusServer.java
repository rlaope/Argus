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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket server for streaming virtual thread events to clients.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>ws://host:port/events - WebSocket stream of events</li>
 *   <li>GET /health - Health check endpoint</li>
 * </ul>
 */
public final class ArgusServer {

    private static final int DEFAULT_PORT = 8080;
    private static final String WEBSOCKET_PATH = "/events";

    private final int port;
    private final RingBuffer<VirtualThreadEvent> eventBuffer;
    private final ChannelGroup clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().name("argus-server-scheduler").daemon(true).unstarted(r)
    );

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
        System.out.printf("[Argus Server] Started on port %d%n", port);
        System.out.printf("[Argus Server] Dashboard: http://localhost:%d/%n", port);
        System.out.printf("[Argus Server] WebSocket endpoint: ws://localhost:%d%s%n", port, WEBSOCKET_PATH);
        System.out.printf("[Argus Server] Health endpoint: http://localhost:%d/health%n", port);

        // Start event broadcasting
        startEventBroadcaster();
    }

    private void startEventBroadcaster() {
        scheduler.scheduleAtFixedRate(() -> {
            if (clients.isEmpty() || eventBuffer == null) {
                return;
            }

            eventBuffer.drain(event -> {
                String json = serializeEvent(event);
                TextWebSocketFrame frame = new TextWebSocketFrame(json);
                clients.writeAndFlush(frame.retain());
                frame.release();
            });
        }, 10, 10, TimeUnit.MILLISECONDS);
    }

    private String serializeEvent(VirtualThreadEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"type\":\"").append(event.eventType().name()).append("\",");
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

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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

        System.out.println("[Argus Server] Stopped");
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

            // Handle WebSocket upgrade
            if (WEBSOCKET_PATH.equals(uri)) {
                WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                        "ws://" + request.headers().get(HttpHeaderNames.HOST) + WEBSOCKET_PATH,
                        null, true);
                handshaker = wsFactory.newHandshaker(request);

                if (handshaker == null) {
                    WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                } else {
                    handshaker.handshake(ctx.channel(), request);
                    clients.add(ctx.channel());
                    System.out.printf("[Argus Server] Client connected: %s (total: %d)%n",
                            ctx.channel().remoteAddress(), clients.size());
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
                System.err.printf("[Argus Server] Error serving static file %s: %s%n", uri, e.getMessage());
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
            System.out.printf("[Argus Server] Client disconnected: %s (total: %d)%n",
                    ctx.channel().remoteAddress(), clients.size());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.printf("[Argus Server] Error: %s%n", cause.getMessage());
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
