package io.argus.server.websocket;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.VirtualThreadEvent;
import io.argus.server.metrics.ServerMetrics;
import io.argus.server.serialization.EventJsonSerializer;
import io.argus.server.state.ActiveThreadsRegistry;
import io.argus.server.state.RecentEventsBuffer;

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
    private final EventJsonSerializer serializer;
    private final ScheduledExecutorService scheduler;

    /**
     * Creates an event broadcaster.
     *
     * @param eventBuffer   the ring buffer to drain events from
     * @param clients       the channel group of connected WebSocket clients
     * @param metrics       the server metrics tracker
     * @param activeThreads the active threads registry
     * @param recentEvents  the recent events buffer
     * @param serializer    the event JSON serializer
     */
    public EventBroadcaster(
            RingBuffer<VirtualThreadEvent> eventBuffer,
            ChannelGroup clients,
            ServerMetrics metrics,
            ActiveThreadsRegistry activeThreads,
            RecentEventsBuffer recentEvents,
            EventJsonSerializer serializer) {
        this.eventBuffer = eventBuffer;
        this.clients = clients;
        this.metrics = metrics;
        this.activeThreads = activeThreads;
        this.recentEvents = recentEvents;
        this.serializer = serializer;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("argus-event-broadcaster").daemon(true).unstarted(r)
        );
    }

    /**
     * Starts the event broadcasting scheduler.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::drainAndBroadcast,
                BROADCAST_INTERVAL_MS, BROADCAST_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the event broadcasting scheduler.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
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
            // Update metrics
            metrics.incrementTotal();
            switch (event.eventType()) {
                case VIRTUAL_THREAD_START -> {
                    metrics.incrementStart();
                    activeThreads.register(event.threadId(), event);
                }
                case VIRTUAL_THREAD_END -> {
                    metrics.incrementEnd();
                    activeThreads.unregister(event.threadId());
                }
                case VIRTUAL_THREAD_PINNED -> metrics.incrementPinned();
                case VIRTUAL_THREAD_SUBMIT_FAILED -> metrics.incrementSubmitFailed();
            }

            // Serialize event
            String json = serializer.serialize(event);

            // Store in recent events
            recentEvents.add(json);

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
}
