package io.argus.server.websocket;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.CPUEvent;
import io.argus.core.event.GCEvent;
import io.argus.core.event.VirtualThreadEvent;
import io.argus.server.analysis.CarrierThreadAnalyzer;
import io.argus.server.analysis.CPUAnalyzer;
import io.argus.server.analysis.GCAnalyzer;
import io.argus.server.analysis.PinningAnalyzer;
import io.argus.server.metrics.ServerMetrics;
import io.argus.server.serialization.EventJsonSerializer;
import io.argus.server.state.ActiveThreadsRegistry;
import io.argus.server.state.RecentEventsBuffer;
import io.argus.server.state.ThreadEventsBuffer;
import io.argus.server.state.ThreadStateManager;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Broadcasts virtual thread events to connected WebSocket clients.
 *
 * <p>This class drains events from the ring buffer, updates metrics and state,
 * and broadcasts serialized events to all connected clients.
 */
public final class EventBroadcaster {

    private static final long BROADCAST_INTERVAL_MS = 10;
    private static final int MAX_EXPORT_EVENTS = 10000;

    private final RingBuffer<VirtualThreadEvent> eventBuffer;
    private final RingBuffer<GCEvent> gcEventBuffer;
    private final RingBuffer<CPUEvent> cpuEventBuffer;
    private final ChannelGroup clients;
    private final List<VirtualThreadEvent> exportableEvents = Collections.synchronizedList(new ArrayList<>());
    private final ServerMetrics metrics;
    private final ActiveThreadsRegistry activeThreads;
    private final RecentEventsBuffer recentEvents;
    private final ThreadEventsBuffer threadEvents;
    private final PinningAnalyzer pinningAnalyzer;
    private final CarrierThreadAnalyzer carrierAnalyzer;
    private final GCAnalyzer gcAnalyzer;
    private final CPUAnalyzer cpuAnalyzer;
    private final ThreadStateManager threadStateManager;
    private final EventJsonSerializer serializer;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService stateScheduler;

    /**
     * Creates an event broadcaster.
     *
     * @param eventBuffer        the ring buffer to drain virtual thread events from
     * @param gcEventBuffer      the ring buffer to drain GC events from (can be null)
     * @param cpuEventBuffer     the ring buffer to drain CPU events from (can be null)
     * @param clients            the channel group of connected WebSocket clients
     * @param metrics            the server metrics tracker
     * @param activeThreads      the active threads registry
     * @param recentEvents       the recent events buffer
     * @param threadEvents       the per-thread events buffer
     * @param pinningAnalyzer    the pinning analyzer for hotspot detection
     * @param carrierAnalyzer    the carrier thread analyzer
     * @param gcAnalyzer         the GC analyzer
     * @param cpuAnalyzer        the CPU analyzer
     * @param threadStateManager the thread state manager for real-time state tracking
     * @param serializer         the event JSON serializer
     */
    public EventBroadcaster(
            RingBuffer<VirtualThreadEvent> eventBuffer,
            RingBuffer<GCEvent> gcEventBuffer,
            RingBuffer<CPUEvent> cpuEventBuffer,
            ChannelGroup clients,
            ServerMetrics metrics,
            ActiveThreadsRegistry activeThreads,
            RecentEventsBuffer recentEvents,
            ThreadEventsBuffer threadEvents,
            PinningAnalyzer pinningAnalyzer,
            CarrierThreadAnalyzer carrierAnalyzer,
            GCAnalyzer gcAnalyzer,
            CPUAnalyzer cpuAnalyzer,
            ThreadStateManager threadStateManager,
            EventJsonSerializer serializer) {
        this.eventBuffer = eventBuffer;
        this.gcEventBuffer = gcEventBuffer;
        this.cpuEventBuffer = cpuEventBuffer;
        this.clients = clients;
        this.metrics = metrics;
        this.activeThreads = activeThreads;
        this.recentEvents = recentEvents;
        this.threadEvents = threadEvents;
        this.pinningAnalyzer = pinningAnalyzer;
        this.carrierAnalyzer = carrierAnalyzer;
        this.gcAnalyzer = gcAnalyzer;
        this.cpuAnalyzer = cpuAnalyzer;
        this.threadStateManager = threadStateManager;
        this.serializer = serializer;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("argus-event-broadcaster").daemon(true).unstarted(r)
        );
        this.stateScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("argus-state-broadcaster").daemon(true).unstarted(r)
        );
    }

    /**
     * Starts the event broadcasting scheduler.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::drainAndBroadcast,
                BROADCAST_INTERVAL_MS, BROADCAST_INTERVAL_MS, TimeUnit.MILLISECONDS);
        // Broadcast state updates at 100ms intervals (10 times per second)
        stateScheduler.scheduleAtFixedRate(this::broadcastStateIfChanged,
                100, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the event broadcasting scheduler.
     */
    public void stop() {
        scheduler.shutdown();
        stateScheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!stateScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                stateScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            stateScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Drains events from the buffer and broadcasts to clients.
     */
    private void drainAndBroadcast() {
        // Drain virtual thread events
        if (eventBuffer != null) {
            eventBuffer.drain(event -> {
                // Store for export (keep last MAX_EXPORT_EVENTS)
                exportableEvents.add(event);
                while (exportableEvents.size() > MAX_EXPORT_EVENTS) {
                    exportableEvents.remove(0);
                }

                // Update metrics and state
                metrics.incrementTotal();
                switch (event.eventType()) {
                    case VIRTUAL_THREAD_START -> {
                        metrics.incrementStart();
                        activeThreads.register(event.threadId(), event);
                        threadStateManager.onThreadStart(event);
                        carrierAnalyzer.onThreadStart(event);
                    }
                    case VIRTUAL_THREAD_END -> {
                        metrics.incrementEnd();
                        activeThreads.unregister(event.threadId());
                        threadStateManager.onThreadEnd(event);
                        carrierAnalyzer.onThreadEnd(event);
                    }
                    case VIRTUAL_THREAD_PINNED -> {
                        metrics.incrementPinned();
                        pinningAnalyzer.recordPinnedEvent(event);
                        threadStateManager.onThreadPinned(event);
                        carrierAnalyzer.onThreadPinned(event);
                    }
                    case VIRTUAL_THREAD_SUBMIT_FAILED -> metrics.incrementSubmitFailed();
                    default -> {
                        // GC and CPU events handled separately
                    }
                }

                // Serialize event
                String json = serializer.serialize(event);

                // Store in recent events
                recentEvents.add(json);

                // Store per-thread event history
                threadEvents.add(event.threadId(), json);

                // Broadcast to WebSocket clients
                if (!clients.isEmpty()) {
                    TextWebSocketFrame frame = new TextWebSocketFrame(json);
                    clients.writeAndFlush(frame.retain());
                    frame.release();
                }
            });
        }

        // Drain GC events
        if (gcEventBuffer != null && gcAnalyzer != null) {
            gcEventBuffer.drain(event -> {
                gcAnalyzer.recordGCEvent(event);
                metrics.incrementGcEvent();

                // Broadcast GC event to clients
                if (!clients.isEmpty()) {
                    String json = serializeGCEvent(event);
                    TextWebSocketFrame frame = new TextWebSocketFrame(json);
                    clients.writeAndFlush(frame.retain());
                    frame.release();
                }
            });
        }

        // Drain CPU events
        if (cpuEventBuffer != null && cpuAnalyzer != null) {
            cpuEventBuffer.drain(event -> {
                cpuAnalyzer.recordCPUEvent(event);
                metrics.incrementCpuEvent();

                // Broadcast CPU event to clients
                if (!clients.isEmpty()) {
                    String json = serializeCPUEvent(event);
                    TextWebSocketFrame frame = new TextWebSocketFrame(json);
                    clients.writeAndFlush(frame.retain());
                    frame.release();
                }
            });
        }
    }

    /**
     * Serializes a GC event to JSON.
     */
    private String serializeGCEvent(GCEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"GC_EVENT\",");
        sb.append("\"eventType\":\"").append(event.eventType().name()).append("\",");
        sb.append("\"timestamp\":\"").append(event.timestamp()).append("\",");
        if (event.duration() > 0) {
            sb.append("\"duration\":").append(event.duration()).append(",");
            sb.append("\"durationMs\":").append(event.durationMs()).append(",");
        }
        if (event.gcName() != null) {
            sb.append("\"gcName\":\"").append(escapeJson(event.gcName())).append("\",");
        }
        if (event.gcCause() != null) {
            sb.append("\"gcCause\":\"").append(escapeJson(event.gcCause())).append("\",");
        }
        if (event.heapUsedBefore() > 0) {
            sb.append("\"heapUsedBefore\":").append(event.heapUsedBefore()).append(",");
        }
        if (event.heapUsedAfter() > 0) {
            sb.append("\"heapUsedAfter\":").append(event.heapUsedAfter()).append(",");
        }
        if (event.heapCommitted() > 0) {
            sb.append("\"heapCommitted\":").append(event.heapCommitted()).append(",");
        }
        // Remove trailing comma and close
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Serializes a CPU event to JSON.
     */
    private String serializeCPUEvent(CPUEvent event) {
        return String.format(
                "{\"type\":\"CPU_EVENT\",\"timestamp\":\"%s\",\"jvmUser\":%.4f,\"jvmSystem\":%.4f,\"jvmTotal\":%.4f,\"machineTotal\":%.4f,\"jvmPercent\":%.2f,\"machinePercent\":%.2f}",
                event.timestamp(),
                event.jvmUser(),
                event.jvmSystem(),
                event.jvmTotal(),
                event.machineTotal(),
                event.jvmTotalPercent(),
                event.machineTotalPercent()
        );
    }

    /**
     * Sends all recent events to a newly connected client.
     *
     * @param channel the client's channel
     */
    public void sendRecentEvents(Channel channel) {
        for (String eventJson : recentEvents.getAll()) {
            channel.write(new TextWebSocketFrame(eventJson));
        }
        channel.flush();
    }

    /**
     * Returns the pinning analyzer for hotspot analysis.
     *
     * @return the pinning analyzer
     */
    public PinningAnalyzer getPinningAnalyzer() {
        return pinningAnalyzer;
    }

    /**
     * Returns the carrier thread analyzer.
     *
     * @return the carrier thread analyzer
     */
    public CarrierThreadAnalyzer getCarrierAnalyzer() {
        return carrierAnalyzer;
    }

    /**
     * Returns the GC analyzer.
     *
     * @return the GC analyzer
     */
    public GCAnalyzer getGcAnalyzer() {
        return gcAnalyzer;
    }

    /**
     * Returns the CPU analyzer.
     *
     * @return the CPU analyzer
     */
    public CPUAnalyzer getCpuAnalyzer() {
        return cpuAnalyzer;
    }

    /**
     * Returns a copy of recent events for export.
     *
     * @return list of recent events
     */
    public List<VirtualThreadEvent> getRecentEvents() {
        synchronized (exportableEvents) {
            return new ArrayList<>(exportableEvents);
        }
    }

    /**
     * Broadcasts thread state updates if state has changed.
     * Called periodically by the stateScheduler.
     */
    private void broadcastStateIfChanged() {
        if (clients.isEmpty()) {
            return;
        }

        // Cleanup old ended threads
        threadStateManager.cleanup();

        // Only broadcast if state has changed
        if (!threadStateManager.hasStateChanged()) {
            return;
        }

        // Build state update JSON
        String stateJson = buildStateUpdateJson();
        TextWebSocketFrame frame = new TextWebSocketFrame(stateJson);
        clients.writeAndFlush(frame.retain());
        frame.release();
    }

    /**
     * Sends current thread state to a newly connected client.
     *
     * @param channel the client's channel
     */
    public void sendCurrentState(Channel channel) {
        String stateJson = buildStateUpdateJson();
        channel.writeAndFlush(new TextWebSocketFrame(stateJson));
    }

    /**
     * Builds the JSON string for thread state update message.
     *
     * @return JSON string
     */
    private String buildStateUpdateJson() {
        var states = threadStateManager.getStateSnapshot();
        var counts = threadStateManager.getStateCounts();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"THREAD_STATE_UPDATE\",");
        sb.append("\"counts\":{");
        sb.append("\"running\":").append(counts.get(ThreadStateManager.State.RUNNING)).append(",");
        sb.append("\"pinned\":").append(counts.get(ThreadStateManager.State.PINNED)).append(",");
        sb.append("\"ended\":").append(counts.get(ThreadStateManager.State.ENDED));
        sb.append("},");
        sb.append("\"threads\":[");

        boolean first = true;
        for (var state : states) {
            if (!first) sb.append(",");
            first = false;

            sb.append("{");
            sb.append("\"threadId\":").append(state.threadId()).append(",");
            sb.append("\"threadName\":\"").append(escapeJson(state.threadName())).append("\",");
            if (state.carrierThread() != null) {
                sb.append("\"carrierThread\":").append(state.carrierThread()).append(",");
            }
            sb.append("\"state\":\"").append(state.state().name()).append("\",");
            sb.append("\"isPinned\":").append(state.isPinned()).append(",");
            sb.append("\"startTime\":\"").append(state.startTime()).append("\"");
            if (state.endTime() != null) {
                sb.append(",\"endTime\":\"").append(state.endTime()).append("\"");
            }
            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * Escapes special characters for JSON.
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
