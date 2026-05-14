package io.argus.cli.jmx;

/**
 * Signals that attaching to a JVM process or resolving its JMX connector address failed.
 *
 * <p>This is a checked exception so callers are forced to decide whether the failure
 * is fatal (throw {@link io.argus.cli.command.CommandExitException}) or recoverable
 * (return a fallback value). All four callers that use {@link JmxAttachment} treat
 * attach failure as "print a message and stop the current operation", which maps
 * naturally to checked-exception handling.
 */
public final class JmxAttachmentException extends Exception {

    public JmxAttachmentException(String message) {
        super(message);
    }

    public JmxAttachmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
