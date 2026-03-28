package io.argus.cli.provider.jdk;

import io.argus.cli.model.PoolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdkPoolParserTest {

    private static final String SAMPLE_OUTPUT =
            "\"pool-1-thread-1\" #10 prio=5 os_prio=31\n" +
            "   java.lang.Thread.State: RUNNABLE\n" +
            "\n" +
            "\"pool-1-thread-2\" #11 prio=5 os_prio=31\n" +
            "   java.lang.Thread.State: WAITING (parking)\n" +
            "\n" +
            "\"pool-1-thread-3\" #12 prio=5 os_prio=31\n" +
            "   java.lang.Thread.State: WAITING (parking)\n" +
            "\n" +
            "\"ForkJoinPool.commonPool-worker-1\" #13 prio=5 os_prio=31\n" +
            "   java.lang.Thread.State: TIMED_WAITING (parking)\n" +
            "\n" +
            "\"ForkJoinPool.commonPool-worker-2\" #14 prio=5 os_prio=31\n" +
            "   java.lang.Thread.State: RUNNABLE\n" +
            "\n" +
            "\"Reference Handler\" #2 daemon prio=10 os_prio=31\n" +
            "   java.lang.Thread.State: RUNNABLE\n" +
            "\n" +
            "\"Finalizer\" #3 daemon prio=8 os_prio=31\n" +
            "   java.lang.Thread.State: WAITING (on object monitor)\n";

    @Test
    void parseOutput_totalThreads() {
        PoolResult r = JdkPoolProvider.parseOutput(SAMPLE_OUTPUT);
        assertEquals(7, r.totalThreads());
    }

    @Test
    void parseOutput_poolGrouping() {
        PoolResult r = JdkPoolProvider.parseOutput(SAMPLE_OUTPUT);
        assertTrue(r.totalPools() >= 3); // pool-1, ForkJoinPool.commonPool, (JVM Internal)
    }

    @Test
    void parseOutput_pool1HasThreeThreads() {
        PoolResult r = JdkPoolProvider.parseOutput(SAMPLE_OUTPUT);
        PoolResult.PoolInfo pool1 = r.pools().stream()
                .filter(p -> p.name().equals("pool-1"))
                .findFirst().orElse(null);
        assertNotNull(pool1);
        assertEquals(3, pool1.threadCount());
    }

    @Test
    void parseOutput_pool1StateDistribution() {
        PoolResult r = JdkPoolProvider.parseOutput(SAMPLE_OUTPUT);
        PoolResult.PoolInfo pool1 = r.pools().stream()
                .filter(p -> p.name().equals("pool-1"))
                .findFirst().orElse(null);
        assertNotNull(pool1);
        assertEquals(1, pool1.stateDistribution().getOrDefault("RUNNABLE", 0));
        assertEquals(2, pool1.stateDistribution().getOrDefault("WAITING", 0));
    }

    @Test
    void parseOutput_forkJoinPoolDetected() {
        PoolResult r = JdkPoolProvider.parseOutput(SAMPLE_OUTPUT);
        boolean hasFjp = r.pools().stream()
                .anyMatch(p -> p.name().contains("ForkJoinPool"));
        assertTrue(hasFjp);
    }

    @Test
    void parseOutput_emptyInput() {
        PoolResult r = JdkPoolProvider.parseOutput("");
        assertEquals(0, r.totalThreads());
        assertEquals(0, r.totalPools());
    }

    @Test
    void classifyPool_executorPoolPattern() {
        assertEquals("pool-1", JdkPoolProvider.classifyPool("pool-1-thread-1"));
        assertEquals("pool-2", JdkPoolProvider.classifyPool("pool-2-thread-5"));
    }

    @Test
    void classifyPool_forkJoinCommonPool() {
        assertEquals("ForkJoinPool.commonPool",
                JdkPoolProvider.classifyPool("ForkJoinPool.commonPool-worker-1"));
    }

    @Test
    void classifyPool_jvmInternal() {
        assertEquals("(JVM Internal)", JdkPoolProvider.classifyPool("Reference Handler"));
        assertEquals("(JVM Internal)", JdkPoolProvider.classifyPool("Finalizer"));
    }
}
