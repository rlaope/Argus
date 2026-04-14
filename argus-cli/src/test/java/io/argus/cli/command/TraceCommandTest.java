package io.argus.cli.command;

import io.argus.cli.command.TraceCommand.CallNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraceCommandTest {

    // -------------------------------------------------------------------------
    // extractMatchingStack
    // -------------------------------------------------------------------------

    private static final String DUMP_WITH_TARGET =
            "\"main\" #1 prio=5 os_prio=0 tid=0x00007f nid=0x1234 runnable\n" +
            "   java.lang.Thread.State: RUNNABLE\n" +
            "        at org.hibernate.internal.SessionImpl.merge(SessionImpl.java:100)\n" +
            "        at com.example.OrderRepository.save(OrderRepository.java:45)\n" +
            "        at com.example.OrderService.createOrder(OrderService.java:22)\n" +
            "        at com.example.Controller.handle(Controller.java:10)\n" +
            "\n" +
            "\"GC task thread\" #2 daemon prio=9 os_prio=0 tid=0x00007f nid=0x5678 runnable\n" +
            "   java.lang.Thread.State: RUNNABLE\n" +
            "        at java.lang.Object.wait(Object.java:1)\n";

    @Test
    void extractMatchingStack_findsTargetFrame() {
        List<String> stack = TraceCommand.extractMatchingStack(
                DUMP_WITH_TARGET, "com.example.OrderService", "createOrder");
        assertNotNull(stack, "Should find matching stack");
        assertFalse(stack.isEmpty());
        // First element should be the target method
        assertTrue(stack.get(0).startsWith("com.example.OrderService.createOrder"),
                "First frame should be the target method");
    }

    @Test
    void extractMatchingStack_includesCalleeFrames() {
        List<String> stack = TraceCommand.extractMatchingStack(
                DUMP_WITH_TARGET, "com.example.OrderService", "createOrder");
        assertNotNull(stack);
        // Should include callee frames (innermost first)
        assertTrue(stack.size() >= 3, "Should include callee frames");
        assertTrue(stack.stream().anyMatch(f -> f.startsWith("com.example.OrderRepository.save")));
        assertTrue(stack.stream().anyMatch(f -> f.startsWith("org.hibernate.internal.SessionImpl.merge")));
    }

    @Test
    void extractMatchingStack_returnsNullWhenNoMatch() {
        List<String> stack = TraceCommand.extractMatchingStack(
                DUMP_WITH_TARGET, "com.example.NonExistent", "missing");
        assertNull(stack, "Should return null when target not found");
    }

    @Test
    void extractMatchingStack_handlesEmptyDump() {
        assertNull(TraceCommand.extractMatchingStack("", "com.example.Foo", "bar"));
        assertNull(TraceCommand.extractMatchingStack(null, "com.example.Foo", "bar"));
    }

    @Test
    void extractMatchingStack_matchesOnlyTargetThread() {
        // GC thread does not contain the target — only main thread does
        List<String> stack = TraceCommand.extractMatchingStack(
                DUMP_WITH_TARGET, "com.example.OrderService", "createOrder");
        assertNotNull(stack);
        // Should not contain Object.wait from GC thread
        assertTrue(stack.stream().noneMatch(f -> f.startsWith("java.lang.Object.wait")));
    }

    // -------------------------------------------------------------------------
    // buildCallTree
    // -------------------------------------------------------------------------

    @Test
    void buildCallTree_singleStack() {
        List<List<String>> stacks = List.of(
                List.of("com.example.OrderService.createOrder(OrderService.java:22)",
                        "com.example.OrderRepository.save(OrderRepository.java:45)")
        );
        CallNode root = TraceCommand.buildCallTree(stacks);
        assertEquals(1, root.children.size());
        CallNode orderServiceNode = root.children.values().iterator().next();
        assertEquals(1, orderServiceNode.hits);
        assertEquals(1, orderServiceNode.children.size());
    }

    @Test
    void buildCallTree_aggregatesRepeatedStacks() {
        List<List<String>> stacks = List.of(
                List.of("com.example.OrderService.createOrder(OrderService.java:22)",
                        "com.example.OrderRepository.save(OrderRepository.java:45)"),
                List.of("com.example.OrderService.createOrder(OrderService.java:22)",
                        "com.example.OrderRepository.save(OrderRepository.java:45)"),
                List.of("com.example.OrderService.createOrder(OrderService.java:22)",
                        "com.example.Validator.validate(Validator.java:10)")
        );
        CallNode root = TraceCommand.buildCallTree(stacks);
        CallNode target = root.children.values().iterator().next();
        assertEquals(3, target.hits, "Root target should have 3 hits");
        // Should have 2 distinct children
        assertEquals(2, target.children.size());
    }

    @Test
    void buildCallTree_emptyStacks() {
        CallNode root = TraceCommand.buildCallTree(List.of());
        assertTrue(root.children.isEmpty());
    }

    // -------------------------------------------------------------------------
    // shortenFrame
    // -------------------------------------------------------------------------

    @Test
    void shortenFrame_shortFrameUnchanged() {
        String frame = "com.example.Foo.bar(Foo.java:10)";
        assertEquals(frame, TraceCommand.shortenFrame(frame, 100));
    }

    @Test
    void shortenFrame_stripsSourceInfo() {
        String frame = "com.example.OrderService.createOrder(OrderService.java:22)";
        String shortened = TraceCommand.shortenFrame(frame, 40);
        assertFalse(shortened.contains("(OrderService.java"), "Should strip source file info");
        assertTrue(shortened.length() <= 40);
    }

    @Test
    void shortenFrame_truncatesWithEllipsis() {
        String frame = "com.example.very.long.package.name.OrderService.createOrder";
        String shortened = TraceCommand.shortenFrame(frame, 20);
        assertTrue(shortened.length() <= 20);
        assertTrue(shortened.startsWith("\u2026"), "Should start with ellipsis when truncated");
    }
}
