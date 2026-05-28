package io.argus.instrument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodSpecTest {

    @Test
    void parse_concreteMethod() {
        MethodSpec s = MethodSpec.parse("com.acme.OrderService#placeOrder");
        assertEquals("com.acme.OrderService", s.classBinaryName());
        assertEquals("com/acme/OrderService", s.classInternalName());
        assertEquals("placeOrder", s.methodPattern());
        assertFalse(s.matchesAllMethods());
        assertEquals("com.acme.OrderService#placeOrder", s.toString());
    }

    @Test
    void parse_trimsSurroundingWhitespace() {
        MethodSpec s = MethodSpec.parse("  com.acme.Foo # bar  ");
        assertEquals("com.acme.Foo", s.classBinaryName());
        assertEquals("bar", s.methodPattern());
    }

    @Test
    void parse_wildcardMethodMatchesAll() {
        MethodSpec s = MethodSpec.parse("com.acme.Foo#*");
        assertTrue(s.matchesAllMethods());
        assertEquals("*", s.methodPattern());
        assertTrue(s.matchesMethod("anything"));
        assertTrue(s.matchesMethod("somethingElse"));
    }

    @Test
    void parse_constructorToken() {
        MethodSpec s = MethodSpec.parse("com.acme.Foo#<init>");
        assertEquals("<init>", s.methodPattern());
        assertFalse(s.matchesAllMethods());
        assertTrue(s.matchesMethod("<init>"));
        assertFalse(s.matchesMethod("placeOrder"));
    }

    @Test
    void parse_staticInitializerToken() {
        MethodSpec s = MethodSpec.parse("com.acme.Foo#<clinit>");
        assertEquals("<clinit>", s.methodPattern());
        assertTrue(s.matchesMethod("<clinit>"));
    }

    @Test
    void matchesClass_exactDottedName() {
        MethodSpec s = MethodSpec.parse("com.acme.Foo#bar");
        assertTrue(s.matchesClass("com.acme.Foo"));
        assertFalse(s.matchesClass("com.acme.Bar"));
        assertFalse(s.matchesClass("com/acme/Foo"));
    }

    @Test
    void matchesMethod_nullIsFalse() {
        MethodSpec s = MethodSpec.parse("com.acme.Foo#bar");
        assertFalse(s.matchesMethod(null));
    }

    @Test
    void matchesMethod_nullIsFalseEvenForWildcard() {
        // The null guard runs before the wildcard check, so a null method name is
        // never a match, even for a '*' spec.
        MethodSpec s = MethodSpec.parse("com.acme.Foo#*");
        assertFalse(s.matchesMethod(null));
    }

    @Test
    void parse_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> MethodSpec.parse(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.acme.Foo",        // missing '#'
            "com.acme.Foobar",     // missing '#'
            "placeOrder",          // missing '#'
    })
    void parse_missingHashThrows(String raw) {
        assertThrows(IllegalArgumentException.class, () -> MethodSpec.parse(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.acme.Foo#bar#baz",
            "a#b#c",
    })
    void parse_doubleHashThrows(String raw) {
        assertThrows(IllegalArgumentException.class, () -> MethodSpec.parse(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1com.acme.Foo#bar",   // class segment starts with digit
            "com..acme.Foo#bar",   // empty class segment
            "com.acme.#bar",       // trailing dot, empty segment
            ".com.acme.Foo#bar",   // leading dot
            "com acme.Foo#bar",    // space in class name
    })
    void parse_malformedClassThrows(String raw) {
        assertThrows(IllegalArgumentException.class, () -> MethodSpec.parse(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.acme.Foo#1bar",   // method starts with digit
            "com.acme.Foo#",       // empty method
            "com.acme.Foo#ba r",   // space in method
            "com.acme.Foo#<bogus>", // unknown angle token
            "com.acme.Foo#a.b",    // dot not allowed in method token
    })
    void parse_malformedMethodThrows(String raw) {
        assertThrows(IllegalArgumentException.class, () -> MethodSpec.parse(raw));
    }

    @Test
    void parse_dollarAndUnderscoreClassNamesAllowed() {
        MethodSpec s = MethodSpec.parse("com.acme.Foo$Inner#do_it$");
        assertEquals("com.acme.Foo$Inner", s.classBinaryName());
        assertEquals("com/acme/Foo$Inner", s.classInternalName());
        assertEquals("do_it$", s.methodPattern());
    }
}
