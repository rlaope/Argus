package io.argus.aggregator;

import io.argus.aggregator.profile.FlameTree;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the collapsed-stack → flamegraph-tree builder.
 *
 * <p>Asserts inclusive leaf/branch counts and the multi-root → single synthetic
 * root behaviour, plus the differential builder's per-frame signed deltas. Tree
 * inspection is done with lightweight substring assertions on the hand-built
 * JSON — enough to pin the shape without pulling in a JSON parser.
 */
class FlameTreeTest {

    @Test
    void buildsTreeFromSemicolonStacksAndSumsInclusiveCounts() {
        Map<String, Long> collapsed = new LinkedHashMap<>();
        collapsed.put("main;a;b", 10L);
        collapsed.put("main;a;c", 5L);
        collapsed.put("main;d", 3L);

        String json = FlameTree.toJson(collapsed);

        // Root is the synthetic "root" with the grand total (10+5+3 = 18).
        assertTrue(json.startsWith("{\"name\":\"root\",\"value\":18,"), json);
        // "main" is inclusive of everything under it (still 18).
        assertTrue(json.contains("{\"name\":\"main\",\"value\":18,"), json);
        // "a" is inclusive of b + c = 15.
        assertTrue(json.contains("{\"name\":\"a\",\"value\":15,"), json);
        // Leaves carry their own counts.
        assertTrue(json.contains("{\"name\":\"b\",\"value\":10,\"children\":[]}"), json);
        assertTrue(json.contains("{\"name\":\"c\",\"value\":5,\"children\":[]}"), json);
        assertTrue(json.contains("{\"name\":\"d\",\"value\":3,\"children\":[]}"), json);
    }

    @Test
    void gathersMultipleRootsUnderSyntheticRoot() {
        Map<String, Long> collapsed = new LinkedHashMap<>();
        collapsed.put("rootA;x", 4L);
        collapsed.put("rootB;y", 6L);

        String json = FlameTree.toJson(collapsed);

        assertTrue(json.startsWith("{\"name\":\"root\",\"value\":10,"), json);
        assertTrue(json.contains("{\"name\":\"rootA\",\"value\":4,"), json);
        assertTrue(json.contains("{\"name\":\"rootB\",\"value\":6,"), json);
    }

    @Test
    void emptyMapYieldsZeroValueRoot() {
        String json = FlameTree.toJson(Map.of());
        assertEquals("{\"name\":\"root\",\"value\":0,\"children\":[]}", json);
    }

    @Test
    void diffCarriesSignedPerFrameDeltas() {
        Map<String, Long> base = new LinkedHashMap<>();
        base.put("main;a", 10L);   // shrinks to 6
        base.put("main;gone", 5L); // removed in head -> negative delta

        Map<String, Long> head = new LinkedHashMap<>();
        head.put("main;a", 6L);
        head.put("main;new", 8L);  // only in head -> positive delta

        String json = FlameTree.diffToJson(base, head);

        // Root: head total 14, base total 15, delta -1.
        assertTrue(json.startsWith("{\"name\":\"root\",\"value\":14,\"head\":14,\"base\":15,\"delta\":-1,"), json);
        // Frame "a" grew? base 10, head 6 -> delta -4.
        assertTrue(json.contains("{\"name\":\"a\",\"value\":6,\"head\":6,\"base\":10,\"delta\":-4,"), json);
        // Frame only in head.
        assertTrue(json.contains("{\"name\":\"new\",\"value\":8,\"head\":8,\"base\":0,\"delta\":8,"), json);
        // Frame only in base: head 0, base 5, delta -5.
        assertTrue(json.contains("{\"name\":\"gone\",\"value\":0,\"head\":0,\"base\":5,\"delta\":-5,"), json);
    }
}
