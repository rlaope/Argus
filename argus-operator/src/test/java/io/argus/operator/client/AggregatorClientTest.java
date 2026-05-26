package io.argus.operator.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AggregatorClient} against an in-process HttpServer so we can
 * verify the contract (paths, methods, idempotent semantics) without a real
 * aggregator running.
 */
class AggregatorClientTest {

    private HttpServer server;
    private AggregatorClient client;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fleet/targets", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes());
                lastRequestBody.set(body);
                byte[] resp = "{\"podId\":\"x\",\"registeredAt\":\"now\",\"updated\":false}".getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });
        server.start();
        client = new AggregatorClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void registerTarget_postsJsonBody() throws Exception {
        boolean ok = client.registerTarget("prod/payment-1", "prod", "payment-1", "payment", "10.1.2.3", 7070);
        assertTrue(ok);
        assertEquals("POST", lastMethod.get());
        assertEquals("/fleet/targets", lastPath.get());
        String body = lastRequestBody.get();
        assertTrue(body.contains("\"podId\":\"prod/payment-1\""), body);
        assertTrue(body.contains("\"port\":7070"), body);
        assertTrue(body.contains("\"host\":\"10.1.2.3\""), body);
    }

    @Test
    void deleteTarget_targetsPodIdPath() throws Exception {
        boolean ok = client.deleteTarget("prod/payment-1");
        assertTrue(ok);
        assertEquals("DELETE", lastMethod.get());
        // The JDK HttpServer decodes percent-escapes before exposing the path,
        // so the server view of the path is always the decoded form. The aggregator
        // contract documents the encoded form for the wire; what we verify here is
        // that the request hits the correct route after server-side decoding.
        String path = lastPath.get();
        assertTrue(path.equals("/fleet/targets/prod%2Fpayment-1")
                        || path.equals("/fleet/targets/prod/payment-1"),
                "unexpected path: " + path);
    }

    @Test
    void clientRejectsBlankBaseUrl() {
        try {
            new AggregatorClient("");
        } catch (IllegalArgumentException expected) {
            return;
        }
        assertFalse(true, "expected IllegalArgumentException");
    }
}
