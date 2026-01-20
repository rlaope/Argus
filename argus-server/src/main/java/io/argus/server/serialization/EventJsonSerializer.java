package io.argus.server.serialization;

import io.argus.core.event.EventType;
import io.argus.core.event.VirtualThreadEvent;

/**
 * Serializes VirtualThreadEvent objects to JSON format.
 *
 * <p>This class handles the conversion of event data to JSON strings
 * suitable for WebSocket transmission to dashboard clients.
 */
public final class EventJsonSerializer {

    /**
     * Serializes a VirtualThreadEvent to a JSON string.
     *
     * @param event the event to serialize
     * @return JSON representation of the event
     */
    public String serialize(VirtualThreadEvent event) {
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

    /**
     * Converts EventType enum to short display name.
     *
     * @param eventType the event type
     * @return short name for display (START, END, PINNED, SUBMIT_FAILED)
     */
    public String getShortTypeName(EventType eventType) {
        return switch (eventType) {
            case VIRTUAL_THREAD_START -> "START";
            case VIRTUAL_THREAD_END -> "END";
            case VIRTUAL_THREAD_PINNED -> "PINNED";
            case VIRTUAL_THREAD_SUBMIT_FAILED -> "SUBMIT_FAILED";
        };
    }

    /**
     * Escapes special characters for JSON string values.
     *
     * @param value the string to escape
     * @return escaped string safe for JSON
     */
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
}
