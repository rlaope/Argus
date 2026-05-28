package io.argus.instrument;

/**
 * Thrown when {@link SafetyGuard} refuses an instrumentation request — either
 * because the opt-in enable flag was not asserted or because the target is a
 * JDK-internal class that must never be retransformed.
 *
 * <p>This is a hard refusal: callers must abort, never downgrade to a partial
 * instrumentation.
 */
public final class InstrumentationRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InstrumentationRefusedException(String message) {
        super(message);
    }
}
