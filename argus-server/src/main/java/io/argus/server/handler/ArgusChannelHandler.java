package io.argus.server.handler;

import io.argus.server.http.HttpResponseHelper;
import io.argus.server.http.StaticFileHandler;
import io.argus.server.metrics.ServerMetrics;
import io.argus.server.state.ActiveThreadsRegistry;
import io.argus.server.state.ThreadEventsBuffer;
import io.argus.server.websocket.EventBroadcaster;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.websocketx.*;

/**
 * Netty channel handler for the Argus server.
 *
 * <p>This handler processes both HTTP requests and WebSocket frames,
 * delegating to specialized handlers for each concern.
 */
public final class ArgusChannelHandler extends SimpleChannelInboundHandler<Object> {

    private static final System.Logger LOG = System.getLogger(ArgusChannelHandler.class.getName());
    private static final String WEBSOCKET_PATH = "/events";

    private final ChannelGroup clients;
    private final ServerMetrics metrics;
    private final ActiveThreadsRegistry activeThreads;
    private final ThreadEventsBuffer threadEvents;
    private final EventBroadcaster broadcaster;
    private final StaticFileHandler staticFileHandler;

    private WebSocketServerHandshaker handshaker;

    /**
     * Creates a new channel handler.
     *
     * @param clients       the channel group for WebSocket clients
     * @param metrics       the server metrics tracker
     * @param activeThreads the active threads registry
     * @param threadEvents  the per-thread events buffer
     * @param broadcaster   the event broadcaster
     */
    public ArgusChannelHandler(
            ChannelGroup clients,
            ServerMetrics metrics,
            ActiveThreadsRegistry activeThreads,
            ThreadEventsBuffer threadEvents,
            EventBroadcaster broadcaster) {
        this.clients = clients;
        this.metrics = metrics;
        this.activeThreads = activeThreads;
        this.threadEvents = threadEvents;
        this.broadcaster = broadcaster;
        this.staticFileHandler = new StaticFileHandler();
    }

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

        // Health endpoint
        if ("/health".equals(uri)) {
            String json = String.format("{\"status\":\"healthy\",\"clients\":%d}", clients.size());
            HttpResponseHelper.sendJson(ctx, request, json);
            return;
        }

        // Metrics endpoint
        if ("/metrics".equals(uri)) {
            String json = metrics.toJson(activeThreads.size(), clients.size());
            HttpResponseHelper.sendJson(ctx, request, json);
            return;
        }

        // Active threads endpoint
        if ("/active-threads".equals(uri)) {
            String json = serializeActiveThreads();
            HttpResponseHelper.sendJson(ctx, request, json);
            return;
        }

        // Thread events endpoint: /threads/{threadId}/events
        if (uri.startsWith("/threads/") && uri.endsWith("/events")) {
            handleThreadEventsRequest(ctx, request, uri);
            return;
        }

        // WebSocket upgrade
        if (WEBSOCKET_PATH.equals(uri)) {
            handleWebSocketUpgrade(ctx, request);
            return;
        }

        // Static files
        if (staticFileHandler.serve(ctx, request, uri)) {
            return;
        }

        // 404 for unknown paths
        HttpResponseHelper.sendNotFound(ctx, request);
    }

    private void handleWebSocketUpgrade(ChannelHandlerContext ctx, FullHttpRequest request) {
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                "ws://" + request.headers().get(HttpHeaderNames.HOST) + WEBSOCKET_PATH,
                null, true);
        handshaker = wsFactory.newHandshaker(request);

        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(ctx.channel(), request).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    clients.add(ctx.channel());
                    LOG.log(System.Logger.Level.DEBUG, "Client connected: {0} (total: {1})",
                            ctx.channel().remoteAddress(), clients.size());
                    // Send recent events to the new client
                    broadcaster.sendRecentEvents(ctx.channel());
                }
            });
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
            String text = textFrame.text();
            if ("ping".equalsIgnoreCase(text)) {
                ctx.writeAndFlush(new TextWebSocketFrame("pong"));
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        clients.remove(ctx.channel());
        LOG.log(System.Logger.Level.DEBUG, "Client disconnected: {0} (total: {1})",
                ctx.channel().remoteAddress(), clients.size());
    }

    private String serializeActiveThreads() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (var event : activeThreads.getAll()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("{");
            sb.append("\"threadId\":").append(event.threadId()).append(",");
            if (event.threadName() != null) {
                sb.append("\"threadName\":\"").append(escapeJson(event.threadName())).append("\",");
            }
            if (event.carrierThread() > 0) {
                sb.append("\"carrierThread\":").append(event.carrierThread()).append(",");
            }
            sb.append("\"timestamp\":\"").append(event.timestamp()).append("\"");
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private void handleThreadEventsRequest(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        // Parse thread ID from URI: /threads/{threadId}/events
        String path = uri.substring("/threads/".length());
        int slashIndex = path.indexOf('/');
        if (slashIndex <= 0) {
            HttpResponseHelper.sendNotFound(ctx, request);
            return;
        }

        String threadIdStr = path.substring(0, slashIndex);
        long threadId;
        try {
            threadId = Long.parseLong(threadIdStr);
        } catch (NumberFormatException e) {
            HttpResponseHelper.sendNotFound(ctx, request);
            return;
        }

        // Get events for this thread
        var events = threadEvents.getEvents(threadId);

        // Build JSON response
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"threadId\":").append(threadId).append(",");
        sb.append("\"eventCount\":").append(events.size()).append(",");
        sb.append("\"events\":[");
        boolean first = true;
        for (String eventJson : events) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(eventJson);
        }
        sb.append("]");
        sb.append("}");

        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(System.Logger.Level.WARNING, "Connection error: {0}", cause.getMessage());
        ctx.close();
    }
}
