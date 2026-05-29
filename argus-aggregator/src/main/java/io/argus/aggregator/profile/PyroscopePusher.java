package io.argus.aggregator.profile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optionally mirrors a captured profile window to a Pyroscope ingest endpoint,
 * reusing the same {@code (podId, service, eventType, window)} keys as
 * {@link ProfileStore}.
 *
 * <p>This wraps {@link ProfileStore#append}: callers route captures through
 * {@link #appendAndPush} so the local disk store stays the source of truth and
 * the push is a best-effort side channel. The push is gated by
 * {@link PyroscopePushConfig#enabled()} — when no endpoint is configured the
 * local-store behaviour is unchanged and {@link #send} is never called.
 *
 * <h2>Request shape</h2>
 * Pyroscope's folded ingest API:
 * <pre>
 *   POST &lt;endpoint&gt;/ingest?name=&lt;labelset&gt;&amp;from=&lt;ms&gt;&amp;until=&lt;ms&gt;&amp;format=folded
 *   Content-Type: text/plain
 *   body: one "stack count" line per collapsed stack
 * </pre>
 * where {@code labelset} is {@code service.&lt;type&gt;{pod="…",region="…"}} —
 * the Pyroscope application-name + label-pair convention. {@code service_name}
 * comes from the {@link ProfileStore} service key, {@code <type>} from
 * {@link PyroscopeProfileType}, {@code pod} from the pod key, and {@code region}
 * from {@link PyroscopePushConfig#region()} when set.
 *
 * <h2>Failure handling</h2>
 * A refused connection, 5xx, timeout, or any thrown error is caught, counted,
 * and logged at WARNING; it never propagates, so the capture/store loop survives
 * a broken or absent Pyroscope. The local store write always happens first.
 */
public final class PyroscopePusher {

    private static final System.Logger LOG = System.getLogger(PyroscopePusher.class.getName());

    private final ProfileStore store;
    private final PyroscopePushConfig config;
    private final PyroscopeTransport transport;

    private final AtomicLong pushAttempts = new AtomicLong(0);
    private final AtomicLong pushSuccesses = new AtomicLong(0);
    private final AtomicLong pushFailures = new AtomicLong(0);

    public PyroscopePusher(ProfileStore store, PyroscopePushConfig config, PyroscopeTransport transport) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.store = store;
        this.config = config;
        this.transport = transport;
    }

    /** Convenience factory wiring the default JDK-HttpClient transport. */
    public static PyroscopePusher create(ProfileStore store, PyroscopePushConfig config) {
        return new PyroscopePusher(store, config, new PyroscopeTransport.HttpClientTransport());
    }

    public boolean pushEnabled() {
        return config.enabled();
    }

    public long pushAttempts() { return pushAttempts.get(); }
    public long pushSuccesses() { return pushSuccesses.get(); }
    public long pushFailures() { return pushFailures.get(); }

    /**
     * Appends one capture cycle to the local {@link ProfileStore} and, when push
     * is enabled, mirrors it to Pyroscope. The local append always runs; the push
     * is best-effort and never throws back to the caller.
     *
     * @see ProfileStore#append for the parameter contract
     */
    public void appendAndPush(String podId, String service, String eventType,
                              long timestampMillis, Map<String, Long> collapsedCounts) {
        // Local store is the source of truth; write it first and unconditionally.
        store.append(podId, service, eventType, timestampMillis, collapsedCounts);

        if (!config.enabled()) {
            return; // endpoint unset → no push attempted
        }
        if (collapsedCounts == null || collapsedCounts.isEmpty()) {
            return;
        }
        pushOneWindow(podId, service, eventType, timestampMillis, collapsedCounts);
    }

    private void pushOneWindow(String podId, String service, String eventType,
                               long timestampMillis, Map<String, Long> collapsedCounts) {
        pushAttempts.incrementAndGet();
        try {
            String url = buildUrl(podId, service, eventType, timestampMillis);
            String body = foldedBody(collapsedCounts);
            int status = transport.send(new PyroscopeTransport.PushRequest(url, body));
            if (status >= 200 && status < 300) {
                pushSuccesses.incrementAndGet();
            } else {
                pushFailures.incrementAndGet();
                LOG.log(System.Logger.Level.WARNING,
                        () -> "pyroscope push got HTTP " + status + "; profile retained in local store");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pushFailures.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "pyroscope push interrupted; profile retained in local store");
        } catch (Throwable t) {
            // Never let a transport failure crash the capture/store loop.
            pushFailures.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING,
                    () -> "pyroscope push failed: " + t.getMessage() + "; profile retained in local store");
        }
    }

    /**
     * Builds the full ingest URL for one window. The window {@code [from, until)}
     * is derived from the store's configured window so {@code from}/{@code until}
     * align with the stored segment, not the raw sample timestamp.
     */
    String buildUrl(String podId, String service, String eventType, long timestampMillis) {
        long from = store.config().windowStartFor(timestampMillis);
        long until = from + store.config().windowMillis();
        String name = labelSet(podId, service, eventType);
        StringBuilder sb = new StringBuilder(config.endpoint().length() + name.length() + 64);
        sb.append(stripTrailingSlash(config.endpoint())).append("/ingest")
          .append("?name=").append(URLEncoder.encode(name, StandardCharsets.UTF_8))
          .append("&from=").append(from)
          .append("&until=").append(until)
          .append("&format=folded");
        return sb.toString();
    }

    /**
     * Builds the Pyroscope application name + label set, e.g.
     * {@code my-svc.process_cpu{pod="ns/pod-a",region="us-east"}}. A blank service
     * falls back to {@code argus} so the application name is never empty.
     */
    String labelSet(String podId, String service, String eventType) {
        String svc = (service == null || service.isBlank()) ? "argus" : service;
        String type = PyroscopeProfileType.forEvent(eventType);
        StringBuilder sb = new StringBuilder(64);
        sb.append(svc).append('.').append(type)
          .append("{service_name=").append(quote(svc))
          .append(",pod=").append(quote(podId == null ? "" : podId));
        if (config.region() != null) {
            sb.append(",region=").append(quote(config.region()));
        }
        sb.append('}');
        return sb.toString();
    }

    /** Renders the collapsed-stack map as Pyroscope folded body: {@code "stack count"} per line. */
    static String foldedBody(Map<String, Long> collapsedCounts) {
        StringBuilder sb = new StringBuilder(256);
        for (Map.Entry<String, Long> e : collapsedCounts.entrySet()) {
            long count = e.getValue() == null ? 0L : e.getValue();
            if (count <= 0L) {
                continue;
            }
            sb.append(e.getKey()).append(' ').append(count).append('\n');
        }
        return sb.toString();
    }

    private static String quote(String v) {
        return '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
