package io.argus.instrument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyGuardTest {

    // --- assertEnabled ---

    @Test
    void assertEnabled_trueDoesNotThrow() {
        assertDoesNotThrow(() -> SafetyGuard.assertEnabled(true));
    }

    @Test
    void assertEnabled_falseThrowsRefused() {
        assertThrows(InstrumentationRefusedException.class, () -> SafetyGuard.assertEnabled(false));
    }

    // --- isForbidden: fail-closed inputs ---

    @Test
    void isForbidden_nullIsForbidden() {
        assertTrue(SafetyGuard.isForbidden(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    void isForbidden_blankIsForbidden(String blank) {
        assertTrue(SafetyGuard.isForbidden(blank));
    }

    // --- isForbidden: the full forbidden-prefix matrix ---

    @ParameterizedTest
    @ValueSource(strings = {
            "java.lang.String",
            "java.util.HashMap",
            "javax.crypto.Cipher",
            "jdk.internal.misc.Unsafe",
            "sun.misc.Unsafe",
            "com.sun.proxy.$Proxy0",
            "io.argus.instrument.InstrumentEngine",
            "net.bytebuddy.agent.ByteBuddyAgent",
    })
    void isForbidden_eachForbiddenPrefixMatches(String name) {
        assertTrue(SafetyGuard.isForbidden(name), name + " should be forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "JAVA.lang.String",
            "Javax.Crypto.Cipher",
            "JDK.internal.X",
            "SUN.misc.Unsafe",
            "COM.SUN.proxy.X",
            "IO.ARGUS.INSTRUMENT.Engine",
            "NET.BYTEBUDDY.Foo",
    })
    void isForbidden_caseInsensitive(String name) {
        assertTrue(SafetyGuard.isForbidden(name), name + " should be forbidden case-insensitively");
    }

    @Test
    void isForbidden_leadingWhitespaceIsTrimmedThenMatched() {
        assertTrue(SafetyGuard.isForbidden("   java.lang.String"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.acme.Foo",
            "com.acme.OrderService",
            "org.springframework.context.ApplicationContext",
            "javax.servlet.Filter",     // javax.* is allowed; only javax.crypto.* is forbidden
            "io.argus.cli.Main",        // io.argus.* is fine; only io.argus.instrument.* is forbidden
            "io.argus.diagnostics.X",
            "sunny.day.Foo",            // not the sun. prefix
            "javaland.Foo",             // not the java. prefix
            "comedy.Foo",               // not com.sun.
    })
    void isForbidden_userClassesAreAllowed(String name) {
        assertFalse(SafetyGuard.isForbidden(name), name + " should be allowed");
    }

    // --- assertInstrumentable ---

    @Test
    void assertInstrumentable_userClassDoesNotThrow() {
        assertDoesNotThrow(() -> SafetyGuard.assertInstrumentable("com.acme.Foo"));
    }

    @Test
    void assertInstrumentable_forbiddenThrowsRefused() {
        assertThrows(InstrumentationRefusedException.class,
                () -> SafetyGuard.assertInstrumentable("java.lang.String"));
    }

    @Test
    void assertInstrumentable_nullThrowsRefused() {
        assertThrows(InstrumentationRefusedException.class,
                () -> SafetyGuard.assertInstrumentable(null));
    }

    @Test
    void assertInstrumentable_agentInternalThrowsRefused() {
        assertThrows(InstrumentationRefusedException.class,
                () -> SafetyGuard.assertInstrumentable("io.argus.instrument.AdviceBridge"));
    }
}
