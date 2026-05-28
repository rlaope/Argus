package io.argus.instrument;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback round-trip tests for {@link EventSink}: a real {@link ServerSocket}
 * on 127.0.0.1 plays the CLI side and asserts the handshake + JSON framing.
 */
class EventSinkTest {

    private static final long TIMEOUT_MS = 5_000L;

    @Test
    void open_writesNonceHandshakeFirst() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout((int) TIMEOUT_MS);
            int port = server.getLocalPort();
            EventSink sink = new EventSink(port, "nonce-abc123");
            try {
                sink.open();
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout((int) TIMEOUT_MS);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                    String handshake = in.readLine();
                    assertEquals("nonce-abc123", handshake, "first line must be the nonce");
                }
            } finally {
                sink.close();
            }
        }
    }

    @Test
    void emit_streamsJsonLinesAfterHandshake() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout((int) TIMEOUT_MS);
            int port = server.getLocalPort();
            EventSink sink = new EventSink(port, "the-nonce");
            Socket accepted = null;
            try {
                sink.open();
                accepted = server.accept();
                accepted.setSoTimeout((int) TIMEOUT_MS);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));

                assertEquals("the-nonce", in.readLine());

                sink.emit(CaptureEvent.enter(1L, "main", "com.acme.Foo", "bar", 0,
                        Collections.singletonList("\"x\"")));
                sink.emit(CaptureEvent.exit(2L, "main", "com.acme.Foo", "bar", 0, "\"ok\"", 1234L));

                String line1 = in.readLine();
                String line2 = in.readLine();
                assertNotNull(line1, "expected the ENTER line");
                assertNotNull(line2, "expected the EXIT line");
                assertTrue(line1.contains("\"type\":\"ENTER\""), line1);
                assertTrue(line1.contains("\"clazz\":\"com.acme.Foo\""), line1);
                assertTrue(line2.contains("\"type\":\"EXIT\""), line2);
                assertTrue(line2.contains("\"wallNanos\":1234"), line2);
            } finally {
                sink.close();
                if (accepted != null) {
                    accepted.close();
                }
            }
        }
    }

    @Test
    void stopLine_firesOnStopCallback() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout((int) TIMEOUT_MS);
            int port = server.getLocalPort();
            EventSink sink = new EventSink(port, "n");
            CountDownLatch stopped = new CountDownLatch(1);
            sink.setOnStop(stopped::countDown);
            Socket accepted = null;
            try {
                sink.open();
                accepted = server.accept();
                accepted.setSoTimeout((int) TIMEOUT_MS);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("n", in.readLine());

                // CLI sends STOP -> the sink's reader thread must fire onStop exactly once.
                OutputStream out = accepted.getOutputStream();
                out.write("STOP\n".getBytes(StandardCharsets.UTF_8));
                out.flush();

                assertTrue(stopped.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                        "onStop must fire on a STOP control line");
            } finally {
                sink.close();
                if (accepted != null) {
                    accepted.close();
                }
            }
        }
    }

    @Test
    void eof_firesOnStopCallback() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout((int) TIMEOUT_MS);
            int port = server.getLocalPort();
            EventSink sink = new EventSink(port, "n");
            CountDownLatch stopped = new CountDownLatch(1);
            sink.setOnStop(stopped::countDown);
            try {
                sink.open();
                Socket accepted = server.accept();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("n", in.readLine());
                // Server hangs up -> the sink reader sees EOF and fires onStop.
                accepted.close();
                assertTrue(stopped.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                        "onStop must fire on EOF (CLI hangup)");
            } finally {
                sink.close();
            }
        }
    }

    @Test
    void emit_afterCloseIsSilentNoOp() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout((int) TIMEOUT_MS);
            int port = server.getLocalPort();
            EventSink sink = new EventSink(port, "n");
            sink.open();
            Socket accepted = server.accept();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("n", in.readLine());
            sink.close();
            // Must not throw even though the channel is closed.
            sink.emit(CaptureEvent.notice(1L, "after close"));
            accepted.close();
        }
    }

    @Test
    void rateLimit_dropsBeyondCapWithinAWindow() throws Exception {
        // Loosely assert the per-second cap: with cap=5 we burst many events and
        // expect AT MOST the cap to arrive within one window. Kept non-flaky by
        // only asserting an upper bound (no lower-bound timing dependency).
        final int cap = 5;
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout((int) TIMEOUT_MS);
            int port = server.getLocalPort();
            EventSink sink = new EventSink(port, "n", cap);
            Socket accepted = null;
            try {
                sink.open();
                accepted = server.accept();
                accepted.setSoTimeout(500);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("n", in.readLine());

                // Burst 50 ENTER events synchronously inside a single ~instant
                // window. ENTER is subject to the rate cap (NOTICE deliberately
                // bypasses it — see noticeBypassesRateCap).
                for (int i = 0; i < 50; i++) {
                    sink.emit(CaptureEvent.enter(i, "t", "C", "m", 0, java.util.Collections.emptyList()));
                }

                int received = 0;
                try {
                    while (in.readLine() != null) {
                        received++;
                        if (received > cap) {
                            break; // already exceeded the bound; the assert below will fail
                        }
                    }
                } catch (IOException readTimeout) {
                    // expected once the burst is drained
                }
                assertTrue(received <= cap,
                        "received " + received + " events but cap was " + cap);
            } finally {
                sink.close();
                if (accepted != null) {
                    accepted.close();
                }
            }
        }
    }

    @Test
    void noticeBypassesRateCap() throws Exception {
        // Lifecycle NOTICE events must NOT be dropped by the rate limiter: even
        // after the per-second cap is saturated by ENTER events, the final
        // "detached" NOTICE has to reach the CLI so the operator learns why
        // instrumentation stopped.
        final int cap = 3;
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout((int) TIMEOUT_MS);
            int port = server.getLocalPort();
            EventSink sink = new EventSink(port, "n", cap);
            Socket accepted = null;
            try {
                sink.open();
                accepted = server.accept();
                accepted.setSoTimeout(1000);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("n", in.readLine());

                // Saturate the window with ENTER events well beyond the cap.
                for (int i = 0; i < 50; i++) {
                    sink.emit(CaptureEvent.enter(i, "t", "C", "m", 0, java.util.Collections.emptyList()));
                }
                // Then emit a NOTICE — it must get through despite the saturated cap.
                sink.emit(CaptureEvent.notice(System.currentTimeMillis(), "detached: test"));

                boolean sawNotice = false;
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.contains("\"type\":\"NOTICE\"")) {
                            sawNotice = true;
                            break;
                        }
                    }
                } catch (IOException readTimeout) {
                    // drained
                }
                assertTrue(sawNotice, "a NOTICE must bypass the rate cap and reach the CLI");
            } finally {
                sink.close();
                if (accepted != null) {
                    accepted.close();
                }
            }
        }
    }
}
