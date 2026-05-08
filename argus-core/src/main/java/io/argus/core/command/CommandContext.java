package io.argus.core.command;

/**
 * Execution context for diagnostic commands.
 *
 * <p>Two variants:
 * <ul>
 *   <li>{@link InProcess} — command runs inside the target JVM (MXBeans, direct API access)</li>
 *   <li>{@link External} — command runs from outside, targeting a remote JVM by PID</li>
 * </ul>
 *
 * <p>Command implementations dispatch on context type:
 * <pre>
 * if (ctx instanceof CommandContext.InProcess) {
 *     CommandContext.InProcess ip = (CommandContext.InProcess) ctx;
 *     ip.getBufferPoolMXBeans();
 * } else if (ctx instanceof CommandContext.External) {
 *     CommandContext.External ex = (CommandContext.External) ctx;
 *     JcmdExecutor.execute(ex.pid(), "VM.info");
 * }
 * </pre>
 */
public interface CommandContext {

    /**
     * In-process context: the command executes inside the monitored JVM.
     * Has direct access to MXBeans, Runtime APIs, and the current JVM state.
     */
    interface InProcess extends CommandContext {
        /** Current JVM PID. */
        long pid();
    }

    /**
     * External context: the command targets a remote JVM by PID.
     * Uses jcmd, jstat, or attach API to gather data.
     */
    interface External extends CommandContext {
        /** Target JVM PID. */
        long pid();
    }
}
