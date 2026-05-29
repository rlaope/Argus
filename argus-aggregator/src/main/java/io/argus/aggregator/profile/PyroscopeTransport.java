package io.argus.aggregator.profile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The injectable seam {@link PyroscopePusher} uses to send a folded-stacks ingest
 * request. Abstracting the send keeps the pusher testable without a live
 * Pyroscope server: tests inject a fake transport that records the request, or
 * one that throws to exercise failure degradation.
 *
 * <p>A {@link PushRequest} carries the fully-built ingest URL (including the
 * {@code name}/{@code from}/{@code until}/{@code format} query) and the folded
 * body. Implementations return the HTTP status code; a non-2xx status or any
 * thrown {@link IOException} is treated by the pusher as a push failure.
 */
public interface PyroscopeTransport {

    /**
     * Sends one ingest request.
     *
     * @return the HTTP status code returned by the ingest endpoint
     * @throws IOException          on connect/timeout/IO failure (treated as a push failure)
     * @throws InterruptedException if the calling thread is interrupted while sending
     */
    int send(PushRequest request) throws IOException, InterruptedException;

    /** A fully-built Pyroscope ingest request: target URL plus folded body. */
    record PushRequest(String url, String foldedBody) {}

    /**
     * The default JDK {@link HttpClient}-backed transport. POSTs the folded body
     * as {@code text/plain} to the ingest URL with a bounded connect/request
     * timeout. No heavy dependency — {@code java.net.http} only.
     */
    final class HttpClientTransport implements PyroscopeTransport {

        private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

        private final HttpClient client;

        public HttpClientTransport() {
            this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        }

        HttpClientTransport(HttpClient client) {
            this.client = client;
        }

        @Override
        public int send(PushRequest request) throws IOException, InterruptedException {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(request.url()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(request.foldedBody(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode();
        }
    }
}
