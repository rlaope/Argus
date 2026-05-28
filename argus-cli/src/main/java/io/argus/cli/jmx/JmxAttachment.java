package io.argus.cli.jmx;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Centralises the JVM-attach + JMX-connect boilerplate used by MBeanCommand,
 * SpringCommand, ThreadsCommand, and ZgcCommand.
 *
 * <p>The typical attach sequence is:
 * <ol>
 *   <li>{@code VirtualMachine.attach(pid)}</li>
 *   <li>Read {@code com.sun.management.jmxremote.localConnectorAddress} from agent properties.</li>
 *   <li>If absent, call {@code vm.startLocalManagementAgent()} and re-read.</li>
 *   <li>{@code JMXConnectorFactory.connect(new JMXServiceURL(addr))}</li>
 *   <li>Use {@code MBeanServerConnection}.</li>
 *   <li>Close connector + detach VM in finally.</li>
 * </ol>
 *
 * <p>All methods throw {@link JmxAttachmentException} on failure so callers can
 * choose between a fatal exit or a graceful fallback.
 */
public final class JmxAttachment {

    private static final String LOCAL_CONNECTOR_ADDRESS =
            "com.sun.management.jmxremote.localConnectorAddress";

    private JmxAttachment() {}

    /**
     * Functional interface for code that uses an {@link MBeanServerConnection}.
     *
     * @param <T> return type
     */
    @FunctionalInterface
    public interface ConnectionUser<T> {
        T use(MBeanServerConnection conn) throws Exception;
    }

    /**
     * Attaches to the JVM identified by {@code pid}, starts the local management
     * agent if it is not already running, opens a JMX connector, runs {@code fn},
     * and cleans everything up — even if {@code fn} throws.
     *
     * @param pid the target process id
     * @param fn  the work to perform against the live {@link MBeanServerConnection}
     * @return whatever {@code fn} returns
     * @throws JmxAttachmentException if the attach or JMX connect step fails
     * @throws Exception              if {@code fn} itself throws
     */
    public static <T> T withConnection(long pid, ConnectionUser<T> fn)
            throws JmxAttachmentException, Exception {
        String addr = resolveConnectorAddress(pid);
        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(addr))) {
            return fn.use(connector.getMBeanServerConnection());
        }
    }

    /**
     * Attaches to the JVM identified by {@code pid} and resolves the JMX service URL
     * string, starting the local management agent if it is not already active.
     *
     * <p>The VM is always detached before this method returns.
     *
     * @param pid the target process id
     * @return the non-null JMX connector address string
     * @throws JmxAttachmentException if attach fails or the address cannot be resolved
     */
    public static String resolveConnectorAddress(long pid) throws JmxAttachmentException {
        com.sun.tools.attach.VirtualMachine vm;
        try {
            vm = com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
        } catch (LinkageError e) {
            // The Attach API needs the JDK's libattach native library, which a
            // GraalVM native-image build does not ship — without this guard the
            // UnsatisfiedLinkError (an Error, not an Exception) escapes as a raw
            // stack trace. Convert it so callers take their normal graceful path.
            throw new JmxAttachmentException(
                    "Attach API unavailable in this build (no libattach). Run "
                    + "attach-based commands from the JAR instead: "
                    + "java -jar argus-cli.jar <command> " + pid, e);
        } catch (Exception e) {
            throw new JmxAttachmentException(
                    "Cannot attach to PID " + pid + ": " + e.getMessage(), e);
        }

        try {
            String addr = vm.getAgentProperties().getProperty(LOCAL_CONNECTOR_ADDRESS);
            if (addr == null) {
                vm.startLocalManagementAgent();
                addr = vm.getAgentProperties().getProperty(LOCAL_CONNECTOR_ADDRESS);
            }
            if (addr == null) {
                throw new JmxAttachmentException(
                        "Cannot resolve JMX connector address for PID " + pid
                        + " — management agent may not have started");
            }
            return addr;
        } catch (JmxAttachmentException e) {
            throw e;
        } catch (Exception e) {
            throw new JmxAttachmentException(
                    "Failed to start management agent for PID " + pid + ": " + e.getMessage(), e);
        } finally {
            try { vm.detach(); } catch (Exception ignored) {}
        }
    }
}
