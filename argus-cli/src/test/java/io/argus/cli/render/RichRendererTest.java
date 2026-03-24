package io.argus.cli.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RichRendererTest {

    // --- formatBytes ---

    @Test
    void formatBytes_zero() {
        assertEquals("0B", RichRenderer.formatBytes(0));
    }

    @Test
    void formatBytes_negative() {
        assertEquals("0B", RichRenderer.formatBytes(-1));
    }

    @Test
    void formatBytes_bytes_rounds_to_k() {
        // 512 bytes = 0.5K -> rounds to 0K? Actually 512/1024 = 0.5, formatted as %.0f -> "0K"
        // But 1023 bytes = 0.999 -> "1K"
        assertEquals("1K", RichRenderer.formatBytes(1023));
    }

    @Test
    void formatBytes_exact_kb() {
        assertEquals("1K", RichRenderer.formatBytes(1024));
    }

    @Test
    void formatBytes_mb_range() {
        // 1024 * 1024 = 1MB
        assertEquals("1M", RichRenderer.formatBytes(1024 * 1024));
    }

    @Test
    void formatBytes_gb_range() {
        // 1.5 GB
        assertEquals("1.5G", RichRenderer.formatBytes((long) (1.5 * 1024 * 1024 * 1024)));
    }

    @Test
    void formatBytes_tb_range() {
        // 2 TB
        assertEquals("2.0T", RichRenderer.formatBytes(2L * 1024 * 1024 * 1024 * 1024));
    }

    // --- formatNumber ---

    @Test
    void formatNumber_small() {
        assertEquals("999", RichRenderer.formatNumber(999));
    }

    @Test
    void formatNumber_zero() {
        assertEquals("0", RichRenderer.formatNumber(0));
    }

    @Test
    void formatNumber_k_range() {
        assertEquals("1.2K", RichRenderer.formatNumber(1200));
    }

    @Test
    void formatNumber_m_range() {
        assertEquals("5.3M", RichRenderer.formatNumber(5_300_000));
    }

    @Test
    void formatNumber_exactly_1000() {
        assertEquals("1.0K", RichRenderer.formatNumber(1_000));
    }

    // --- formatDuration ---

    @Test
    void formatDuration_seconds() {
        assertEquals("10s", RichRenderer.formatDuration(10_000));
    }

    @Test
    void formatDuration_zero() {
        assertEquals("0s", RichRenderer.formatDuration(0));
    }

    @Test
    void formatDuration_minutes_and_seconds() {
        assertEquals("2m 30s", RichRenderer.formatDuration(150_000));
    }

    @Test
    void formatDuration_hours_and_minutes() {
        assertEquals("1h 15m", RichRenderer.formatDuration(75 * 60 * 1000L));
    }

    @Test
    void formatDuration_exactly_one_minute() {
        assertEquals("1m 0s", RichRenderer.formatDuration(60_000));
    }

    // --- humanClassName ---

    @Test
    void humanClassName_null() {
        assertEquals("?", RichRenderer.humanClassName(null));
    }

    @Test
    void humanClassName_byte_array() {
        assertEquals("byte[]", RichRenderer.humanClassName("[B"));
    }

    @Test
    void humanClassName_int_array() {
        assertEquals("int[]", RichRenderer.humanClassName("[I"));
    }

    @Test
    void humanClassName_long_array() {
        assertEquals("long[]", RichRenderer.humanClassName("[J"));
    }

    @Test
    void humanClassName_object_array() {
        // [Ljava.lang.String; -> java.lang.String[]
        // parts.length == 3, not > 3, so full name is kept
        assertEquals("java.lang.String[]", RichRenderer.humanClassName("[Ljava.lang.String;"));
    }

    @Test
    void humanClassName_inner_class_dollar() {
        // java.util.HashMap$Node -> parts.length=4 > 3 -> "HashMap.Node"
        assertEquals("HashMap.Node", RichRenderer.humanClassName("java.util.HashMap$Node"));
    }

    @Test
    void humanClassName_jdk_internal_array() {
        // [Ljdk.internal.vm.FillerElement; -> strip L/; -> jdk.internal.vm.FillerElement
        // parts: ["jdk","internal","vm","FillerElement"] length=4>3 -> "vm.FillerElement"
        // then append "[]"
        assertEquals("vm.FillerElement[]", RichRenderer.humanClassName("[Ljdk.internal.vm.FillerElement;"));
    }

    @Test
    void humanClassName_with_module_info() {
        // "java.lang.String (java.base@21)" -> strip module -> "java.lang.String"
        // parts.length == 3, not > 3
        assertEquals("java.lang.String", RichRenderer.humanClassName("java.lang.String (java.base@21)"));
    }

    @Test
    void humanClassName_short_name_preserved() {
        assertEquals("Foo", RichRenderer.humanClassName("Foo"));
    }

    @Test
    void humanClassName_two_segment_name() {
        assertEquals("com.Foo", RichRenderer.humanClassName("com.Foo"));
    }

    @Test
    void humanClassName_char_array() {
        assertEquals("char[]", RichRenderer.humanClassName("[C"));
    }

    @Test
    void humanClassName_boolean_array() {
        assertEquals("boolean[]", RichRenderer.humanClassName("[Z"));
    }

    // --- shortenClassName ---

    @Test
    void shortenClassName_null() {
        assertEquals("?", RichRenderer.shortenClassName(null));
    }

    @Test
    void shortenClassName_short_name_unchanged() {
        assertEquals("Foo", RichRenderer.shortenClassName("Foo"));
    }

    @Test
    void shortenClassName_two_segments_unchanged() {
        assertEquals("com.Foo", RichRenderer.shortenClassName("com.Foo"));
    }

    @Test
    void shortenClassName_long_name_shortened() {
        assertEquals("server.Foo", RichRenderer.shortenClassName("io.argus.server.Foo"));
    }

    // --- escapeJson ---

    @Test
    void escapeJson_null() {
        assertEquals("", RichRenderer.escapeJson(null));
    }

    @Test
    void escapeJson_quotes() {
        assertEquals("say \\\"hello\\\"", RichRenderer.escapeJson("say \"hello\""));
    }

    @Test
    void escapeJson_backslash() {
        assertEquals("a\\\\b", RichRenderer.escapeJson("a\\b"));
    }

    @Test
    void escapeJson_no_special_chars() {
        assertEquals("hello world", RichRenderer.escapeJson("hello world"));
    }

    // --- truncate ---

    @Test
    void truncate_null() {
        assertEquals("", RichRenderer.truncate(null, 10));
    }

    @Test
    void truncate_short_string_unchanged() {
        assertEquals("hello", RichRenderer.truncate("hello", 10));
    }

    @Test
    void truncate_exact_fit_unchanged() {
        assertEquals("hello", RichRenderer.truncate("hello", 5));
    }

    @Test
    void truncate_too_long_appends_ellipsis() {
        String result = RichRenderer.truncate("hello world", 6);
        assertTrue(result.endsWith("\u2026"), "should end with ellipsis");
        assertEquals(6, result.length());
    }

    // --- padRight ---

    @Test
    void padRight_pads_short_string() {
        assertEquals("hi   ", RichRenderer.padRight("hi", 5));
    }

    @Test
    void padRight_exact_width_unchanged() {
        assertEquals("hello", RichRenderer.padRight("hello", 5));
    }

    @Test
    void padRight_longer_than_width_unchanged() {
        assertEquals("toolong", RichRenderer.padRight("toolong", 3));
    }

    @Test
    void padRight_null_treated_as_empty() {
        assertEquals("     ", RichRenderer.padRight(null, 5));
    }

    // --- padLeft ---

    @Test
    void padLeft_pads_short_string() {
        assertEquals("   hi", RichRenderer.padLeft("hi", 5));
    }

    @Test
    void padLeft_exact_width_unchanged() {
        assertEquals("hello", RichRenderer.padLeft("hello", 5));
    }

    @Test
    void padLeft_longer_than_width_unchanged() {
        assertEquals("toolong", RichRenderer.padLeft("toolong", 3));
    }

    @Test
    void padLeft_null_treated_as_empty() {
        assertEquals("     ", RichRenderer.padLeft(null, 5));
    }
}
