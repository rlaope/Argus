package io.argus.instrument;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The agent-side end of the loopback event channel back to the CLI.
 *
 * <p>On {@link #open()} it connects to {@code 127.0.0.1:<port>}, writes the
 * one-time {@code nonce} line as a handshake (the CLI refuses any connection
 * whose first line is not the nonce it generated, so a stray process cannot
 * impersonate the agent), then spins up a daemon reader thread for control
 * lines. A {@code "STOP"} line or EOF fires the registered {@link #setOnStop}
 * callback exactly once so the engine can detach cleanly.
 *
 * <p>Why every emit path swallows {@link IOException}: {@link #emit} runs on the
 * <em>application's</em> threads (the advice calls into it). Throwing there would
 * propagate into the instrumented method and could crash or corrupt the target
 * application — strictly forbidden. The only place an exception escapes is
 * {@link #open()}, which runs on the attach thread; the caller aborts the attach
 * if the socket cannot be established.
 *
 * <p>Backpressure: when {@code maxEventsPerSecond > 0}, events beyond that rate
 * within a one-second sliding window are silently dropped rather than buffered,
 * so a hot method can never block or OOM the application.
 */
public final class EventSink {

    private static final Logger LOG = System.getLogger("io.argus.instrument");

    private final int port;
    private final String nonce;
    private final int maxEventsPerSecond;

    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean stopFired = new AtomicBoolean(false);

    private volatile Socket socket;
    private volatile Writer writer;
    private volatile Runnable onStop;

    // Rate-limit window state, guarded by writeLock.
    private long windowStartMs;
    private int windowCount;

    public EventSink(int port, String nonce) {
        this(port, nonce, 0);
    }

    public EventSink(int port, String nonce, int maxEventsPerSecond) {
        this.port = port;
        this.nonce = nonce == null ? "" : nonce;
        this.maxEventsPerSecond = Math.max(0, maxEventsPerSecond);
    }

    /** Registers the callback fired once on STOP / EOF from the CLI. */
    public void setOnStop(Runnable r) {
        this.onStop = r;
    }

    /**
     * Connects, performs the nonce handshake, and starts the control reader.
     *
     * @throws IOException if the socket cannot be opened or the handshake cannot
     *         be written — the caller aborts the attach in that case.
     */
    public void open() throws IOException {
        Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port);
        s.setTcpNoDelay(true);
        OutputStream out = s.getOutputStream();
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        // Handshake first, before anything else can be written.
        w.write(nonce);
        w.write('\n');
        w.flush();

        this.socket = s;
        this.writer = w;
        this.windowStartMs = System.currentTimeMillis();
        this.windowCount = 0;

        Thread t = new Thread(this::readControlLoop, "argus-instrument-sink-reader");
        t.setDaemon(true);
        t.start();
    }

    private void readControlLoop() {
        Socket s = this.socket;
        if (s == null) {
            return;
        }
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ("STOP".equals(line.trim())) {
                    fireOnStop();
                    return;
                }
            }
            // readLine() returned null → EOF: CLI hung up.
            fireOnStop();
        } catch (IOException e) {
            // Socket closed under us (e.g. local close()) counts as a stop signal.
            fireOnStop();
        }
    }

    private void fireOnStop() {
        if (stopFired.compareAndSet(false, true)) {
            Runnable r = this.onStop;
            if (r != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "onStop callback threw", t);
                }
            }
        }
    }

    /**
     * Serialises and writes one event followed by a newline, flushed.
     *
     * <p>Thread-safe and non-throwing: I/O failures are logged and dropped so
     * the calling application thread is never disturbed. Honours the
     * per-second rate cap.
     */
    public void emit(CaptureEvent e) {
        if (e == null || closed.get()) {
            return;
        }
        Writer w = this.writer;
        if (w == null) {
            return;
        }
        synchronized (writeLock) {
            if (closed.get()) {
                return;
            }
            // Lifecycle NOTICE events (e.g. "detached: timeout") bypass the rate
            // limiter: dropping the final detach reason because a hot method
            // saturated the per-second window would leave the operator blind to
            // why instrumentation stopped. Control events are rare by nature.
            if (e.kind() != CaptureEvent.Kind.NOTICE && isRateLimited()) {
                return;
            }
            try {
                w.write(e.toJson());
                w.write('\n');
                w.flush();
            } catch (IOException ex) {
                LOG.log(Level.DEBUG, "event emit failed; dropping", ex);
            }
        }
    }

    /** Caller must hold {@code writeLock}. Returns true if this event should be dropped. */
    private boolean isRateLimited() {
        if (maxEventsPerSecond <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - windowStartMs >= 1000L) {
            windowStartMs = now;
            windowCount = 0;
        }
        if (windowCount >= maxEventsPerSecond) {
            return true;
        }
        windowCount++;
        return false;
    }

    /** Best-effort, idempotent close of the reader, writer and socket. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (writeLock) {
            Writer w = this.writer;
            if (w != null) {
                try {
                    w.flush();
                } catch (IOException ignore) {
                    // best effort
                }
                try {
                    w.close();
                } catch (IOException ignore) {
                    // best effort
                }
            }
        }
        Socket s = this.socket;
        if (s != null) {
            try {
                // Closing the socket is what actually unblocks the reader thread's
                // blocking readLine() (it throws SocketException, caught in
                // readControlLoop). A Thread.interrupt() would be a no-op on a
                // blocking socket read, so we deliberately rely on close() alone.
                s.close();
            } catch (IOException ignore) {
                // best effort
            }
        }
    }
}
