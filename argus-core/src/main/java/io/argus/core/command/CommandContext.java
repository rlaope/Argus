package io.argus.core.command;

/**
 * Execution context for diagnostic commands.
 *
 * <p>Sealed to exactly two variants:
 * <ul>
 *   <li>{@link InProcess} — command runs inside the target JVM (MXBeans, direct API access)</li>
 *   <li>{@link External} — command runs from outside, targeting a remote JVM by PID</li>
 * </ul>
 *
 * <p>Command implementations use pattern matching to handle each context:
 * <pre>
 * switch (ctx) {
 *     case InProcess ip -> ip.getBufferPoolMXBeans();
 *     case External ex  -> JcmdExecutor.execute(ex.pid(), "VM.info");
 * }
 * </pre>
 */
public sealed interface CommandContext permits CommandContext.InProcess, CommandContext.External {

    /**
     * In-process context: the command executes inside the monitored JVM.
     * Has direct access to MXBeans, Runtime APIs, and the current JVM state.
     */
    non-sealed interface InProcess extends CommandContext {
        /** Current JVM PID. */
        long pid();
    }

    /**
     * External context: the command targets a remote JVM by PID.
     * Uses jcmd, jstat, or attach API to gather data.
     */
    non-sealed interface External extends CommandContext {
        /** Target JVM PID. */
        long pid();
    }
}
