package io.argus.aggregator.profile;

/**
 * Maps Argus async-profiler event types to the profile-type names Pyroscope's
 * ingest API expects in the {@code name} label set (the part after the
 * {@code service_name}, e.g. {@code my-svc.process_cpu{...}}).
 *
 * <p>The mapping follows Pyroscope/Grafana's documented application-name suffix
 * convention for the folded/collapsed ingest format:
 * <ul>
 *   <li>{@code cpu}   → {@code process_cpu}</li>
 *   <li>{@code alloc} → {@code memory:alloc_space:bytes}</li>
 *   <li>{@code lock}  → {@code mutex:contentions:count}</li>
 *   <li>{@code wall}  → {@code wall}</li>
 * </ul>
 *
 * <p>Unknown event types pass through verbatim so a future async-profiler event
 * can still be pushed (Pyroscope tolerates arbitrary suffixes); the four mapped
 * types above are the ones this project captures.
 */
public final class PyroscopeProfileType {

    private PyroscopeProfileType() {}

    /**
     * Returns the Pyroscope profile-type suffix for an Argus {@code eventType}.
     *
     * @param eventType async-profiler event ("cpu", "alloc", "lock", "wall", …)
     * @return the Pyroscope profile-type name; the raw event type if unmapped
     */
    public static String forEvent(String eventType) {
        if (eventType == null) {
            return "";
        }
        switch (eventType) {
            case "cpu":   return "process_cpu";
            case "alloc": return "memory:alloc_space:bytes";
            case "lock":  return "mutex:contentions:count";
            case "wall":  return "wall";
            default:      return eventType;
        }
    }
}
