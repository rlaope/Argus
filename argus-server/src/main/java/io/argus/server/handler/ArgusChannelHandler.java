package io.argus.server.handler;

import java.util.Map;

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

        // Single thread dump endpoint: /threads/{threadId}/dump
        if (uri.startsWith("/threads/") && uri.endsWith("/dump")) {
            handleSingleThreadDump(ctx, request, uri);
            return;
        }

        // All threads dump endpoint
        if ("/thread-dump".equals(uri)) {
            handleAllThreadsDump(ctx, request);
            return;
        }

        // Pinning analysis endpoint
        if ("/pinning-analysis".equals(uri)) {
            handlePinningAnalysis(ctx, request);
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

    private void handleSingleThreadDump(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        // Parse thread ID from URI: /threads/{threadId}/dump
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

        // Find the thread and get its stack trace
        Thread targetThread = findThreadById(threadId);

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"threadId\":").append(threadId).append(",");
        sb.append("\"timestamp\":\"").append(java.time.Instant.now()).append("\",");

        if (targetThread != null) {
            sb.append("\"threadName\":\"").append(escapeJson(targetThread.getName())).append("\",");
            sb.append("\"state\":\"").append(targetThread.getState()).append("\",");
            sb.append("\"isVirtual\":").append(targetThread.isVirtual()).append(",");
            sb.append("\"stackTrace\":\"").append(escapeJson(formatStackTrace(targetThread.getStackTrace()))).append("\"");
        } else {
            sb.append("\"error\":\"Thread not found or already terminated\"");
        }

        sb.append("}");
        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private void handlePinningAnalysis(ChannelHandlerContext ctx, FullHttpRequest request) {
        var analysis = broadcaster.getPinningAnalyzer().getAnalysis();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"totalPinnedEvents\":").append(analysis.totalPinnedEvents()).append(",");
        sb.append("\"uniqueStackTraces\":").append(analysis.uniqueStackTraces()).append(",");
        sb.append("\"hotspots\":[");

        boolean first = true;
        for (var hotspot : analysis.hotspots()) {
            if (!first) {
                sb.append(",");
            }
            first = false;

            sb.append("{");
            sb.append("\"rank\":").append(hotspot.rank()).append(",");
            sb.append("\"count\":").append(hotspot.count()).append(",");
            sb.append("\"percentage\":").append(String.format("%.1f", hotspot.percentage())).append(",");
            sb.append("\"stackTraceHash\":\"").append(hotspot.stackTraceHash()).append("\",");
            sb.append("\"topFrame\":\"").append(escapeJson(hotspot.topFrame())).append("\",");
            sb.append("\"fullStackTrace\":\"").append(escapeJson(hotspot.fullStackTrace())).append("\"");
            sb.append("}");
        }

        sb.append("]");
        sb.append("}");

        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private void handleAllThreadsDump(ChannelHandlerContext ctx, FullHttpRequest request) {
        Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"timestamp\":\"").append(java.time.Instant.now()).append("\",");
        sb.append("\"totalThreads\":").append(allThreads.size()).append(",");
        sb.append("\"threads\":[");

        boolean first = true;
        for (Map.Entry<Thread, StackTraceElement[]> entry : allThreads.entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stackTrace = entry.getValue();

            if (!first) {
                sb.append(",");
            }
            first = false;

            sb.append("{");
            sb.append("\"threadId\":").append(thread.threadId()).append(",");
            sb.append("\"threadName\":\"").append(escapeJson(thread.getName())).append("\",");
            sb.append("\"state\":\"").append(thread.getState()).append("\",");
            sb.append("\"isVirtual\":").append(thread.isVirtual()).append(",");
            sb.append("\"stackTrace\":\"").append(escapeJson(formatStackTrace(stackTrace))).append("\"");
            sb.append("}");
        }

        sb.append("]");
        sb.append("}");
        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private Thread findThreadById(long threadId) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.threadId() == threadId)
                .findFirst()
                .orElse(null);
    }

    private String formatStackTrace(StackTraceElement[] stackTrace) {
        if (stackTrace == null || stackTrace.length == 0) {
            return "(no stack trace available)";
        }
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : stackTrace) {
            sb.append("    at ").append(element.toString()).append("\\n");
        }
        return sb.toString();
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
