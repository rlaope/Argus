package io.argus.cli.provider.jdk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AsProfProviderArgvTest {

    private static List<String> argv(AsProfOptions opts) {
        List<String> cmd = new ArrayList<>();
        AsProfProvider.buildExtraArgs(cmd, opts);
        return cmd;
    }

    @Test
    void nullOpts_emitsNothing() {
        List<String> cmd = new ArrayList<>();
        AsProfProvider.buildExtraArgs(cmd, null);
        assertTrue(cmd.isEmpty());
    }

    @Test
    void defaults_emitsNothing() {
        assertTrue(argv(AsProfOptions.defaults()).isEmpty());
    }

    @Test
    void interval_emitsShortFlagPair() {
        assertEquals(Arrays.asList("-i", "1ms"),
                argv(AsProfOptions.builder().interval("1ms").build()));
    }

    @Test
    void jstackdepth_emitsShortFlagPair() {
        assertEquals(Arrays.asList("-j", "64"),
                argv(AsProfOptions.builder().jstackdepth(64).build()));
    }

    @Test
    void cstack_emitsLongFlagPair() {
        assertEquals(Arrays.asList("--cstack", "dwarf"),
                argv(AsProfOptions.builder().cstack("dwarf").build()));
    }

    @Test
    void perThread_emitsLoneShortFlag() {
        assertEquals(List.of("-t"),
                argv(AsProfOptions.builder().perThread(true).build()));
    }

    @Test
    void allUser_emitsLoneLongFlag() {
        assertEquals(List.of("--alluser"),
                argv(AsProfOptions.builder().allUser(true).build()));
    }

    @Test
    void allKernel_emitsLoneLongFlag() {
        assertEquals(List.of("--allkernel"),
                argv(AsProfOptions.builder().allKernel(true).build()));
    }

    @Test
    void allocBytes_emitsLongFlagPair() {
        assertEquals(Arrays.asList("--alloc", "512k"),
                argv(AsProfOptions.builder().allocBytes("512k").build()));
    }

    @Test
    void live_emitsLoneLongFlag() {
        assertEquals(List.of("--live"),
                argv(AsProfOptions.builder().live(true).build()));
    }

    @Test
    void include_emitsRepeatedShortFlagPair() {
        assertEquals(Arrays.asList("-I", "com.example.*", "-I", "org.foo.*"),
                argv(AsProfOptions.builder()
                        .addInclude("com.example.*")
                        .addInclude("org.foo.*")
                        .build()));
    }

    @Test
    void exclude_emitsRepeatedShortFlagPair() {
        assertEquals(Arrays.asList("-X", "java.*", "-X", "jdk.*"),
                argv(AsProfOptions.builder()
                        .addExclude("java.*")
                        .addExclude("jdk.*")
                        .build()));
    }

    @Test
    void reverse_emitsLoneLongFlag() {
        assertEquals(List.of("--reverse"),
                argv(AsProfOptions.builder().reverse(true).build()));
    }

    @Test
    void minwidth_emitsLongFlagPair() {
        assertEquals(Arrays.asList("--minwidth", "0.5"),
                argv(AsProfOptions.builder().minwidth("0.5").build()));
    }

    @Test
    void sched_emitsLoneLongFlag() {
        assertEquals(List.of("--sched"),
                argv(AsProfOptions.builder().sched(true).build()));
    }

    @Test
    void clock_emitsLongFlagPair() {
        assertEquals(Arrays.asList("--clock", "monotonic"),
                argv(AsProfOptions.builder().clock("monotonic").build()));
    }

    @Test
    void signal_emitsLongFlagPair() {
        assertEquals(Arrays.asList("--signal", "42"),
                argv(AsProfOptions.builder().signal("42").build()));
    }

    @Test
    void procInterval_emitsLongFlagPair() {
        assertEquals(Arrays.asList("--proc", "30s"),
                argv(AsProfOptions.builder().procInterval("30s").build()));
    }

    @Test
    void nofree_emitsLoneLongFlag() {
        assertEquals(List.of("--nofree"),
                argv(AsProfOptions.builder().nofree(true).build()));
    }

    @Test
    void ttsp_emitsLoneLongFlag() {
        assertEquals(List.of("--ttsp"),
                argv(AsProfOptions.builder().ttsp(true).build()));
    }

    @Test
    void beginFunction_emitsLongFlagPair() {
        assertEquals(Arrays.asList("--begin", "com.example.MyClass.handle"),
                argv(AsProfOptions.builder().beginFunction("com.example.MyClass.handle").build()));
    }

    @Test
    void endFunction_emitsLongFlagPair() {
        assertEquals(Arrays.asList("--end", "com.example.MyClass.done"),
                argv(AsProfOptions.builder().endFunction("com.example.MyClass.done").build()));
    }

    @Test
    void allFlagsSet_emitsFullArgvInDeclarationOrder() {
        AsProfOptions opts = AsProfOptions.builder()
                .interval("1ms")
                .jstackdepth(64)
                .cstack("dwarf")
                .perThread(true)
                .allUser(true)
                .allocBytes("512k")
                .live(true)
                .addInclude("com.example.*")
                .addExclude("java.*")
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

        List<String> expected = Arrays.asList(
                "-i", "1ms",
                "-j", "64",
                "--cstack", "dwarf",
                "-t",
                "--alluser",
                "--alloc", "512k",
                "--live",
                "-I", "com.example.*",
                "-X", "java.*",
                "--reverse",
                "--minwidth", "0.5",
                "--sched",
                "--clock", "monotonic",
                "--signal", "42",
                "--proc", "30s",
                "--nofree",
                "--ttsp",
                "--begin", "com.example.MyClass.handle",
                "--end", "com.example.MyClass.done");

        assertEquals(expected, argv(opts));
    }
}
