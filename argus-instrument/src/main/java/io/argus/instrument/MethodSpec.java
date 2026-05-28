package io.argus.instrument;

import java.util.regex.Pattern;

/**
 * A parsed instrumentation target of the form {@code <fully.qualified.Class>#<method>}.
 *
 * <p>The method token may be a concrete name ({@code process}), the constructor
 * marker ({@code <init>}), or {@code *} to match every method on the class.
 * The class token must always be a concrete fully-qualified name — wildcards on
 * the class are deliberately not supported, so a single spec can never fan out
 * to "instrument the world".
 *
 * <p>Examples: {@code com.acme.OrderService#placeOrder}, {@code com.acme.Foo#*},
 * {@code com.acme.Foo#<init>}.
 */
public final class MethodSpec {

    private static final Pattern CLASS_NAME =
            Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*");
    private static final Pattern METHOD_NAME =
            Pattern.compile("([\\p{L}_$][\\p{L}\\p{N}_$]*|<init>|<clinit>|\\*)");

    private final String classBinaryName;
    private final String methodPattern;

    private MethodSpec(String classBinaryName, String methodPattern) {
        this.classBinaryName = classBinaryName;
        this.methodPattern = methodPattern;
    }

    /**
     * Parses a {@code Class#method} spec.
     *
     * @throws IllegalArgumentException if the spec is null, missing exactly one
     *         {@code #}, or has a malformed class or method token
     */
    public static MethodSpec parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("method spec is required (expected Class#method)");
        }
        String spec = raw.trim();
        int hash = spec.indexOf('#');
        if (hash < 0 || spec.indexOf('#', hash + 1) >= 0) {
            throw new IllegalArgumentException(
                    "malformed spec '" + raw + "' (expected exactly one '#', e.g. com.acme.Foo#bar)");
        }
        String cls = spec.substring(0, hash).trim();
        String method = spec.substring(hash + 1).trim();
        if (!CLASS_NAME.matcher(cls).matches()) {
            throw new IllegalArgumentException("malformed class name in spec: '" + cls + "'");
        }
        if (!METHOD_NAME.matcher(method).matches()) {
            throw new IllegalArgumentException("malformed method name in spec: '" + method + "'");
        }
        return new MethodSpec(cls, method);
    }

    /** Dotted class name, e.g. {@code com.acme.Foo}. */
    public String classBinaryName() {
        return classBinaryName;
    }

    /** Internal (slash) class name, e.g. {@code com/acme/Foo}. */
    public String classInternalName() {
        return classBinaryName.replace('.', '/');
    }

    /** The method token: a concrete name, {@code <init>}/{@code <clinit>}, or {@code *}. */
    public String methodPattern() {
        return methodPattern;
    }

    /** Whether the method token matches every method on the class. */
    public boolean matchesAllMethods() {
        return "*".equals(methodPattern);
    }

    /** Whether {@code candidate} (dotted) is the class this spec targets. */
    public boolean matchesClass(String candidateBinaryName) {
        return classBinaryName.equals(candidateBinaryName);
    }

    /** Whether {@code methodName} matches this spec's method token. */
    public boolean matchesMethod(String methodName) {
        if (methodName == null) {
            return false;
        }
        return matchesAllMethods() || methodPattern.equals(methodName);
    }

    @Override
    public String toString() {
        return classBinaryName + "#" + methodPattern;
    }
}
