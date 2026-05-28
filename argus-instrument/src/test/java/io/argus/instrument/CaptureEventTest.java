package io.argus.instrument;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureEventTest {

    // --- kind() + the per-kind JSON contract ---

    @Test
    void enter_jsonHasTypeArgsAndDepth() {
        CaptureEvent e = CaptureEvent.enter(123L, "main", "com.acme.Foo", "bar", 2,
                Arrays.asList("\"x\"", "42"));
        assertEquals(CaptureEvent.Kind.ENTER, e.kind());
        String json = e.toJson();
        assertSingleLine(json);
        assertTrue(json.contains("\"type\":\"ENTER\""), json);
        assertTrue(json.contains("\"ts\":123"), json);
        assertTrue(json.contains("\"thread\":\"main\""), json);
        assertTrue(json.contains("\"clazz\":\"com.acme.Foo\""), json);
        assertTrue(json.contains("\"method\":\"bar\""), json);
        assertTrue(json.contains("\"depth\":2"), json);
        assertTrue(json.contains("\"args\":["), json);
        // ENTER does not carry exit/throw fields.
        assertFalse(json.contains("\"ret\""), json);
        assertFalse(json.contains("\"wallNanos\""), json);
    }

    @Test
    void enter_emptyArgsRendersEmptyArray() {
        CaptureEvent e = CaptureEvent.enter(1L, "t", "C", "m", 0, Collections.emptyList());
        String json = e.toJson();
        assertTrue(json.contains("\"args\":[]"), json);
    }

    @Test
    void exit_jsonHasRetAndWallNanos() {
        CaptureEvent e = CaptureEvent.exit(456L, "worker-1", "com.acme.Foo", "bar", 1,
                "\"result\"", 987654L);
        assertEquals(CaptureEvent.Kind.EXIT, e.kind());
        String json = e.toJson();
        assertSingleLine(json);
        assertTrue(json.contains("\"type\":\"EXIT\""), json);
        assertTrue(json.contains("\"ret\":"), json);
        assertTrue(json.contains("\"wallNanos\":987654"), json);
        assertTrue(json.contains("\"depth\":1"), json);
        assertFalse(json.contains("\"args\""), json);
        assertFalse(json.contains("\"ex\""), json);
    }

    @Test
    void thrown_jsonHasExAndWallNanos() {
        CaptureEvent e = CaptureEvent.thrown(789L, "main", "com.acme.Foo", "bar", 0,
                "java.lang.IllegalStateException: boom", 1234L);
        assertEquals(CaptureEvent.Kind.THROW, e.kind());
        String json = e.toJson();
        assertSingleLine(json);
        assertTrue(json.contains("\"type\":\"THROW\""), json);
        assertTrue(json.contains("\"ex\":\"java.lang.IllegalStateException: boom\""), json);
        assertTrue(json.contains("\"wallNanos\":1234"), json);
        assertFalse(json.contains("\"ret\""), json);
    }

    @Test
    void monitor_jsonHasAggregateFields() {
        CaptureEvent e = CaptureEvent.monitor(111L, "com.acme.Foo", "bar",
                10L, 7L, 3L, 1.5, 9.25);
        assertEquals(CaptureEvent.Kind.MONITOR, e.kind());
        String json = e.toJson();
        assertSingleLine(json);
        assertTrue(json.contains("\"type\":\"MONITOR\""), json);
        assertTrue(json.contains("\"count\":10"), json);
        assertTrue(json.contains("\"success\":7"), json);
        assertTrue(json.contains("\"failure\":3"), json);
        assertTrue(json.contains("\"avgMs\":1.500"), json);
        assertTrue(json.contains("\"maxMs\":9.250"), json);
        // MONITOR has no thread/depth/wallNanos.
        assertFalse(json.contains("\"depth\""), json);
        assertFalse(json.contains("\"wallNanos\""), json);
    }

    @Test
    void monitor_nanOrInfinityRendersAsZero() {
        CaptureEvent e = CaptureEvent.monitor(1L, "C", "m", 0L, 0L, 0L,
                Double.NaN, Double.POSITIVE_INFINITY);
        String json = e.toJson();
        assertTrue(json.contains("\"avgMs\":0"), json);
        assertTrue(json.contains("\"maxMs\":0"), json);
    }

    @Test
    void notice_jsonHasMessageOnly() {
        CaptureEvent e = CaptureEvent.notice(222L, "detached: hit limit");
        assertEquals(CaptureEvent.Kind.NOTICE, e.kind());
        String json = e.toJson();
        assertSingleLine(json);
        assertTrue(json.contains("\"type\":\"NOTICE\""), json);
        assertTrue(json.contains("\"message\":\"detached: hit limit\""), json);
        assertFalse(json.contains("\"clazz\""), json);
        assertFalse(json.contains("\"depth\""), json);
    }

    // --- escaping keeps JSON one line and valid ---

    @Test
    void escape_quotesAreBackslashed() {
        assertEquals("a\\\"b", CaptureEvent.escape("a\"b"));
    }

    @Test
    void escape_backslashIsDoubled() {
        assertEquals("a\\\\b", CaptureEvent.escape("a\\b"));
    }

    @Test
    void escape_newlinesAndTabsBecomeEscapes() {
        assertEquals("a\\nb", CaptureEvent.escape("a\nb"));
        assertEquals("a\\rb", CaptureEvent.escape("a\rb"));
        assertEquals("a\\tb", CaptureEvent.escape("a\tb"));
    }

    @Test
    void escape_nullBecomesEmpty() {
        assertEquals("", CaptureEvent.escape(null));
    }

    @Test
    void escape_otherControlCharsBecomeUnicode() {
        // A non-named control char (0x01) is emitted as a \\uXXXX escape so it can
        // never break line framing.
        assertEquals("\\u0001", CaptureEvent.escape(String.valueOf((char) 0x01)));
    }


    @Test
    void enter_embeddedQuotesAndNewlinesStayOneValidLine() {
        // An argument value carrying quotes + a newline must not break line framing.
        String nasty = "he said \"hi\"\nthen left\twork";
        CaptureEvent e = CaptureEvent.enter(1L, "main", "com.acme.Foo", "bar", 0,
                Collections.singletonList(nasty));
        String json = e.toJson();
        assertSingleLine(json);
        // The raw newline must not appear; it must be escaped as \n.
        assertFalse(json.contains("\n"), "JSON must be a single line");
        assertTrue(json.contains("\\n"), json);
        assertTrue(json.contains("\\\"hi\\\""), json);
        // Quotes are balanced: braces present and structurally well-formed.
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
    }

    @Test
    void notice_messageWithNewlineIsEscaped() {
        CaptureEvent e = CaptureEvent.notice(1L, "line1\nline2");
        String json = e.toJson();
        assertSingleLine(json);
        assertTrue(json.contains("line1\\nline2"), json);
    }

    private static void assertSingleLine(String json) {
        assertFalse(json.contains("\n"), "JSON must contain no newline: " + json);
        assertFalse(json.contains("\r"), "JSON must contain no carriage return: " + json);
    }
}
