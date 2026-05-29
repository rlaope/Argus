package io.argus.cli.llm;

/**
 * Raised when an {@link LlmProvider} call fails. Callers fall back to the
 * deterministic findings output when this is thrown.
 */
public final class LlmException extends Exception {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
