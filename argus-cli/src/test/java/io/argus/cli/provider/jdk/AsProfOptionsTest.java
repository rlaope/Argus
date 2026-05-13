package io.argus.cli.provider.jdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AsProfOptionsTest {

    @Test
    void defaults_areNoOverride() {
        AsProfOptions o = AsProfOptions.defaults();
        assertNull(o.interval);
        assertNull(o.jstackdepth);
        assertNull(o.cstack);
        assertNull(o.allocBytes);
        assertNull(o.outputFormat);
        assertNull(o.minwidth);
        assertNull(o.clock);
        assertNull(o.signal);
        assertNull(o.procInterval);
        assertNull(o.beginFunction);
        assertNull(o.endFunction);

        assertFalse(o.perThread);
        assertFalse(o.allUser);
        assertFalse(o.allKernel);
        assertFalse(o.live);
        assertFalse(o.reverse);
        assertFalse(o.sched);
        assertFalse(o.nofree);
        assertFalse(o.ttsp);

        assertTrue(o.include.isEmpty());
        assertTrue(o.exclude.isEmpty());
    }

    @Test
    void builderSetters_landOnFields() {
        AsProfOptions o = AsProfOptions.builder()
                .interval("1ms")
                .jstackdepth(64)
                .cstack("dwarf")
                .perThread(true)
                .allUser(true)
                .allocBytes("512k")
                .live(true)
                .outputFormat("jfr")
                .reverse(true)
                .minwidth("0.5")
                .sched(true)
                .clock("monotonic")
                .signal("42")
                .procInterval("30s")
                .nofree(true)
                .ttsp(true)
                .beginFunction("com.example.MyClass.handle")
                .endFunction("com.example.MyClass.done")
                .build();

        assertEquals("1ms", o.interval);
        assertEquals(64, o.jstackdepth);
        assertEquals("dwarf", o.cstack);
        assertTrue(o.perThread);
        assertTrue(o.allUser);
        assertEquals("512k", o.allocBytes);
        assertTrue(o.live);
        assertEquals("jfr", o.outputFormat);
        assertTrue(o.reverse);
        assertEquals("0.5", o.minwidth);
        assertTrue(o.sched);
        assertEquals("monotonic", o.clock);
        assertEquals("42", o.signal);
        assertEquals("30s", o.procInterval);
        assertTrue(o.nofree);
        assertTrue(o.ttsp);
        assertEquals("com.example.MyClass.handle", o.beginFunction);
        assertEquals("com.example.MyClass.done", o.endFunction);
    }

    @Test
    void allKernel_isMutuallyExclusiveFlagFromAllUser_butBothSettableViaBuilder() {
        AsProfOptions o = AsProfOptions.builder().allKernel(true).build();
        assertTrue(o.allKernel);
        assertFalse(o.allUser);
    }

    @Test
    void addInclude_accumulatesMultipleEntries() {
        AsProfOptions o = AsProfOptions.builder()
                .addInclude("com.example.*")
                .addInclude("org.foo.*")
                .build();
        assertEquals(2, o.include.size());
        assertEquals("com.example.*", o.include.get(0));
        assertEquals("org.foo.*", o.include.get(1));
    }

    @Test
    void addExclude_accumulatesMultipleEntries() {
        AsProfOptions o = AsProfOptions.builder()
                .addExclude("java.*")
                .addExclude("jdk.*")
                .addExclude("sun.*")
                .build();
        assertEquals(3, o.exclude.size());
    }

    @Test
    void includeList_isUnmodifiable() {
        AsProfOptions o = AsProfOptions.builder().addInclude("com.x.*").build();
        assertThrows(UnsupportedOperationException.class, () -> o.include.add("late"));
    }

    @Test
    void excludeList_isUnmodifiable() {
        AsProfOptions o = AsProfOptions.builder().addExclude("java.*").build();
        assertThrows(UnsupportedOperationException.class, () -> o.exclude.add("late"));
    }

    @Test
    void builderSnapshot_isIndependentOfPostBuildBuilderMutation() {
        AsProfOptions.Builder b = AsProfOptions.builder().interval("1ms");
        AsProfOptions snapshot = b.build();
        b.interval("999ms");
        assertEquals("1ms", snapshot.interval);
    }
}
