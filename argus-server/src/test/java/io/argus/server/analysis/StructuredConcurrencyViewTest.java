package io.argus.server.analysis;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructuredConcurrencyViewTest {

    private static String fixture() throws Exception {
        try (InputStream in = StructuredConcurrencyViewTest.class
                .getResourceAsStream("/loom/thread-dump.json")) {
            assertNotNull(in, "fixture /loom/thread-dump.json must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTwoRootScopesNestedTree() throws Exception {
        List<StructuredConcurrencyView.ScopeNode> roots =
                StructuredConcurrencyView.parse(fixture());

        // One top-level scope (ShutdownOnFailure) owned by main; the <root> container is not a scope.
        assertEquals(1, roots.size(), "expected a single root StructuredTaskScope");

        StructuredConcurrencyView.ScopeNode root = roots.get(0);
        assertTrue(root.name().contains("ShutdownOnFailure"));
        assertEquals(1L, root.ownerTid());
        assertEquals(2, root.subtasks().size());

        // Forked subtasks are grouped under their scope.
        assertEquals(
                List.of("fetch-user", "fetch-order"),
                root.subtasks().stream().map(StructuredConcurrencyView.Subtask::name).toList());

        // The nested ShutdownOnSuccess scope hangs under the parent scope.
        assertEquals(1, root.children().size());
        StructuredConcurrencyView.ScopeNode child = root.children().get(0);
        assertTrue(child.name().contains("ShutdownOnSuccess"));
        assertEquals(21L, child.ownerTid());
        assertEquals(1, child.subtasks().size());
        assertEquals("lookup-cache", child.subtasks().get(0).name());
        assertFalse(child.subtasks().get(0).stack().isEmpty());
    }

    @Test
    void rendersIndentedScopeTree() throws Exception {
        String rendered = StructuredConcurrencyView.render(fixture());

        assertTrue(rendered.contains("Scope StructuredTaskScope$ShutdownOnFailure@1a2b3c"), rendered);
        assertTrue(rendered.contains("fetch-user [tid=21]"), rendered);
        assertTrue(rendered.contains("fetch-order [tid=22]"), rendered);
        // Nested scope is indented deeper than its parent.
        int parentIdx = rendered.indexOf("Scope StructuredTaskScope$ShutdownOnFailure");
        int childIdx = rendered.indexOf("  Scope StructuredTaskScope$ShutdownOnSuccess");
        assertTrue(childIdx > parentIdx, "nested scope should render indented under its parent:\n" + rendered);
        assertTrue(rendered.contains("lookup-cache [tid=31]"), rendered);
    }

    @Test
    void noScopesYieldsMessage() {
        String json = "{\"threadDump\":{\"threadContainers\":["
                + "{\"container\":\"<root>\",\"parent\":null,\"owner\":null,\"threads\":[]}]}}";
        assertTrue(StructuredConcurrencyView.parse(json).isEmpty());
        assertTrue(StructuredConcurrencyView.render(json).contains("No StructuredTaskScope"));
    }
}
