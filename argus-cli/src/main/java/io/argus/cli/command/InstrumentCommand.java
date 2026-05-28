package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.instrument.InstrumentOptions;
import io.argus.cli.instrument.InstrumentSession;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.core.command.CommandGroup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Dispatches the {@code argus instrument} family — opt-in live method
 * instrumentation of a running JVM.
 *
 * <ul>
 *   <li>{@code argus instrument watch <pid> com.acme.Foo#bar} — args/return/exception/timing per call.</li>
 *   <li>{@code argus instrument trace <pid> com.acme.Foo#bar} — instrumented call tree (depth).</li>
 *   <li>{@code argus instrument monitor <pid> com.acme.Foo#bar} — windowed count/success/failure/avg-RT.</li>
 * </ul>
 *
 * <p><strong>Default OFF.</strong> Every subcommand refuses to run unless the
 * caller passes {@code --enable-instrument} or sets the system property
 * {@code argus.instrument.enabled=true}. The CLI also refuses JDK-internal
 * targets and validates the {@code Class#method} spec as defense in depth (the
 * agent enforces the same rules).
 *
 * <p>This command has <strong>no</strong> compile dependency on the
 * {@code argus-instrument} agent module: it resolves a prebuilt agent JAR at
 * runtime and attaches it dynamically.
 */
public final class InstrumentCommand implements Command {

    static final String ENABLE_FLAG = "--enable-instrument";
    static final String ENABLE_PROPERTY = "argus.instrument.enabled";

    static final String DEFAULT_AGENT_JAR =
            Paths.get(System.getProperty("user.home"), ".argus", "lib", "argus-instrument.jar").toString();

    @Override
    public String name() { return "instrument"; }

    @Override
    public CommandGroup group() { return CommandGroup.THREADS; }

    @Override
    public CommandMode mode() { return CommandMode.WRITE; }

    @Override
    public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.instrument.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.out.println(messages.get("cmd.instrument.usage"));
            return;
        }
        String head = args[0];
        if ("--help".equals(head) || "-h".equals(head) || "help".equals(head)) {
            System.out.println(messages.get("cmd.instrument.usage"));
            return;
        }
        if ("watch".equals(head) || "trace".equals(head) || "monitor".equals(head)) {
            runSubcommand(head, shift(args), config, messages);
            return;
        }
        System.out.println(messages.get("cmd.instrument.usage"));
        throw new CommandExitException(2);
    }

    private void runSubcommand(String mode, String[] args, CliConfig config, Messages messages) {
        // Gate 1: opt-in. Refuse unless the flag is present or the property is set.
        // Checked first so the default-OFF guarantee never depends on later input.
        if (!isEnabled(args)) {
            System.err.println(messages.get("cmd.instrument.error.disabled"));
            throw new CommandExitException(2);
        }

        // Pure-input safety gates (no live target needed) run before PID liveness
        // so a malformed/forbidden request is rejected even against a stale PID.
        // Positional args are flag-aware: index 0 is the PID, index 1 the spec,
        // regardless of where --flags appear.
        String[] pos = positionals(args);
        String spec = pos.length >= 2 ? pos[1] : null;
        // Gate 3: spec shape — exactly one '#', dotted class, non-empty method.
        if (!isValidSpec(spec)) {
            System.err.println(messages.get("cmd.instrument.error.badspec"));
            throw new CommandExitException(2);
        }
        // Gate 2: JDK-internal / agent-internal refusal.
        String className = spec.substring(0, spec.indexOf('#'));
        if (isForbidden(className)) {
            System.err.println(messages.get("cmd.instrument.error.forbidden"));
            throw new CommandExitException(2);
        }

        // PID parsing is flag-aware (uses positionals) so `--flag <pid>` orderings
        // don't misread a flag as the PID.
        long pid = CommandUtils.parsePidOrExit(pos, messages);

        // Gate 4: resolve agent jar; refuse if missing (reached before attach).
        String agentJar = resolveAgentJar(args);
        if (!Files.isRegularFile(Path.of(agentJar))) {
            System.err.println(messages.get("cmd.instrument.error.noagent", agentJar));
            throw new CommandExitException(3);
        }

        InstrumentOptions options = parseOptions(args, mode, config);
        new InstrumentSession(mode, spec, pid, agentJar, options, config).run(messages);
    }

    /** Opt-in gate: {@code --enable-instrument} flag OR {@code argus.instrument.enabled=true}. */
    static boolean isEnabled(String[] args) {
        for (String a : args) {
            if (ENABLE_FLAG.equals(a)) return true;
        }
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"));
    }

    /**
     * Validates a {@code <dotted.Class>#<method-or-*-or-<init>>} spec: exactly one
     * {@code #}, a non-empty dotted class on the left, and a non-empty method token
     * on the right.
     */
    static boolean isValidSpec(String spec) {
        if (spec == null || spec.isEmpty()) return false;
        int hash = spec.indexOf('#');
        if (hash <= 0) return false;
        if (spec.indexOf('#', hash + 1) != -1) return false; // more than one '#'
        String clazz = spec.substring(0, hash);
        String method = spec.substring(hash + 1);
        if (method.isEmpty()) return false;
        // Class must be a dotted identifier path; reject leading/trailing dots.
        if (clazz.startsWith(".") || clazz.endsWith(".")) return false;
        for (int i = 0; i < clazz.length(); i++) {
            char c = clazz.charAt(i);
            if (c != '.' && !Character.isJavaIdentifierPart(c)) return false;
        }
        return true;
    }

    /**
     * Refuses JDK-internal, crypto, and Argus/ByteBuddy-internal packages. Kept
     * local so this command has no dependency on the agent module, but the trim +
     * lower-case normalisation deliberately mirrors the agent's
     * {@code SafetyGuard.isForbidden} so the two gates can never diverge and let a
     * case/whitespace variant through one but not the other (fail closed).
     */
    static boolean isForbidden(String className) {
        if (className == null) return true;
        String n = className.trim().toLowerCase(java.util.Locale.ROOT);
        if (n.isEmpty()) return true;
        return n.startsWith("java.")
                || n.startsWith("javax.crypto.")
                || n.startsWith("jdk.")
                || n.startsWith("sun.")
                || n.startsWith("com.sun.")
                || n.startsWith("io.argus.instrument.")
                || n.startsWith("net.bytebuddy.");
    }

    /** Resolves the agent jar: {@code --agent-jar=} → {@code ARGUS_INSTRUMENT_JAR} → default. */
    static String resolveAgentJar(String[] args) {
        for (String a : args) {
            if (a.startsWith("--agent-jar=")) {
                return a.substring("--agent-jar=".length());
            }
        }
        String env = System.getenv("ARGUS_INSTRUMENT_JAR");
        if (env != null && !env.isEmpty()) return env;
        return DEFAULT_AGENT_JAR;
    }

    static InstrumentOptions parseOptions(String[] args, String mode, CliConfig config) {
        int maxHits = InstrumentOptions.DEFAULT_MAX_HITS;
        long timeoutMs = InstrumentOptions.DEFAULT_TIMEOUT_SECONDS * 1000L;
        int maxValueLen = InstrumentOptions.DEFAULT_MAX_VALUE_LEN;
        int maxArgs = InstrumentOptions.DEFAULT_MAX_ARGS;
        int maxDepth = InstrumentOptions.DEFAULT_MAX_DEPTH;
        String format = "json".equals(config.format()) ? "json" : "text";

        for (String a : args) {
            if (a.startsWith("--max-hits=")) {
                maxHits = parseIntOr(a.substring("--max-hits=".length()), maxHits);
            } else if (a.startsWith("--timeout=")) {
                timeoutMs = parseTimeoutMs(a.substring("--timeout=".length()), timeoutMs);
            } else if (a.startsWith("--max-value-len=")) {
                maxValueLen = parseIntOr(a.substring("--max-value-len=".length()), maxValueLen);
            } else if (a.startsWith("--max-args=")) {
                maxArgs = parseIntOr(a.substring("--max-args=".length()), maxArgs);
            } else if (a.startsWith("--max-depth=")) {
                maxDepth = parseIntOr(a.substring("--max-depth=".length()), maxDepth);
            } else if (a.equals("--format=json")) {
                format = "json";
            }
        }
        return new InstrumentOptions(maxHits, timeoutMs, maxValueLen, maxArgs, maxDepth, format);
    }

    /** Parses {@code Ns} (seconds) or a bare integer (seconds) into milliseconds. */
    static long parseTimeoutMs(String raw, long dflt) {
        if (raw == null || raw.isEmpty()) return dflt;
        String value = raw;
        if (value.endsWith("s")) value = value.substring(0, value.length() - 1);
        try {
            return Long.parseLong(value) * 1000L;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static int parseIntOr(String raw, int dflt) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Non-flag positional arguments in order; index 0 is the PID, index 1 the spec. */
    private static String[] positionals(String[] args) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String a : args) {
            if (!a.startsWith("--")) out.add(a);
        }
        return out.toArray(new String[0]);
    }

    private static String[] shift(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }
}
