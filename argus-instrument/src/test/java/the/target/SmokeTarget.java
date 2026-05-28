package the.target;

/**
 * A real, loadable, top-level target class for {@code InstrumentEngineSmokeTest}.
 *
 * <p>It lives in a non-forbidden package ({@code the.target.*}) so
 * {@link io.argus.instrument.SafetyGuard} permits instrumenting it, and it is a
 * plain top-level class (NOT a lambda or anonymous class) so the engine's
 * {@code ElementMatchers.named(...)} can target it by exact binary name and
 * ByteBuddy can retransform a genuinely-loaded class.
 */
public class SmokeTarget {

    /** Instrumentation target: a simple arithmetic method with two args + a return value. */
    public int add(int a, int b) {
        return a + b;
    }

    /** A second target that always throws, to exercise the THROW event path. */
    public int boom(int a) {
        throw new IllegalStateException("boom " + a);
    }
}
