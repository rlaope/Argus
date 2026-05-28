package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.instrument.InstrumentOptions;
import io.argus.cli.instrument.InstrumentSession;
import io.argus.cli.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentCommandTest {

    private static final Messages MESSAGES = new Messages("en");

    private static CliConfig defaultConfig() {
        return new CliConfig("en", "auto", false, "text", 9202);
    }

    private static ProviderRegistry emptyRegistry() {
        return new ProviderRegistry();
    }

    private interface ThrowingRun {
        void run();
    }

    private static String captureErr(ThrowingRun body) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err));
        try {
            body.run();
        } finally {
            System.setErr(orig);
        }
        return err.toString();
    }

    private static String captureOut(ThrowingRun body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(out));
        try {
            body.run();
        } finally {
            System.setOut(orig);
        }
        return out.toString();
    }

    @Test
    void helpFlagPrintsUsage() {
        InstrumentCommand cmd = new InstrumentCommand();
        String out = captureOut(() ->
                cmd.execute(new String[]{"--help"}, defaultConfig(), emptyRegistry(), MESSAGES));
        assertTrue(out.contains("argus instrument"), "Usage must mention `argus instrument`. Got: " + out);
        assertTrue(out.contains("watch"), "Usage must list watch");
        assertTrue(out.contains("trace"), "Usage must list trace");
        assertTrue(out.contains("monitor"), "Usage must list monitor");
    }

    @Test
    void unknownSubcommandPrintsUsageAndExits2() {
        InstrumentCommand cmd = new InstrumentCommand();
        captureOut(() -> {
            CommandExitException ex = assertThrows(CommandExitException.class, () ->
                    cmd.execute(new String[]{"bogus"}, defaultConfig(), emptyRegistry(), MESSAGES));
            assertEquals(2, ex.exitCode());
        });
    }

    /** The load-bearing default-OFF guarantee. */
    @Test
    void refusesWithoutEnableFlag() {
        InstrumentCommand cmd = new InstrumentCommand();
        String err = captureErr(() -> {
            CommandExitException ex = assertThrows(CommandExitException.class, () ->
                    cmd.execute(new String[]{"watch", "12345", "com.acme.Foo#bar"},
                            defaultConfig(), emptyRegistry(), MESSAGES));
            assertEquals(2, ex.exitCode(), "Missing --enable-instrument must exit 2");
        });
        assertTrue(err.contains("disabled") || err.contains("--enable-instrument"),
                "Disabled message expected. Got: " + err);
    }

    /** Forbidden JDK-internal target is refused even WITH the enable flag. */
    @Test
    void refusesJdkInternalTargets() {
        InstrumentCommand cmd = new InstrumentCommand();
        captureErr(() -> {
            CommandExitException ex = assertThrows(CommandExitException.class, () ->
                    cmd.execute(new String[]{"watch", "12345", "java.lang.String#length", "--enable-instrument"},
                            defaultConfig(), emptyRegistry(), MESSAGES));
            assertEquals(2, ex.exitCode(), "Forbidden target must exit 2");
        });
    }

    @Test
    void malformedSpecNoHashExits2() {
        InstrumentCommand cmd = new InstrumentCommand();
        captureErr(() -> {
            CommandExitException ex = assertThrows(CommandExitException.class, () ->
                    cmd.execute(new String[]{"watch", "12345", "Foo", "--enable-instrument"},
                            defaultConfig(), emptyRegistry(), MESSAGES));
            assertEquals(2, ex.exitCode(), "No '#' must exit 2 (badspec)");
        });
    }

    @Test
    void malformedSpecTwoHashesExits2() {
        InstrumentCommand cmd = new InstrumentCommand();
        captureErr(() -> {
            CommandExitException ex = assertThrows(CommandExitException.class, () ->
                    cmd.execute(new String[]{"watch", "12345", "com.acme.Foo#bar#baz", "--enable-instrument"},
                            defaultConfig(), emptyRegistry(), MESSAGES));
            assertEquals(2, ex.exitCode(), "Two '#' must exit 2 (badspec)");
        });
    }

    /**
     * Missing agent JAR must be caught (exit 3) BEFORE any attach is attempted.
     * The bogus PID would otherwise fail; we use a real PID (this process) so PID
     * validation passes and we reach the noagent branch.
     */
    @Test
    void missingAgentJarExits3() {
        long ownPid = ProcessHandle.current().pid();
        InstrumentCommand cmd = new InstrumentCommand();
        String err = captureErr(() -> {
            CommandExitException ex = assertThrows(CommandExitException.class, () ->
                    cmd.execute(new String[]{
                            "watch", String.valueOf(ownPid), "com.acme.Foo#bar",
                            "--enable-instrument",
                            "--agent-jar=/nonexistent/path/argus-instrument.jar"
                    }, defaultConfig(), emptyRegistry(), MESSAGES));
            assertEquals(3, ex.exitCode(), "Missing agent jar must exit 3");
        });
        assertTrue(err.contains("/nonexistent/path/argus-instrument.jar"),
                "noagent message must include the resolved path. Got: " + err);
    }

    @Test
    void specValidation() {
        assertTrue(InstrumentCommand.isValidSpec("com.acme.Foo#bar"));
        assertTrue(InstrumentCommand.isValidSpec("com.acme.Foo#*"));
        assertTrue(InstrumentCommand.isValidSpec("Foo#bar"));
        assertFalse(InstrumentCommand.isValidSpec("Foo"));
        assertFalse(InstrumentCommand.isValidSpec("com.acme.Foo#bar#baz"));
        assertFalse(InstrumentCommand.isValidSpec("#bar"));
        assertFalse(InstrumentCommand.isValidSpec("com.acme.Foo#"));
        assertFalse(InstrumentCommand.isValidSpec(null));
    }

    @Test
    void forbiddenPackages() {
        assertTrue(InstrumentCommand.isForbidden("java.lang.String"));
        assertTrue(InstrumentCommand.isForbidden("javax.crypto.Cipher"));
        assertTrue(InstrumentCommand.isForbidden("jdk.internal.misc.Unsafe"));
        assertTrue(InstrumentCommand.isForbidden("sun.misc.Unsafe"));
        assertTrue(InstrumentCommand.isForbidden("com.sun.tools.attach.VirtualMachine"));
        assertTrue(InstrumentCommand.isForbidden("io.argus.instrument.Agent"));
        assertTrue(InstrumentCommand.isForbidden("net.bytebuddy.ByteBuddy"));
        assertFalse(InstrumentCommand.isForbidden("com.acme.Foo"));
        assertFalse(InstrumentCommand.isForbidden("io.argus.cli.command.InstrumentCommand"));
    }

    @Test
    void optionParsingProducesExpectedWireString() {
        InstrumentOptions opts = InstrumentCommand.parseOptions(
                new String[]{"54321", "com.acme.Foo#bar", "--enable-instrument",
                        "--max-hits=5", "--timeout=10"},
                "watch", defaultConfig());
        assertEquals(5, opts.maxHits());
        assertEquals(10_000L, opts.timeoutMs());
        assertEquals(InstrumentOptions.DEFAULT_MAX_VALUE_LEN, opts.maxValueLen());

        String wire = InstrumentSession.buildOptionsWire("watch", "com.acme.Foo#bar", 7777, "abc123", opts);
        assertTrue(wire.contains("mode=watch"), wire);
        assertTrue(wire.contains("spec=com.acme.Foo#bar"), wire);
        assertTrue(wire.contains("port=7777"), wire);
        assertTrue(wire.contains("nonce=abc123"), wire);
        assertTrue(wire.contains("enabled=true"), wire);
        assertTrue(wire.contains("maxHits=5"), wire);
        assertTrue(wire.contains("timeoutMs=10000"), wire);
        assertTrue(wire.contains("maxEventsPerSecond=500"), wire);
    }

    @Test
    void timeoutAcceptsSecondsSuffixAndBareNumber() {
        assertEquals(30_000L, InstrumentCommand.parseTimeoutMs("30s", 0L));
        assertEquals(45_000L, InstrumentCommand.parseTimeoutMs("45", 0L));
        assertEquals(99L, InstrumentCommand.parseTimeoutMs("garbage", 99L));
    }
}
