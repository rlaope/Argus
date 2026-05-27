package io.argus.aggregator.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal HTTP client used by the console proxy endpoints to forward
 * requests to an individual pod's argus-server. Reuses one {@link HttpClient}
 * instance per aggregator process — argus-server endpoints respond in ms.
 *
 * <p>Timeouts:
 * <ul>
 *   <li>connect: 2s (pod IP either responds or it doesn't)</li>
 *   <li>request: 10s (argus diagnostic commands like {@code threaddump} can
 *       take a few seconds on large heaps; longer than that is unhealthy)</li>
 * </ul>
 *
 * <p>This forwarder is intentionally tiny: it forwards a single HTTP GET and
 * returns the body as a string with the upstream status code. JSON parsing
 * stays in the caller so unmodified pod payloads pass through verbatim.
 */
public class PodHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;

    public PodHttpClient() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    PodHttpClient(HttpClient client) {
        this.client = client;
    }

    /**
     * Performs a GET against {@code baseUrl + path} and returns the response.
     * The caller decides how to translate status codes.
     *
     * @param baseUrl pod's {@code http://host:port} base
     * @param path    request path; must begin with {@code /}
     * @throws ProxyException on connect/timeout/IO failure — caller maps to 502
     */
    public Response get(String baseUrl, String path) throws ProxyException {
        try {
            URI uri = URI.create(baseUrl + path);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String ct = resp.headers().firstValue("content-type").orElse("application/json");
            return new Response(resp.statusCode(), resp.body(), ct);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxyException("interrupted", e);
        } catch (IllegalArgumentException e) {
            // Malformed URI from baseUrl+path — surface as 502 like any
            // other upstream failure so the proxy contract stays uniform.
            throw new ProxyException("bad URI: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProxyException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Performs a POST against {@code baseUrl + path} with the given body and
     * returns the response. Uses the same 2s connect / 10s request timeouts as
     * {@link #get}.
     *
     * @param baseUrl     pod's {@code http://host:port} base
     * @param path        request path (with optional querystring); must begin with {@code /}
     * @param body        request body; may be empty but not null
     * @param contentType value for the {@code Content-Type} header
     * @throws ProxyException on connect/timeout/IO failure — caller maps to 502
     */
    public Response post(String baseUrl, String path, String body, String contentType)
            throws ProxyException {
        try {
            URI uri = URI.create(baseUrl + path);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String ct = resp.headers().firstValue("content-type").orElse("application/json");
            return new Response(resp.statusCode(), resp.body(), ct);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxyException("interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new ProxyException("bad URI: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProxyException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** Upstream HTTP response captured as raw body + status + content-type. */
    public record Response(int status, String body, String contentType) {}

    /** Wraps a connect / IO / timeout failure on the pod side. */
    public static final class ProxyException extends Exception {
        public ProxyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
