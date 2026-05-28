package io.argus.instrument;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The dynamic-attach entry point. The CLI hands this class an
 * {@link AgentOptions#encode() encoded options string} via
 * {@code VirtualMachine.loadAgent(jar, options)}; {@link #agentmain} parses it,
 * opens the event channel, and starts the {@link InstrumentEngine}.
 *
 * <p>The overriding rule here is <b>never destabilise the target JVM</b>. Every
 * branch that could fail — bad options, a refused target, an unreachable CLI
 * socket — is contained: we either report the failure back over a short-lived
 * socket as a NOTICE and return, or, if we cannot even parse far enough to find
 * the port, we log via {@link System.Logger} and return silently. The target's
 * own code is never touched on the failure paths.
 */
public final class AgentMain {

    private static final Logger LOG = System.getLogger("io.argus.instrument");

    private AgentMain() {
    }

    /**
     * Dynamic-attach entry point invoked by {@code VirtualMachine.loadAgent}.
     *
     * <p>There is intentionally no {@code premain}: this agent is only ever loaded
     * on demand against an already-running JVM (the manifest declares
     * {@code Agent-Class} but not {@code Premain-Class}). Static {@code -javaagent}
     * startup has no port/nonce to connect back to and is not a supported mode.
     */
    public static void agentmain(String args, Instrumentation inst) {
        AgentOptions options;
        try {
            options = AgentOptions.parse(args);
        } catch (Throwable parseError) {
            reportEarlyFailure(args, parseError);
            return;
        }

        EventSink sink = new EventSink(options.port(), options.nonce(), options.caps().maxEventsPerSecond());
        try {
            sink.open();
        } catch (IOException ioe) {
            // CLI will observe the missing connection and report it; nothing more we can do.
            LOG.log(Level.WARNING, "could not open event channel to CLI; aborting attach", ioe);
            return;
        }

        InstrumentEngine engine = new InstrumentEngine(options, inst, sink);
        sink.setOnStop(() -> engine.stop("stop requested"));

        try {
            engine.start();
        } catch (InstrumentationRefusedException refused) {
            safeNotice(sink, "refused: " + refused.getMessage());
            sink.close();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "instrumentation start failed; detaching", t);
            safeNotice(sink, "error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            // Reset/teardown via stop so any partial transform is undone.
            engine.stop("start error");
        }
    }

    private static void safeNotice(EventSink sink, String message) {
        try {
            sink.emit(CaptureEvent.notice(System.currentTimeMillis(), message));
        } catch (Throwable ignore) {
            // best effort
        }
    }

    /**
     * Best-effort attempt to tell the CLI why the attach aborted before a sink
     * could be constructed. Tries to recover the port and nonce from the raw
     * options; if even that is impossible, falls back to a log line.
     */
    private static void reportEarlyFailure(String rawArgs, Throwable cause) {
        Integer port = extract(rawArgs, "port") == null
                ? null : tryParseInt(extract(rawArgs, "port"));
        String nonce = extract(rawArgs, "nonce");
        if (port == null) {
            LOG.log(Level.WARNING, "unparseable agent options; aborting attach", cause);
            return;
        }
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            if (nonce != null) {
                w.write(nonce);
                w.write('\n');
            }
            w.write(CaptureEvent.notice(System.currentTimeMillis(),
                    "refused: " + cause.getMessage()).toJson());
            w.write('\n');
            w.flush();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "could not report early attach failure to CLI", cause);
        }
    }

    /** Tolerant single-key extraction from the {@code key=value;...} wire string. */
    private static String extract(String raw, String key) {
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq).trim())) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static Integer tryParseInt(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < 1 || v > 65535) {
                return null;
            }
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
