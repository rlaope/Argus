package io.argus.cli.provider.jdk;

import io.argus.cli.model.DeadlockResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdkDeadlockParserTest {

    private static final String NO_DEADLOCK_OUTPUT =
            "\"main\" #1 [12345] prio=5 os_prio=31 cpu=123.45ms elapsed=60.00s tid=0x0001 nid=0x01 runnable\n" +
            "   java.lang.Thread.State: RUNNABLE\n" +
            "\tat java.lang.Thread.sleep(java.base@21/Native Method)\n";

    private static final String DEADLOCK_OUTPUT =
            "\"Thread-1\" #11 [23456] prio=5 os_prio=31 cpu=10.00ms elapsed=5.00s tid=0x0002 nid=0x02\n" +
            "   java.lang.Thread.State: BLOCKED (on object monitor)\n" +
            "\tat com.example.Service.transfer(Service.java:42)\n" +
            "\t- waiting to lock <0x00000007ac6e8f70> (a java.lang.Object)\n" +
            "\t- locked <0x00000007ac6e8f60> (a java.lang.Object)\n" +
            "\n" +
            "\"Thread-2\" #12 [23457] prio=5 os_prio=31 cpu=10.00ms elapsed=5.00s tid=0x0003 nid=0x03\n" +
            "   java.lang.Thread.State: BLOCKED (on object monitor)\n" +
            "\tat com.example.Service.withdraw(Service.java:67)\n" +
            "\t- waiting to lock <0x00000007ac6e8f60> (a java.lang.Object)\n" +
            "\t- locked <0x00000007ac6e8f70> (a java.lang.Object)\n" +
            "\n" +
            "Found one Java-level deadlock:\n" +
            "=============================\n" +
            "\"Thread-1\":\n" +
            "  waiting to lock monitor 0x00007f89a8003f80 (object 0x00000007ac6e8f70, a java.lang.Object),\n" +
            "  which is held by \"Thread-2\"\n" +
            "\n" +
            "\"Thread-2\":\n" +
            "  waiting to lock monitor 0x00007f89a8003f90 (object 0x00000007ac6e8f60, a java.lang.Object),\n" +
            "  which is held by \"Thread-1\"\n" +
            "\n" +
            "Java stack information for the threads listed above:\n" +
            "===================================================\n" +
            "\"Thread-1\":\n" +
            "\tat com.example.Service.transfer(Service.java:42)\n" +
            "\t- waiting to lock <0x00000007ac6e8f70> (a java.lang.Object)\n" +
            "\t- locked <0x00000007ac6e8f60> (a java.lang.Object)\n" +
            "\n" +
            "\"Thread-2\":\n" +
            "\tat com.example.Service.withdraw(Service.java:67)\n" +
            "\t- waiting to lock <0x00000007ac6e8f60> (a java.lang.Object)\n" +
            "\t- locked <0x00000007ac6e8f70> (a java.lang.Object)\n" +
            "\n" +
            "Found 1 deadlock.\n";

    @Test
    void parseOutput_noDeadlock() {
        DeadlockResult r = JdkDeadlockProvider.parseOutput(NO_DEADLOCK_OUTPUT);
        assertEquals(0, r.deadlockCount());
        assertTrue(r.chains().isEmpty());
    }

    @Test
    void parseOutput_nullInput() {
        DeadlockResult r = JdkDeadlockProvider.parseOutput(null);
        assertEquals(0, r.deadlockCount());
        assertTrue(r.chains().isEmpty());
    }

    @Test
    void parseOutput_emptyInput() {
        DeadlockResult r = JdkDeadlockProvider.parseOutput("");
        assertEquals(0, r.deadlockCount());
        assertTrue(r.chains().isEmpty());
    }

    @Test
    void parseOutput_detectsDeadlock() {
        DeadlockResult r = JdkDeadlockProvider.parseOutput(DEADLOCK_OUTPUT);
        assertEquals(1, r.deadlockCount());
        assertEquals(1, r.chains().size());
    }

    @Test
    void parseOutput_chainHasTwoThreads() {
        DeadlockResult r = JdkDeadlockProvider.parseOutput(DEADLOCK_OUTPUT);
        DeadlockResult.DeadlockChain chain = r.chains().get(0);
        assertEquals(2, chain.threads().size());
    }

    @Test
    void parseOutput_firstThreadParsedCorrectly() {
        DeadlockResult r = JdkDeadlockProvider.parseOutput(DEADLOCK_OUTPUT);
        DeadlockResult.DeadlockThread t1 = r.chains().get(0).threads().get(0);
        assertEquals("Thread-1", t1.name());
        assertEquals("BLOCKED", t1.state());
        assertFalse(t1.waitingLock().isEmpty());
        assertEquals("java.lang.Object", t1.waitingLockClass());
        assertEquals("com.example.Service.transfer(Service.java:42)", t1.stackTop());
    }

    @Test
    void parseOutput_secondThreadParsedCorrectly() {
        DeadlockResult r = JdkDeadlockProvider.parseOutput(DEADLOCK_OUTPUT);
        DeadlockResult.DeadlockThread t2 = r.chains().get(0).threads().get(1);
        assertEquals("Thread-2", t2.name());
        assertEquals("BLOCKED", t2.state());
        assertFalse(t2.waitingLock().isEmpty());
        assertEquals("java.lang.Object", t2.waitingLockClass());
        assertEquals("com.example.Service.withdraw(Service.java:67)", t2.stackTop());
    }

    @Test
    void parseOutput_threadHeldLocksParsed() {
        DeadlockResult r = JdkDeadlockProvider.parseOutput(DEADLOCK_OUTPUT);
        DeadlockResult.DeadlockThread t1 = r.chains().get(0).threads().get(0);
        assertFalse(t1.heldLock().isEmpty());
        assertEquals("java.lang.Object", t1.heldLockClass());
    }
}
