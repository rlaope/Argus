package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class PoolCommandDispatchTest {

    private static final Messages MESSAGES = new Messages("en");

    private static CliConfig defaultConfig() {
        return new CliConfig("en", "auto", false, "text", 9202);
    }

    private static ProviderRegistry emptyRegistry() {
        return new ProviderRegistry();
    }

    /**
     * The dispatcher must treat "help" / "--help" / "-h" as a print-usage path
     * (no PID, no JMX attach), proving it does not blindly parse args[0] as PID.
     */
    @Test
    void helpFlagPrintsUsageWithoutTouchingPid() {
        PoolCommand cmd = new PoolCommand();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(out));
        try {
            cmd.execute(new String[]{"--help"}, defaultConfig(), emptyRegistry(), MESSAGES);
        } finally {
            System.setOut(orig);
        }
        String s = out.toString();
        assertTrue(s.contains("argus pool"), "Usage must mention `argus pool`. Got: " + s);
        assertTrue(s.contains("jdbc"), "Usage must list jdbc subcommand");
        assertTrue(s.contains("advise"), "Usage must list advise subcommand");
    }

    /**
     * `argus pool jdbc` with no PID must fail with exit code 2 (PID required),
     * proving that "jdbc" was consumed as subcommand and dispatched to the jdbc
     * handler — not parsed as a PID by the default path.
     */
    @Test
    void jdbcSubcommandRoutesAndRequiresPid() {
        PoolCommand cmd = new PoolCommand();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            CommandExitException ex = assertThrows(CommandExitException.class, () ->
                    cmd.execute(new String[]{"jdbc"}, defaultConfig(), emptyRegistry(), MESSAGES));
            assertEquals(2, ex.exitCode(),
                    "Missing PID after `jdbc` subcommand must exit with code 2");
        } finally {
            System.setErr(origErr);
        }
        assertTrue(err.toString().contains("PID"),
                "Error message must mention PID");
    }

    /**
     * Same shape for `argus pool advise` — proves dispatch routes to the advise
     * handler rather than treating "advise" as a PID.
     */
    @Test
    void adviseSubcommandRoutesAndRequiresPid() {
        PoolCommand cmd = new PoolCommand();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            CommandExitException ex = assertThrows(CommandExitException.class, () ->
                    cmd.execute(new String[]{"advise"}, defaultConfig(), emptyRegistry(), MESSAGES));
            assertEquals(2, ex.exitCode(),
                    "Missing PID after `advise` subcommand must exit with code 2");
        } finally {
            System.setErr(origErr);
        }
        assertTrue(err.toString().contains("PID"),
                "Error message must mention PID");
    }
}
