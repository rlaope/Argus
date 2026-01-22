package io.argus.server.websocket;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.VirtualThreadEvent;
import io.argus.server.analysis.CarrierThreadAnalyzer;
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

    private final RingBuffer<VirtualThreadEvent> eventBuffer;
    private final ChannelGroup clients;
    private final ServerMetrics metrics;
    private final ActiveThreadsRegistry activeThreads;
    private final RecentEventsBuffer recentEvents;
    private final ThreadEventsBuffer threadEvents;
    private final PinningAnalyzer pinningAnalyzer;
    private final CarrierThreadAnalyzer carrierAnalyzer;
    private final ThreadStateManager threadStateManager;
    private final EventJsonSerializer serializer;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService stateScheduler;

    /**
     * Creates an event broadcaster.
     *
     * @param eventBuffer        the ring buffer to drain events from
     * @param clients            the channel group of connected WebSocket clients
     * @param metrics            the server metrics tracker
     * @param activeThreads      the active threads registry
     * @param recentEvents       the recent events buffer
     * @param threadEvents       the per-thread events buffer
     * @param pinningAnalyzer    the pinning analyzer for hotspot detection
     * @param carrierAnalyzer    the carrier thread analyzer
     * @param threadStateManager the thread state manager for real-time state tracking
     * @param serializer         the event JSON serializer
     */
    public EventBroadcaster(
            RingBuffer<VirtualThreadEvent> eventBuffer,
            ChannelGroup clients,
            ServerMetrics metrics,
            ActiveThreadsRegistry activeThreads,
            RecentEventsBuffer recentEvents,
            ThreadEventsBuffer threadEvents,
            PinningAnalyzer pinningAnalyzer,
            CarrierThreadAnalyzer carrierAnalyzer,
            ThreadStateManager threadStateManager,
            EventJsonSerializer serializer) {
        this.eventBuffer = eventBuffer;
        this.clients = clients;
        this.metrics = metrics;
        this.activeThreads = activeThreads;
        this.recentEvents = recentEvents;
        this.threadEvents = threadEvents;
        this.pinningAnalyzer = pinningAnalyzer;
        this.carrierAnalyzer = carrierAnalyzer;
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
        if (eventBuffer == null) {
            return;
        }

        eventBuffer.drain(event -> {
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
