package io.argus.operator.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin REST client for argus-aggregator's /fleet/targets endpoints.
 * Contract is defined in docs/aggregator-api.md.
 */
public class AggregatorClient {

    private static final Logger LOG = LoggerFactory.getLogger(AggregatorClient.class);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public AggregatorClient(String baseUrl) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public AggregatorClient(String baseUrl, HttpClient client) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.http = client;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("aggregator baseUrl is required");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * POST /fleet/targets — idempotent register/update. Returns true on 2xx.
     */
    public boolean registerTarget(String podId, String namespace, String podName,
                                  String deployment, String host, int port) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("podId", podId);
        body.put("namespace", namespace);
        body.put("podName", podName);
        body.put("deployment", deployment == null ? "" : deployment);
        body.put("host", host);
        body.put("port", port);

        byte[] json = mapper.writeValueAsBytes(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/fleet/targets"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code >= 200 && code < 300) {
            LOG.info("aggregator registerTarget OK podId={} status={}", podId, code);
            return true;
        }
        LOG.warn("aggregator registerTarget FAILED podId={} status={} body={}", podId, code, resp.body());
        return false;
    }

    /**
     * DELETE /fleet/targets/{podId} — idempotent. Returns true on 2xx (incl. 204).
     */
    public boolean deleteTarget(String podId) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(podId, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/fleet/targets/" + encoded))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code >= 200 && code < 300) {
            LOG.info("aggregator deleteTarget OK podId={} status={}", podId, code);
            return true;
        }
        LOG.warn("aggregator deleteTarget FAILED podId={} status={} body={}", podId, code, resp.body());
        return false;
    }

    public String baseUrl() {
        return baseUrl;
    }
}
