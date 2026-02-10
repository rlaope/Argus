package io.argus.server.handler;

import java.util.Map;

import io.argus.core.config.AgentConfig;
import io.argus.server.analysis.AllocationAnalyzer;
import io.argus.server.analysis.ContentionAnalyzer;
import io.argus.server.analysis.CorrelationAnalyzer;
import io.argus.server.analysis.FlameGraphAnalyzer;
import io.argus.server.analysis.CPUAnalyzer;
import io.argus.server.analysis.GCAnalyzer;
import io.argus.server.analysis.MetaspaceAnalyzer;
import io.argus.server.analysis.MethodProfilingAnalyzer;
import io.argus.server.http.HttpResponseHelper;
import io.argus.server.http.StaticFileHandler;
import io.argus.server.metrics.PrometheusMetricsCollector;
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

    private final AgentConfig config;
    private final ChannelGroup clients;
    private final ServerMetrics metrics;
    private final ActiveThreadsRegistry activeThreads;
    private final ThreadEventsBuffer threadEvents;
    private final GCAnalyzer gcAnalyzer;
    private final CPUAnalyzer cpuAnalyzer;
    private final AllocationAnalyzer allocationAnalyzer;
    private final MetaspaceAnalyzer metaspaceAnalyzer;
    private final MethodProfilingAnalyzer methodProfilingAnalyzer;
    private final FlameGraphAnalyzer flameGraphAnalyzer;
    private final ContentionAnalyzer contentionAnalyzer;
    private final CorrelationAnalyzer correlationAnalyzer;
    private final EventBroadcaster broadcaster;
    private final PrometheusMetricsCollector prometheusCollector;
    private final StaticFileHandler staticFileHandler;

    private WebSocketServerHandshaker handshaker;

    /**
     * Creates a new channel handler (backward compatible).
     *
     * @param clients       the channel group for WebSocket clients
     * @param metrics       the server metrics tracker
     * @param activeThreads the active threads registry
     * @param threadEvents  the per-thread events buffer
     * @param gcAnalyzer    the GC analyzer
     * @param cpuAnalyzer   the CPU analyzer
     * @param broadcaster   the event broadcaster
     */
    public ArgusChannelHandler(
            ChannelGroup clients,
            ServerMetrics metrics,
            ActiveThreadsRegistry activeThreads,
            ThreadEventsBuffer threadEvents,
            GCAnalyzer gcAnalyzer,
            CPUAnalyzer cpuAnalyzer,
            EventBroadcaster broadcaster) {
        this(null, clients, metrics, activeThreads, threadEvents, gcAnalyzer, cpuAnalyzer,
                null, null, null, null, null, null, broadcaster, null);
    }

    /**
     * Creates a new channel handler with full analyzer support.
     *
     * @param config                  the agent configuration (null for defaults)
     * @param clients                 the channel group for WebSocket clients
     * @param metrics                 the server metrics tracker
     * @param activeThreads           the active threads registry
     * @param threadEvents            the per-thread events buffer
     * @param gcAnalyzer              the GC analyzer
     * @param cpuAnalyzer             the CPU analyzer
     * @param allocationAnalyzer      the allocation analyzer
     * @param metaspaceAnalyzer       the metaspace analyzer
     * @param methodProfilingAnalyzer the method profiling analyzer
     * @param flameGraphAnalyzer      the flame graph analyzer (null if profiling disabled)
     * @param contentionAnalyzer      the contention analyzer
     * @param correlationAnalyzer     the correlation analyzer
     * @param broadcaster             the event broadcaster
     * @param prometheusCollector     the Prometheus metrics collector (null if disabled)
     */
    public ArgusChannelHandler(
            AgentConfig config,
            ChannelGroup clients,
            ServerMetrics metrics,
            ActiveThreadsRegistry activeThreads,
            ThreadEventsBuffer threadEvents,
            GCAnalyzer gcAnalyzer,
            CPUAnalyzer cpuAnalyzer,
            AllocationAnalyzer allocationAnalyzer,
            MetaspaceAnalyzer metaspaceAnalyzer,
            MethodProfilingAnalyzer methodProfilingAnalyzer,
            FlameGraphAnalyzer flameGraphAnalyzer,
            ContentionAnalyzer contentionAnalyzer,
            CorrelationAnalyzer correlationAnalyzer,
            EventBroadcaster broadcaster,
            PrometheusMetricsCollector prometheusCollector) {
        this.config = config != null ? config : AgentConfig.defaults();
        this.clients = clients;
        this.metrics = metrics;
        this.activeThreads = activeThreads;
        this.threadEvents = threadEvents;
        this.gcAnalyzer = gcAnalyzer;
        this.cpuAnalyzer = cpuAnalyzer;
        this.allocationAnalyzer = allocationAnalyzer;
        this.metaspaceAnalyzer = metaspaceAnalyzer;
        this.methodProfilingAnalyzer = methodProfilingAnalyzer;
        this.flameGraphAnalyzer = flameGraphAnalyzer;
        this.contentionAnalyzer = contentionAnalyzer;
        this.correlationAnalyzer = correlationAnalyzer;
        this.broadcaster = broadcaster;
        this.prometheusCollector = prometheusCollector;
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

        // Prometheus metrics endpoint
        if ("/prometheus".equals(uri)) {
            handlePrometheusMetrics(ctx, request);
            return;
        }

        // Config endpoint (feature flags)
        if ("/config".equals(uri)) {
            handleConfig(ctx, request);
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

        // Carrier threads analysis endpoint
        if ("/carrier-threads".equals(uri)) {
            handleCarrierThreads(ctx, request);
            return;
        }

        // GC analysis endpoint
        if ("/gc-analysis".equals(uri)) {
            handleGCAnalysis(ctx, request);
            return;
        }

        // CPU metrics endpoint
        if ("/cpu-metrics".equals(uri)) {
            handleCPUMetrics(ctx, request);
            return;
        }

        // Allocation analysis endpoint
        if ("/allocation-analysis".equals(uri)) {
            handleAllocationAnalysis(ctx, request);
            return;
        }

        // Metaspace metrics endpoint
        if ("/metaspace-metrics".equals(uri)) {
            handleMetaspaceMetrics(ctx, request);
            return;
        }

        // Method profiling endpoint
        if ("/method-profiling".equals(uri)) {
            handleMethodProfiling(ctx, request);
            return;
        }

        // Flame graph endpoint
        if (uri.startsWith("/flame-graph")) {
            handleFlameGraph(ctx, request, uri);
            return;
        }

        // Contention analysis endpoint
        if ("/contention-analysis".equals(uri)) {
            handleContentionAnalysis(ctx, request);
            return;
        }

        // Correlation analysis endpoint
        if ("/correlation".equals(uri)) {
            handleCorrelation(ctx, request);
            return;
        }

        // Export endpoint: /export?format=csv|json|jsonl&types=START,END,PINNED&from=ISO&to=ISO
        if (uri.startsWith("/export")) {
            handleExport(ctx, request, uri);
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
                    // Send current thread state to the new client
                    broadcaster.sendCurrentState(ctx.channel());
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

    private void handleConfig(ChannelHandlerContext ctx, FullHttpRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"features\":{");
        sb.append("\"gc\":{\"enabled\":").append(config.isGcEnabled()).append(",\"overhead\":\"low\"},");
        sb.append("\"cpu\":{\"enabled\":").append(config.isCpuEnabled()).append(",\"overhead\":\"low\"");
        sb.append(",\"intervalMs\":").append(config.getCpuIntervalMs()).append("},");
        sb.append("\"metaspace\":{\"enabled\":").append(config.isMetaspaceEnabled()).append(",\"overhead\":\"low\"},");
        sb.append("\"allocation\":{\"enabled\":").append(config.isAllocationEnabled()).append(",\"overhead\":\"high\"");
        sb.append(",\"thresholdBytes\":").append(config.getAllocationThreshold()).append("},");
        sb.append("\"profiling\":{\"enabled\":").append(config.isProfilingEnabled()).append(",\"overhead\":\"high\"");
        sb.append(",\"intervalMs\":").append(config.getProfilingIntervalMs()).append("},");
        sb.append("\"contention\":{\"enabled\":").append(config.isContentionEnabled()).append(",\"overhead\":\"medium\"");
        sb.append(",\"thresholdMs\":").append(config.getContentionThresholdMs()).append("},");
        sb.append("\"correlation\":{\"enabled\":").append(config.isCorrelationEnabled()).append(",\"overhead\":\"low\"},");
        sb.append("\"prometheus\":{\"enabled\":").append(config.isPrometheusEnabled()).append(",\"overhead\":\"low\"},");
        sb.append("\"otlp\":{\"enabled\":").append(config.isOtlpEnabled()).append(",\"overhead\":\"low\"");
        if (config.isOtlpEnabled()) {
            sb.append(",\"endpoint\":\"").append(escapeJson(config.getOtlpEndpoint())).append("\"");
            sb.append(",\"intervalMs\":").append(config.getOtlpIntervalMs());
        }
        sb.append("}");
        sb.append("},");
        sb.append("\"server\":{");
        sb.append("\"port\":").append(config.getServerPort()).append(",");
        sb.append("\"bufferSize\":").append(config.getBufferSize());
        sb.append("}");
        sb.append("}");

        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private void handlePrometheusMetrics(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (prometheusCollector == null) {
            HttpResponseHelper.sendNotFound(ctx, request);
            return;
        }

        long startNanos = System.nanoTime();
        String metricsText = prometheusCollector.collectMetrics();
        long durationNanos = System.nanoTime() - startNanos;

        StringBuilder sb = new StringBuilder(metricsText.length() + 128);
        sb.append(metricsText);
        sb.append("# HELP argus_scrape_duration_seconds Time taken to collect metrics\n");
        sb.append("# TYPE argus_scrape_duration_seconds gauge\n");
        sb.append("argus_scrape_duration_seconds ")
                .append(String.format("%.6f", durationNanos / 1_000_000_000.0))
                .append('\n');

        HttpResponseHelper.sendPrometheus(ctx, request, sb.toString());
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

    private void handleCarrierThreads(ChannelHandlerContext ctx, FullHttpRequest request) {
        var analysis = broadcaster.getCarrierAnalyzer().getAnalysis();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"totalCarriers\":").append(analysis.totalCarriers()).append(",");
        sb.append("\"totalVirtualThreadsHandled\":").append(analysis.totalVirtualThreadsHandled()).append(",");
        sb.append("\"avgVirtualThreadsPerCarrier\":").append(analysis.avgVirtualThreadsPerCarrier()).append(",");
        sb.append("\"carriers\":[");

        boolean first = true;
        for (var carrier : analysis.carriers()) {
            if (!first) {
                sb.append(",");
            }
            first = false;

            sb.append("{");
            sb.append("\"carrierId\":").append(carrier.carrierId()).append(",");
            sb.append("\"totalVirtualThreads\":").append(carrier.totalVirtualThreads()).append(",");
            sb.append("\"currentVirtualThreads\":").append(carrier.currentVirtualThreads()).append(",");
            sb.append("\"pinnedEvents\":").append(carrier.pinnedEvents()).append(",");
            sb.append("\"utilizationPercent\":").append(carrier.utilizationPercent()).append(",");
            sb.append("\"lastActivity\":\"").append(carrier.lastActivity()).append("\"");
            sb.append("}");
        }

        sb.append("]");
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

    private void handleGCAnalysis(ChannelHandlerContext ctx, FullHttpRequest request) {
        var analysis = gcAnalyzer.getAnalysis();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"totalGCEvents\":").append(analysis.totalGCEvents()).append(",");
        sb.append("\"totalPauseTimeMs\":").append(analysis.totalPauseTimeMs()).append(",");
        sb.append("\"avgPauseTimeMs\":").append(String.format("%.2f", analysis.avgPauseTimeMs())).append(",");
        sb.append("\"maxPauseTimeMs\":").append(analysis.maxPauseTimeMs()).append(",");
        sb.append("\"currentHeapUsed\":").append(analysis.currentHeapUsed()).append(",");
        sb.append("\"currentHeapCommitted\":").append(analysis.currentHeapCommitted()).append(",");
        sb.append("\"gcOverheadPercent\":").append(String.format("%.2f", analysis.gcOverheadPercent())).append(",");
        sb.append("\"isOverheadWarning\":").append(analysis.isOverheadWarning()).append(",");

        if (analysis.lastGCTime() != null) {
            sb.append("\"lastGCTime\":\"").append(analysis.lastGCTime()).append("\",");
        }

        // Cause distribution
        sb.append("\"causeDistribution\":{");
        boolean first = true;
        for (var entry : analysis.causeDistribution().entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
        }
        sb.append("},");

        // Recent GCs
        sb.append("\"recentGCs\":[");
        first = true;
        for (var gc : analysis.recentGCs()) {
            if (!first) sb.append(",");
            first = false;

            sb.append("{");
            sb.append("\"timestamp\":\"").append(gc.timestamp()).append("\",");
            if (gc.gcName() != null) {
                sb.append("\"gcName\":\"").append(escapeJson(gc.gcName())).append("\",");
            }
            if (gc.gcCause() != null) {
                sb.append("\"gcCause\":\"").append(escapeJson(gc.gcCause())).append("\",");
            }
            sb.append("\"pauseTimeMs\":").append(String.format("%.2f", gc.pauseTimeMs())).append(",");
            sb.append("\"heapUsedBefore\":").append(gc.heapUsedBefore()).append(",");
            sb.append("\"heapUsedAfter\":").append(gc.heapUsedAfter()).append(",");
            sb.append("\"memoryReclaimed\":").append(gc.memoryReclaimed());
            sb.append("}");
        }
        sb.append("]");

        sb.append("}");

        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private void handleCPUMetrics(ChannelHandlerContext ctx, FullHttpRequest request) {
        var analysis = cpuAnalyzer.getAnalysis();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"totalSamples\":").append(analysis.totalSamples()).append(",");
        sb.append("\"currentJvmUser\":").append(String.format("%.4f", analysis.currentJvmUser())).append(",");
        sb.append("\"currentJvmSystem\":").append(String.format("%.4f", analysis.currentJvmSystem())).append(",");
        sb.append("\"currentJvmTotal\":").append(String.format("%.4f", analysis.currentJvmTotal())).append(",");
        sb.append("\"currentMachineTotal\":").append(String.format("%.4f", analysis.currentMachineTotal())).append(",");
        sb.append("\"currentJvmPercent\":").append(String.format("%.2f", analysis.currentJvmPercent())).append(",");
        sb.append("\"currentMachinePercent\":").append(String.format("%.2f", analysis.currentMachinePercent())).append(",");
        sb.append("\"avgJvmTotal\":").append(String.format("%.4f", analysis.avgJvmTotal())).append(",");
        sb.append("\"avgMachineTotal\":").append(String.format("%.4f", analysis.avgMachineTotal())).append(",");
        sb.append("\"peakJvmTotal\":").append(String.format("%.4f", analysis.peakJvmTotal())).append(",");
        sb.append("\"peakMachineTotal\":").append(String.format("%.4f", analysis.peakMachineTotal())).append(",");

        if (analysis.lastUpdateTime() != null) {
            sb.append("\"lastUpdateTime\":\"").append(analysis.lastUpdateTime()).append("\",");
        }

        // History
        sb.append("\"history\":[");
        boolean first = true;
        for (var snapshot : analysis.history()) {
            if (!first) sb.append(",");
            first = false;

            sb.append("{");
            sb.append("\"timestamp\":\"").append(snapshot.timestamp()).append("\",");
            sb.append("\"jvmUser\":").append(String.format("%.4f", snapshot.jvmUser())).append(",");
            sb.append("\"jvmSystem\":").append(String.format("%.4f", snapshot.jvmSystem())).append(",");
            sb.append("\"jvmTotal\":").append(String.format("%.4f", snapshot.jvmTotal())).append(",");
            sb.append("\"machineTotal\":").append(String.format("%.4f", snapshot.machineTotal())).append(",");
            sb.append("\"jvmPercent\":").append(String.format("%.2f", snapshot.jvmPercent())).append(",");
            sb.append("\"machinePercent\":").append(String.format("%.2f", snapshot.machinePercent()));
            sb.append("}");
        }
        sb.append("]");

        sb.append("}");

        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private void handleAllocationAnalysis(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (allocationAnalyzer == null) {
            HttpResponseHelper.sendJson(ctx, request, "{\"error\":\"Allocation tracking not enabled\"}");
            return;
        }

        var analysis = allocationAnalyzer.getAnalysis();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"totalAllocations\":").append(analysis.totalAllocations()).append(",");
        sb.append("\"totalBytesAllocated\":").append(analysis.totalBytesAllocated()).append(",");
        sb.append("\"totalAllocatedMB\":").append(String.format("%.2f", analysis.totalAllocatedMB())).append(",");
        sb.append("\"allocationRateBytesPerSec\":").append(String.format("%.2f", analysis.allocationRateBytesPerSec())).append(",");
        sb.append("\"allocationRateMBPerSec\":").append(String.format("%.2f", analysis.allocationRateMBPerSec())).append(",");
        sb.append("\"peakAllocationRateMBPerSec\":").append(String.format("%.2f", analysis.peakAllocationRateMBPerSec())).append(",");

        // Top allocating classes
        sb.append("\"topAllocatingClasses\":[");
        boolean first = true;
        for (var classAlloc : analysis.topAllocatingClasses()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"className\":\"").append(escapeJson(classAlloc.className())).append("\",");
            sb.append("\"allocationCount\":").append(classAlloc.allocationCount()).append(",");
            sb.append("\"totalBytes\":").append(classAlloc.totalBytes());
            sb.append("}");
        }
        sb.append("],");

        // History
        sb.append("\"history\":[");
        first = true;
        for (var snapshot : analysis.history()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"timestamp\":\"").append(snapshot.timestamp()).append("\",");
            sb.append("\"totalAllocations\":").append(snapshot.totalAllocations()).append(",");
            sb.append("\"totalBytes\":").append(snapshot.totalBytes()).append(",");
            sb.append("\"allocationRateMBPerSec\":").append(String.format("%.2f", snapshot.allocationRateMBPerSec()));
            sb.append("}");
        }
        sb.append("]");

        sb.append("}");

        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private void handleMetaspaceMetrics(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (metaspaceAnalyzer == null) {
            HttpResponseHelper.sendJson(ctx, request, "{\"error\":\"Metaspace monitoring not enabled\"}");
            return;
        }

        var analysis = metaspaceAnalyzer.getAnalysis();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"currentUsed\":").append(analysis.currentUsed()).append(",");
        sb.append("\"currentCommitted\":").append(analysis.currentCommitted()).append(",");
        sb.append("\"currentReserved\":").append(analysis.currentReserved()).append(",");
        sb.append("\"currentClassCount\":").append(analysis.currentClassCount()).append(",");
        sb.append("\"currentUsedMB\":").append(String.format("%.2f", analysis.currentUsedMB())).append(",");
        sb.append("\"currentCommittedMB\":").append(String.format("%.2f", analysis.currentCommittedMB())).append(",");
        sb.append("\"peakUsed\":").append(analysis.peakUsed()).append(",");
        sb.append("\"peakUsedMB\":").append(String.format("%.2f", analysis.peakUsedMB())).append(",");
        sb.append("\"growthRateMBPerMin\":").append(String.format("%.4f", analysis.growthRateMBPerMin())).append(",");

        if (analysis.lastUpdateTime() != null) {
            sb.append("\"lastUpdateTime\":\"").append(analysis.lastUpdateTime()).append("\",");
        }

        // History
        sb.append("\"history\":[");
        boolean first = true;
        for (var snapshot : analysis.history()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"timestamp\":\"").append(snapshot.timestamp()).append("\",");
            sb.append("\"usedMB\":").append(String.format("%.2f", snapshot.usedMB())).append(",");
            sb.append("\"committedMB\":").append(String.format("%.2f", snapshot.committedMB())).append(",");
            sb.append("\"classCount\":").append(snapshot.classCount());
            sb.append("}");
        }
        sb.append("]");

        sb.append("}");

        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private void handleFlameGraph(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        if (flameGraphAnalyzer == null) {
            HttpResponseHelper.sendJson(ctx, request, "{\"error\":\"Method profiling not enabled\"}");
            return;
        }

        var params = parseQueryParams(uri);
        String format = params.getOrDefault("format", "json");

        if ("true".equals(params.get("reset"))) {
            flameGraphAnalyzer.clear();
            HttpResponseHelper.sendJson(ctx, request, "{\"status\":\"cleared\"}");
            return;
        }

        if ("collapsed".equals(format)) {
            String collapsed = flameGraphAnalyzer.getCollapsedStacks();
            HttpResponseHelper.sendPlainText(ctx, request, collapsed);
        } else {
            String json = flameGraphAnalyzer.getFlameGraphJson();
            HttpResponseHelper.sendJson(ctx, request, json);
        }
    }

    private void handleMethodProfiling(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (methodProfilingAnalyzer == null) {
            HttpResponseHelper.sendJson(ctx, request, "{\"error\":\"Method profiling not enabled\"}");
            return;
        }

        var analysis = methodProfilingAnalyzer.getAnalysis();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"totalSamples\":").append(analysis.totalSamples()).append(",");

        // Top methods
        sb.append("\"topMethods\":[");
        boolean first = true;
        for (var method : analysis.topMethods()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"className\":\"").append(escapeJson(method.className())).append("\",");
            sb.append("\"methodName\":\"").append(escapeJson(method.methodName())).append("\",");
            sb.append("\"sampleCount\":").append(method.sampleCount()).append(",");
            sb.append("\"percentage\":").append(String.format("%.2f", method.percentage()));
            sb.append("}");
        }
        sb.append("],");

        // Package distribution
        sb.append("\"packageDistribution\":{");
        first = true;
        for (var entry : analysis.packageDistribution().entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
        }
        sb.append("}");

        sb.append("}");

        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private void handleContentionAnalysis(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (contentionAnalyzer == null) {
            HttpResponseHelper.sendJson(ctx, request, "{\"error\":\"Contention tracking not enabled\"}");
            return;
        }

        var analysis = contentionAnalyzer.getAnalysis();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"totalContentionEvents\":").append(analysis.totalContentionEvents()).append(",");
        sb.append("\"totalContentionTimeMs\":").append(analysis.totalContentionTimeMs()).append(",");

        // Hotspots
        sb.append("\"hotspots\":[");
        boolean first = true;
        for (var hotspot : analysis.hotspots()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"monitorClass\":\"").append(escapeJson(hotspot.monitorClass())).append("\",");
            sb.append("\"eventCount\":").append(hotspot.eventCount()).append(",");
            sb.append("\"totalTimeMs\":").append(hotspot.totalTimeMs()).append(",");
            sb.append("\"avgTimeMs\":").append(String.format("%.2f", hotspot.avgTimeMs())).append(",");
            sb.append("\"enterCount\":").append(hotspot.enterCount()).append(",");
            sb.append("\"waitCount\":").append(hotspot.waitCount()).append(",");
            sb.append("\"percentage\":").append(String.format("%.2f", hotspot.percentage()));
            sb.append("}");
        }
        sb.append("],");

        // Thread contention time
        sb.append("\"threadContentionTime\":{");
        first = true;
        for (var entry : analysis.threadContentionTime().entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
        }
        sb.append("}");

        sb.append("}");

        HttpResponseHelper.sendJson(ctx, request, sb.toString());
    }

    private void handleCorrelation(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (correlationAnalyzer == null) {
            HttpResponseHelper.sendJson(ctx, request, "{\"error\":\"Correlation analysis not enabled\"}");
            return;
        }

        var analysis = correlationAnalyzer.getAnalysis();

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // GC-CPU correlations
        sb.append("\"gcCpuCorrelations\":[");
        boolean first = true;
        for (var corr : analysis.gcCpuCorrelations()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"timestamp\":\"").append(corr.timestamp()).append("\",");
            sb.append("\"primaryEvent\":\"").append(corr.primaryEvent()).append("\",");
            sb.append("\"correlatedEvent\":\"").append(corr.correlatedEvent()).append("\",");
            sb.append("\"description\":\"").append(escapeJson(corr.description())).append("\"");
            sb.append("}");
        }
        sb.append("],");

        // GC-Pinning correlations
        sb.append("\"gcPinningCorrelations\":[");
        first = true;
        for (var corr : analysis.gcPinningCorrelations()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"timestamp\":\"").append(corr.timestamp()).append("\",");
            sb.append("\"primaryEvent\":\"").append(corr.primaryEvent()).append("\",");
            sb.append("\"correlatedEvent\":\"").append(corr.correlatedEvent()).append("\",");
            sb.append("\"description\":\"").append(escapeJson(corr.description())).append("\"");
            sb.append("}");
        }
        sb.append("],");

        // Recommendations
        sb.append("\"recommendations\":[");
        first = true;
        for (var rec : analysis.recommendations()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"type\":\"").append(rec.type().name()).append("\",");
            sb.append("\"title\":\"").append(escapeJson(rec.title())).append("\",");
            sb.append("\"description\":\"").append(escapeJson(rec.description())).append("\",");
            sb.append("\"severity\":\"").append(rec.severity().name()).append("\"");
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

    private void handleExport(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        // Parse query parameters
        var params = parseQueryParams(uri);
        String format = params.getOrDefault("format", "json");
        String typesParam = params.getOrDefault("types", "START,END,PINNED,SUBMIT_FAILED");
        String fromParam = params.get("from");
        String toParam = params.get("to");

        // Parse event types filter
        var allowedTypes = java.util.Set.of(typesParam.split(","));

        // Parse time range
        java.time.Instant fromTime = null;
        java.time.Instant toTime = null;
        try {
            if (fromParam != null && !fromParam.isEmpty()) {
                fromTime = java.time.Instant.parse(fromParam);
            }
            if (toParam != null && !toParam.isEmpty()) {
                toTime = java.time.Instant.parse(toParam);
            }
        } catch (Exception e) {
            HttpResponseHelper.sendBadRequest(ctx, request, "Invalid time format. Use ISO-8601.");
            return;
        }

        // Get all events from broadcaster
        var allEvents = broadcaster.getRecentEvents();

        // Filter events
        final java.time.Instant finalFromTime = fromTime;
        final java.time.Instant finalToTime = toTime;
        var filteredEvents = allEvents.stream()
                .filter(e -> allowedTypes.contains(e.eventType().name().replace("VIRTUAL_THREAD_", "")))
                .filter(e -> finalFromTime == null || !e.timestamp().isBefore(finalFromTime))
                .filter(e -> finalToTime == null || !e.timestamp().isAfter(finalToTime))
                .toList();

        // Export in requested format
        String content;
        String contentType;
        String filename;

        switch (format.toLowerCase()) {
            case "csv":
                content = exportToCsv(filteredEvents);
                contentType = "text/csv";
                filename = "argus-events.csv";
                break;
            case "jsonl":
                content = exportToJsonLines(filteredEvents);
                contentType = "application/x-ndjson";
                filename = "argus-events.jsonl";
                break;
            case "json":
            default:
                content = exportToJson(filteredEvents);
                contentType = "application/json";
                filename = "argus-events.json";
                break;
        }

        HttpResponseHelper.sendDownload(ctx, request, content, contentType, filename);
    }

    private String exportToCsv(java.util.List<io.argus.core.event.VirtualThreadEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,type,threadId,threadName,duration,carrierThread,stackTrace\n");

        for (var event : events) {
            sb.append(event.timestamp()).append(",");
            sb.append(event.eventType().name().replace("VIRTUAL_THREAD_", "")).append(",");
            sb.append(event.threadId()).append(",");
            sb.append(escapeCsv(event.threadName())).append(",");
            sb.append(event.duration()).append(",");
            sb.append(event.carrierThread() > 0 ? event.carrierThread() : "").append(",");
            sb.append(escapeCsv(event.stackTrace())).append("\n");
        }

        return sb.toString();
    }

    private String exportToJson(java.util.List<io.argus.core.event.VirtualThreadEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"exportTime\":\"").append(java.time.Instant.now()).append("\",");
        sb.append("\"eventCount\":").append(events.size()).append(",");
        sb.append("\"events\":[");

        boolean first = true;
        for (var event : events) {
            if (!first) sb.append(",");
            first = false;
            sb.append(eventToJson(event));
        }

        sb.append("]}");
        return sb.toString();
    }

    private String exportToJsonLines(java.util.List<io.argus.core.event.VirtualThreadEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (var event : events) {
            sb.append(eventToJson(event)).append("\n");
        }
        return sb.toString();
    }

    private String eventToJson(io.argus.core.event.VirtualThreadEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"timestamp\":\"").append(event.timestamp()).append("\",");
        sb.append("\"type\":\"").append(event.eventType().name().replace("VIRTUAL_THREAD_", "")).append("\",");
        sb.append("\"threadId\":").append(event.threadId()).append(",");
        if (event.threadName() != null) {
            sb.append("\"threadName\":\"").append(escapeJson(event.threadName())).append("\",");
        }
        sb.append("\"duration\":").append(event.duration());
        if (event.carrierThread() > 0) {
            sb.append(",\"carrierThread\":").append(event.carrierThread());
        }
        if (event.stackTrace() != null) {
            sb.append(",\"stackTrace\":\"").append(escapeJson(event.stackTrace())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private java.util.Map<String, String> parseQueryParams(String uri) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        int queryStart = uri.indexOf('?');
        if (queryStart < 0) return params;

        String query = uri.substring(queryStart + 1);
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0) {
                String key = param.substring(0, eq);
                String value = java.net.URLDecoder.decode(param.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
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
