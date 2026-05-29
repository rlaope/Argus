package io.argus.cli.rightsize;

import io.argus.cli.rightsize.RightsizeRecommender.Observation;
import io.argus.cli.rightsize.RightsizeRecommender.Recommendation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RightsizeRecommenderTest {

    private static final long MIB = 1024L * 1024L;

    /** Builds a defensible (long-enough window, enough GC cycles) steady-state observation. */
    private static Observation steadyState(long liveSetBytes) {
        return new Observation(
                /* heapHighWaterMark */ liveSetBytes * 2,
                /* postGcLiveSet     */ liveSetBytes,
                /* currentXmx        */ liveSetBytes * 4,
                /* currentLimit      */ liveSetBytes * 5,
                /* allocRate         */ 100.0 * MIB,
                /* promoRate         */ 10.0 * MIB,
                /* metaspace         */ 128 * MIB,
                /* directBuffer      */ 32 * MIB,
                /* codeCache         */ 48 * MIB,
                /* threadCount       */ 64,
                /* observationWindow */ 300_000L,
                /* observedGcCycles  */ 20L);
    }

    @Test
    void xmxLandsWithinSafetyBandAboveFloorAndNeverBelow() {
        long floor = 512 * MIB;
        Recommendation r = RightsizeRecommender.recommend(steadyState(floor));

        assertFalse(r.refused());
        // Hard floor guarantee: -Xmx is never below the observed post-GC live set.
        assertTrue(r.recommendedXmxBytes() >= floor,
                "-Xmx must never be below the live-set floor");
        // Stated band: -Xmx lands at the safety factor above the floor.
        long expected = Math.round(floor * RightsizeRecommender.XMX_SAFETY_FACTOR);
        assertEquals(expected, r.recommendedXmxBytes());
        // It lands within [floor, 2x floor] — comfortably above the floor, not runaway.
        assertTrue(r.recommendedXmxBytes() <= floor * 2);
        assertEquals(RightsizeRecommender.XMX_SAFETY_FACTOR, r.xmxSafetyFactor());
        // -Xms mirrors -Xmx and is never above it.
        assertEquals(r.recommendedXmxBytes(), r.recommendedXmsBytes());
    }

    @Test
    void xmxNeverDropsBelowFloorEvenWhenSafetyFactorWouldRound() {
        // A 1-byte floor: safety factor rounds to >= floor, never below.
        Recommendation r = RightsizeRecommender.recommend(steadyState(1L));
        assertFalse(r.refused());
        assertTrue(r.recommendedXmxBytes() >= 1L);
    }

    @Test
    void containerLimitExceedsXmxByNativeFootprintAndHeadroom() {
        long floor = 512 * MIB;
        Recommendation r = RightsizeRecommender.recommend(steadyState(floor));
        assertFalse(r.refused());
        // Request includes heap + native footprint; limit adds headroom on top.
        assertTrue(r.recommendedContainerRequestBytes() > r.recommendedXmxBytes());
        assertTrue(r.recommendedContainerLimitBytes() >= r.recommendedContainerRequestBytes());
        // Healthy config: native headroom exists, no OOMKill flag.
        assertFalse(r.oomKillRisk());
        assertNull(r.oomKillReason());
    }

    @Test
    void oomKillFlagFiresWhenCurrentLimitApproximatesXmx() {
        // floor=512MiB -> xmx = round(512*1.5) = 768MiB. Operator's CURRENT limit is set
        // at 800MiB — only 32MiB above -Xmx, far under the 10% (76.8MiB) headroom threshold.
        // The JVM can fill its heap and still be OOMKilled for native growth it never sees.
        long floor = 512 * MIB;
        long xmx = Math.round(floor * RightsizeRecommender.XMX_SAFETY_FACTOR); // 768 MiB
        long tightCurrentLimit = xmx + 32 * MIB;
        Observation o = new Observation(
                floor * 2, floor, floor * 2,
                /* currentContainerLimit */ tightCurrentLimit,
                100.0 * MIB, 10.0 * MIB,
                128 * MIB, 32 * MIB, 48 * MIB, 64,
                300_000L, 20L);
        Recommendation r = RightsizeRecommender.recommend(o);
        assertFalse(r.refused());
        assertTrue(r.oomKillRisk(), "OOMKill flag must fire when current limit ≈ -Xmx");
        assertNotNull(r.oomKillReason());
    }

    @Test
    void oomKillFlagDoesNotFireWhenCurrentLimitHasHeadroom() {
        long floor = 512 * MIB;
        long xmx = Math.round(floor * RightsizeRecommender.XMX_SAFETY_FACTOR);
        // A generous current limit, well above -Xmx + native footprint.
        long roomyLimit = xmx * 2;
        Observation o = new Observation(
                floor * 2, floor, floor * 2, roomyLimit,
                100.0 * MIB, 10.0 * MIB,
                128 * MIB, 32 * MIB, 48 * MIB, 64,
                300_000L, 20L);
        Recommendation r = RightsizeRecommender.recommend(o);
        assertFalse(r.oomKillRisk());
        assertNull(r.oomKillReason());
    }

    @Test
    void shortWindowIsRefusedWithReason() {
        Observation tooShort = new Observation(
                256 * MIB, 128 * MIB, 512 * MIB, 1024 * MIB,
                50.0 * MIB, 5.0 * MIB,
                64 * MIB, 16 * MIB, 32 * MIB, 32,
                /* observationWindow */ 5_000L,
                /* observedGcCycles  */ 20L);
        Recommendation r = RightsizeRecommender.recommend(tooShort);
        assertTrue(r.refused());
        assertNotNull(r.refusalReason());
        assertEquals(0, r.recommendedXmxBytes());
        // Inputs are still echoed so the caller can see why.
        assertEquals(128 * MIB, r.inputPostGcLiveSetBytes());
    }

    @Test
    void tooFewGcCyclesIsRefused() {
        Observation fewGc = new Observation(
                256 * MIB, 128 * MIB, 512 * MIB, 1024 * MIB,
                50.0 * MIB, 5.0 * MIB,
                64 * MIB, 16 * MIB, 32 * MIB, 32,
                /* observationWindow */ 300_000L,
                /* observedGcCycles  */ 1L);
        Recommendation r = RightsizeRecommender.recommend(fewGc);
        assertTrue(r.refused());
        assertNotNull(r.refusalReason());
    }

    @Test
    void cpuRequestScalesWithAllocationAndPromotionPressure() {
        // Low pressure -> baseline 0.5 core.
        Observation low = new Observation(
                256 * MIB, 128 * MIB, 0, 0,
                10.0 * MIB, 1.0 * MIB,
                0, 0, 0, 8, 300_000L, 20L);
        assertEquals(0.5, RightsizeRecommender.recommend(low).recommendedCpuRequestCores());

        // High alloc + high promotion -> 0.5 + 0.5 + 0.25 = 1.25 cores.
        Observation high = new Observation(
                256 * MIB, 128 * MIB, 0, 0,
                512.0 * MIB, 128.0 * MIB,
                0, 0, 0, 8, 300_000L, 20L);
        assertEquals(1.25, RightsizeRecommender.recommend(high).recommendedCpuRequestCores());
    }

    @Test
    void inputsAreEchoedInTheRecommendation() {
        long floor = 300 * MIB;
        Observation o = steadyState(floor);
        Recommendation r = RightsizeRecommender.recommend(o);
        assertEquals(o.heapHighWaterMarkBytes(), r.inputHeapHighWaterMarkBytes());
        assertEquals(o.postGcLiveSetBytes(), r.inputPostGcLiveSetBytes());
        assertEquals(o.metaspaceBytes(), r.inputMetaspaceBytes());
        assertEquals(o.directBufferBytes(), r.inputDirectBufferBytes());
        assertEquals(o.codeCacheBytes(), r.inputCodeCacheBytes());
        assertEquals(o.threadCount(), r.inputThreadCount());
        assertEquals(floor, r.liveSetFloorBytes());
    }
}
