package io.argus.instrument;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueRendererTest {

    private static final CaptureCaps CAPS = CaptureCaps.defaults();

    /** A value whose toString() always throws — must NOT propagate out of render(). */
    static final class Hostile {
        @Override
        public String toString() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void render_nullIsLiteralNull() {
        assertEquals("null", ValueRenderer.render(null, CAPS));
    }

    @Test
    void render_nullCapsUsesDefaultLength() {
        // render must be null-caps-safe (defaults to 256).
        assertEquals("null", ValueRenderer.render(null, null));
    }

    @Test
    void render_stringIsQuoted() {
        assertEquals("\"hello\"", ValueRenderer.render("hello", CAPS));
    }

    @Test
    void render_charSequenceIsQuoted() {
        StringBuilder sb = new StringBuilder("buf");
        assertEquals("\"buf\"", ValueRenderer.render(sb, CAPS));
    }

    @Test
    void render_smallPrimitiveIntArrayIsFullyRendered() {
        int[] a = {1, 2, 3};
        assertEquals("[1, 2, 3]", ValueRenderer.render(a, CAPS));
    }

    @Test
    void render_smallPrimitiveLongArray() {
        long[] a = {10L, 20L};
        assertEquals("[10, 20]", ValueRenderer.render(a, CAPS));
    }

    @Test
    void render_smallPrimitiveBooleanArray() {
        boolean[] a = {true, false};
        assertEquals("[true, false]", ValueRenderer.render(a, CAPS));
    }

    @Test
    void render_largePrimitiveArraySummarised() {
        // length > 32 -> summarised as Type[len], not fully rendered.
        int[] big = new int[100];
        assertEquals("int[100]", ValueRenderer.render(big, CAPS));
    }

    @Test
    void render_objectArrayAlwaysSummarised() {
        // Object arrays are never fully rendered, even when short.
        String[] a = {"a", "b"};
        assertEquals("String[2]", ValueRenderer.render(a, CAPS));
    }

    @Test
    void render_collectionShowsSimpleNameAndSize() {
        List<String> list = new ArrayList<>(Arrays.asList("a", "b", "c"));
        assertEquals("ArrayList(size=3)", ValueRenderer.render(list, CAPS));
    }

    @Test
    void render_mapShowsSimpleNameAndSize() {
        Map<String, Integer> map = new HashMap<>();
        map.put("k1", 1);
        map.put("k2", 2);
        assertEquals("HashMap(size=2)", ValueRenderer.render(map, CAPS));
    }

    @Test
    void render_plainObjectUsesToString() {
        Object o = new Object() {
            @Override
            public String toString() {
                return "custom-repr";
            }
        };
        assertEquals("custom-repr", ValueRenderer.render(o, CAPS));
    }

    @Test
    void render_toStringThrowsDegradesToMarker_neverPropagates() {
        // The headline safety case: a hostile toString() must become a marker
        // string, NOT propagate the exception into the captured method.
        String rendered = ValueRenderer.render(new Hostile(), CAPS);
        assertTrue(rendered.startsWith("<"), rendered);
        assertTrue(rendered.contains("toString threw"), rendered);
        assertTrue(rendered.contains("IllegalStateException"), rendered);
        assertTrue(rendered.contains(Hostile.class.getName()), rendered);
    }

    @Test
    void render_longValueTruncatedWithEllipsis() {
        // Build a string longer than maxValueLen, then assert it's truncated with the
        // trailing ellipsis marker '…'.
        CaptureCaps small = CaptureCaps.defaults().withMaxValueLen(8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append('x');
        }
        // String renders quoted, so raw rendered length is 52 (50 + 2 quotes); truncated to 8.
        String rendered = ValueRenderer.render(sb.toString(), small);
        assertTrue(rendered.endsWith("…"), "expected trailing ellipsis, got: " + rendered);
        // 8 chars + the single ellipsis char.
        assertEquals(9, rendered.length(), rendered);
    }

    @Test
    void render_shortValueNotTruncated() {
        CaptureCaps caps = CaptureCaps.defaults().withMaxValueLen(100);
        String rendered = ValueRenderer.render("short", caps);
        assertEquals("\"short\"", rendered);
        assertFalse(rendered.contains("…"), rendered);
    }

    // --- truncate() (package-private) direct edge cases ---

    @Test
    void truncate_nullIsLiteralNull() {
        assertEquals("null", ValueRenderer.truncate(null, 10));
    }

    @Test
    void truncate_nonPositiveMaxReturnsUnchanged() {
        assertEquals("abc", ValueRenderer.truncate("abc", 0));
        assertEquals("abc", ValueRenderer.truncate("abc", -1));
    }

    @Test
    void truncate_exactLengthUnchanged() {
        assertEquals("abc", ValueRenderer.truncate("abc", 3));
    }

    @Test
    void truncate_longerGetsEllipsis() {
        assertEquals("ab…", ValueRenderer.truncate("abcdef", 2));
    }
}
