package io.argus.cli.instrument;

import io.argus.cli.command.CommandExitException;
import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.render.AnsiStyle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Attach-and-stream engine for {@code argus instrument}.
 *
 * <p>This class is deliberately built on plain JDK + sockets only. It has
 * <strong>no</strong> compile dependency on the {@code argus-instrument} agent
 * module: it dynamically attaches a prebuilt agent JAR to a target PID and then
 * talks to the agent over a loopback socket using a fixed string/JSON protocol.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Bind an ephemeral {@code 127.0.0.1} server socket (the agent connects back).</li>
 *   <li>Generate a 32-hex-char nonce so a forged connection can be rejected.</li>
 *   <li>{@code VirtualMachine.attach(pid)} → {@code loadAgent(jar, options)} → {@code detach()}.</li>
 *   <li>Accept the agent connection, verify the first line equals the nonce.</li>
 *   <li>Stream newline-delimited JSON event lines until the socket closes,
 *       rendering each per {@code --format}.</li>
 * </ol>
 */
public final class InstrumentSession {

    /** Exit codes used by the instrument command family. */
    public static final int EXIT_ATTACH = 4;
    public static final int EXIT_HANDSHAKE = 4;

    private static final int ACCEPT_TIMEOUT_MS = 15_000;
    private static final int MAX_EVENTS_PER_SECOND = 500;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String mode;
    private final String spec;
    private final long pid;
    private final String agentJarPath;
    private final InstrumentOptions options;
    private final boolean jsonOutput;
    private final boolean useColor;

    public InstrumentSession(String mode, String spec, long pid, String agentJarPath,
                             InstrumentOptions options, CliConfig config) {
        this.mode = mode;
        this.spec = spec;
        this.pid = pid;
        this.agentJarPath = agentJarPath;
        this.options = options;
        this.jsonOutput = "json".equals(options.format());
        this.useColor = config.color();
    }

    /**
     * Generates a 32-hex-char random nonce from 16 random bytes.
     */
    static String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Builds the flat {@code key=value;...} wire string handed to the agent's
     * {@code AgentOptions}. Values never contain {@code ;} or {@code =}.
     *
     * <p>Factored out as a package-private static method so tests can assert on
     * the exact wire format without a live target.
     */
    public static String buildOptionsWire(String mode, String spec, int port, String nonce,
                                          InstrumentOptions opts) {
        return "mode=" + mode
                + ";spec=" + spec
                + ";port=" + port
                + ";nonce=" + nonce
                + ";enabled=true"
                + ";maxHits=" + opts.maxHits()
                + ";timeoutMs=" + opts.timeoutMs()
                + ";maxValueLen=" + opts.maxValueLen()
                + ";maxArgs=" + opts.maxArgs()
                + ";maxDepth=" + opts.maxDepth()
                + ";maxEventsPerSecond=" + MAX_EVENTS_PER_SECOND;
    }

    /**
     * Runs the full attach + stream lifecycle, blocking until the agent closes
     * the socket (or the user interrupts with Ctrl-C). Throws
     * {@link CommandExitException} with the documented exit code on failure.
     */
    public void run(Messages messages) {
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
            server.setSoTimeout(ACCEPT_TIMEOUT_MS);
            int port = server.getLocalPort();

            String nonce = newNonce();
            String optionsWire = buildOptionsWire(mode, spec, port, nonce, options);

            attach(optionsWire, messages);

            try (Socket socket = server.accept()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream agentOut = socket.getOutputStream();

                String first = reader.readLine();
                if (first == null || !nonce.equals(first.trim())) {
                    System.err.println(messages.get("cmd.instrument.error.handshake"));
                    throw new CommandExitException(EXIT_HANDSHAKE);
                }

                installShutdownHook(agentOut);
                stream(reader);
            }
        } catch (CommandExitException e) {
            throw e;
        } catch (IOException e) {
            System.err.println(messages.get("cmd.instrument.error.attach", pid, String.valueOf(e.getMessage())));
            throw new CommandExitException(EXIT_ATTACH);
        } finally {
            if (server != null) {
                try { server.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void attach(String optionsWire, Messages messages) {
        com.sun.tools.attach.VirtualMachine vm;
        try {
            vm = com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
        } catch (Throwable t) {
            System.err.println(messages.get("cmd.instrument.error.attach", pid, String.valueOf(t.getMessage())));
            throw new CommandExitException(EXIT_ATTACH);
        }
        try {
            vm.loadAgent(agentJarPath, optionsWire);
        } catch (Throwable t) {
            System.err.println(messages.get("cmd.instrument.error.attach", pid, String.valueOf(t.getMessage())));
            throw new CommandExitException(EXIT_ATTACH);
        } finally {
            try { vm.detach(); } catch (Throwable ignored) {}
        }
    }

    private void installShutdownHook(OutputStream agentOut) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                agentOut.write("STOP\n".getBytes(StandardCharsets.UTF_8));
                agentOut.flush();
                // Give the agent a brief moment to emit its final NOTICE + reset.
                Thread.sleep(300);
            } catch (Exception ignored) {
            }
        }, "argus-instrument-stop"));
    }

    private void stream(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            if (jsonOutput) {
                System.out.println(line);
            } else {
                renderPretty(line);
            }
        }
    }

    private void renderPretty(String line) {
        InstrumentEvent ev = InstrumentEvent.parse(line);
        String type = ev.str("type");
        if (type == null) {
            return;
        }
        String indent = indent(ev.intVal("depth", 0));
        switch (type) {
            case "ENTER": {
                String args = String.join(", ", ev.strArray("args"));
                System.out.println(color(AnsiStyle.CYAN) + indent + "→ "
                        + ev.str("clazz") + "." + ev.str("method") + "(" + args + ")" + reset());
                break;
            }
            case "EXIT": {
                System.out.println(color(AnsiStyle.GREEN) + indent + "← "
                        + ev.str("clazz") + "." + ev.str("method") + " = " + nullSafe(ev.str("ret"))
                        + reset() + dim() + "  (" + ms(ev.longVal("wallNanos", 0)) + " ms)" + reset());
                break;
            }
            case "THROW": {
                System.out.println(color(AnsiStyle.RED) + indent + "✗ "
                        + ev.str("clazz") + "." + ev.str("method") + " threw " + nullSafe(ev.str("ex"))
                        + reset() + dim() + "  (" + ms(ev.longVal("wallNanos", 0)) + " ms)" + reset());
                break;
            }
            case "MONITOR": {
                System.out.println(ev.str("clazz") + "." + ev.str("method")
                        + "  count=" + ev.longVal("count", 0)
                        + " ok=" + ev.longVal("success", 0)
                        + " err=" + ev.longVal("failure", 0)
                        + " avg=" + trimNum(ev.numStr("avgMs")) + "ms"
                        + " max=" + trimNum(ev.numStr("maxMs")) + "ms");
                break;
            }
            case "NOTICE": {
                System.err.println(dim() + "[argus-instrument] " + nullSafe(ev.str("message")) + reset());
                break;
            }
            default:
                // Unknown event type — fall back to raw line so nothing is lost.
                System.out.println(line);
                break;
        }
    }

    private static String indent(int depth) {
        if (depth <= 0) return "";
        StringBuilder sb = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    private static String ms(long nanos) {
        return trimNum(String.valueOf(nanos / 1_000_000.0));
    }

    private static String trimNum(String s) {
        if (s == null) return "0";
        if (s.endsWith(".0")) return s.substring(0, s.length() - 2);
        return s;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String color(String code) {
        return AnsiStyle.style(useColor, code);
    }

    private String dim() {
        return AnsiStyle.style(useColor, AnsiStyle.DIM);
    }

    private String reset() {
        return AnsiStyle.style(useColor, AnsiStyle.RESET);
    }

    /**
     * Minimal hand-rolled JSON object parser for a single event line. The agent
     * emits flat objects with string/number/array-of-string values; this avoids
     * pulling in a JSON dependency just to read those few fields.
     */
    static final class InstrumentEvent {
        private final java.util.Map<String, String> scalars = new java.util.HashMap<>();
        private final java.util.Map<String, List<String>> arrays = new java.util.HashMap<>();

        static InstrumentEvent parse(String json) {
            InstrumentEvent ev = new InstrumentEvent();
            if (json == null) return ev;
            String s = json.trim();
            int i = 0;
            int n = s.length();
            // Skip to first '{'.
            while (i < n && s.charAt(i) != '{') i++;
            if (i < n) i++;
            while (i < n) {
                // Skip whitespace and separators.
                while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
                if (i >= n || s.charAt(i) == '}') break;
                if (s.charAt(i) != '"') break;
                StringBuilder key = new StringBuilder();
                i = readString(s, i, key);
                while (i < n && Character.isWhitespace(s.charAt(i))) i++;
                if (i >= n || s.charAt(i) != ':') break;
                i++;
                while (i < n && Character.isWhitespace(s.charAt(i))) i++;
                if (i >= n) break;
                char c = s.charAt(i);
                if (c == '"') {
                    StringBuilder val = new StringBuilder();
                    i = readString(s, i, val);
                    ev.scalars.put(key.toString(), val.toString());
                } else if (c == '[') {
                    List<String> list = new ArrayList<>();
                    i++;
                    while (i < n) {
                        while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
                        if (i >= n || s.charAt(i) == ']') { if (i < n) i++; break; }
                        if (s.charAt(i) == '"') {
                            StringBuilder item = new StringBuilder();
                            i = readString(s, i, item);
                            list.add(item.toString());
                        } else {
                            StringBuilder item = new StringBuilder();
                            i = readBareToken(s, i, item);
                            list.add(item.toString());
                        }
                    }
                    ev.arrays.put(key.toString(), list);
                } else if (c == '{') {
                    // Nested object — skip it (no event field uses one).
                    i = skipObject(s, i);
                } else {
                    StringBuilder val = new StringBuilder();
                    i = readBareToken(s, i, val);
                    ev.scalars.put(key.toString(), val.toString());
                }
            }
            return ev;
        }

        private static int readString(String s, int i, StringBuilder out) {
            int n = s.length();
            i++; // opening quote
            while (i < n) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < n) {
                    char esc = s.charAt(i + 1);
                    switch (esc) {
                        case 'n': out.append('\n'); break;
                        case 't': out.append('\t'); break;
                        case 'r': out.append('\r'); break;
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'u':
                            if (i + 5 < n) {
                                try {
                                    out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                    i += 4;
                                } catch (NumberFormatException ignored) {
                                    out.append(esc);
                                }
                            }
                            break;
                        default: out.append(esc); break;
                    }
                    i += 2;
                } else if (c == '"') {
                    return i + 1;
                } else {
                    out.append(c);
                    i++;
                }
            }
            return i;
        }

        private static int readBareToken(String s, int i, StringBuilder out) {
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) break;
                out.append(c);
                i++;
            }
            return i;
        }

        private static int skipObject(String s, int i) {
            int depth = 0;
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (c == '"') {
                    StringBuilder ignore = new StringBuilder();
                    i = readString(s, i, ignore);
                    continue;
                }
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i + 1;
                }
                i++;
            }
            return i;
        }

        String str(String key) {
            return scalars.get(key);
        }

        String numStr(String key) {
            return scalars.get(key);
        }

        int intVal(String key, int dflt) {
            String v = scalars.get(key);
            if (v == null) return dflt;
            try { return (int) Double.parseDouble(v); } catch (NumberFormatException e) { return dflt; }
        }

        long longVal(String key, long dflt) {
            String v = scalars.get(key);
            if (v == null) return dflt;
            try { return (long) Double.parseDouble(v); } catch (NumberFormatException e) { return dflt; }
        }

        List<String> strArray(String key) {
            List<String> list = arrays.get(key);
            return list == null ? new ArrayList<>() : list;
        }
    }
}
