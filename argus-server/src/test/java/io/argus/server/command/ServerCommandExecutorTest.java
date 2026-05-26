package io.argus.server.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the set of commands exposed to the web Console, plus the rejection path
 * for opted-out commands. Any new {@link io.argus.core.command.DiagnosticCommand}
 * implementation must update {@link #EXPECTED_WEB_CONSOLE_IDS} — this forces a
 * conscious decision about whether the new command belongs in the browser.
 */
class ServerCommandExecutorTest {

    /**
     * The exact set of command ids that {@link ServerCommandExecutor#getAvailableCommands()}
     * is expected to return. When you add a new SPI command, decide whether it is suitable
     * for one-click invocation from the browser:
     *
     * <ul>
     *   <li>If yes (read-only diagnostic, runs in &lt; a few seconds, no file output, no
     *       JVM mutation, no required args): add its id here.</li>
     *   <li>If no (destructive, long-running, file output, requires args): override
     *       {@code supportsWebConsole()} to return false on the implementation and do NOT
     *       add the id here.</li>
     * </ul>
     *
     * The point of this test is not to be exhaustive about safety — it is to force
     * the author of a new command to look at the categorisation rather than getting
     * silent default exposure on the web console.
     */
    private static final List<String> EXPECTED_WEB_CONSOLE_IDS = List.of(
            "buffers",
            "classloader",
            "classstat",
            "compiler",
            "compilerqueue",
            "deadlock",
            "doctor",
            "dynlibs",
            "env",
            "events",
            "finalizer",
            "gc",
            "gccause",
            "gcnew",
            "gcutil",
            "heap",
            "histo",
            "info",
            "jfr",
            "logger",
            "metaspace",
            "nmt",
            "pool",
            "sysprops",
            "threaddump",
            "threads",
            "vmflag",
            "vmlog"
    );

    /**
     * Commands that exist on the server SPI but explicitly opt out of the web Console.
     * Keep this list intentional and tight: the only ones here should genuinely be
     * unsafe or unfit for one-click invocation.
     */
    private static final List<String> EXPECTED_OPT_OUT_IDS = List.of(
            "gcrun" // System.gc() — destructive
    );

    @Test
    void webConsoleExposureMatchesExpectedSet() {
        Map<String, ServerCommandExecutor.CommandInfo> exposed =
                ServerCommandExecutor.getAvailableCommands();

        List<String> exposedIds = exposed.keySet().stream().sorted().toList();
        List<String> expectedIds = EXPECTED_WEB_CONSOLE_IDS.stream().sorted().toList();

        assertEquals(expectedIds, exposedIds,
                "Web console command exposure changed. " +
                "If you added a new DiagnosticCommand, decide whether it belongs in " +
                "the browser and update ServerCommandExecutorTest.EXPECTED_WEB_CONSOLE_IDS " +
                "(or override supportsWebConsole()->false on the implementation and add it " +
                "to EXPECTED_OPT_OUT_IDS instead).");
    }

    @Test
    void optOutCommandsAreNotExposedButStillRegistered() {
        Map<String, ServerCommandExecutor.CommandInfo> exposed =
                ServerCommandExecutor.getAvailableCommands();

        for (String optOutId : EXPECTED_OPT_OUT_IDS) {
            assertFalse(exposed.containsKey(optOutId),
                    "Opt-out command '" + optOutId + "' must not appear in /api/commands");
        }
    }

    @Test
    void executeRejectsOptOutCommandsWithTypedException() {
        for (String optOutId : EXPECTED_OPT_OUT_IDS) {
            WebConsoleRejectedException ex = assertThrows(
                    WebConsoleRejectedException.class,
                    () -> ServerCommandExecutor.execute(optOutId),
                    "Opt-out command '" + optOutId + "' must throw on /api/exec");
            assertTrue(ex.getMessage().contains(optOutId),
                    "Rejection message must reference the rejected command id");
        }
    }

    @Test
    void executeReturnsUnknownCommandStringForMissingId() {
        String result = ServerCommandExecutor.execute("definitely-not-a-real-command");
        assertEquals("Unknown command: definitely-not-a-real-command", result);
    }
}
