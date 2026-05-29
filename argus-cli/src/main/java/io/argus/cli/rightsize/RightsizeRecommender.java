package io.argus.cli.rightsize;

/**
 * Pure-function core for JVM right-sizing recommendations (FinOps).
 *
 * <p>This class deliberately has <b>no JVM-attach dependency</b>: it operates only on a
 * value object ({@link Observation}) so the headroom/recommendation math is unit-testable
 * without a live JVM. The CLI command is responsible for collecting the observation from a
 * running process; the aggregator can build one from fleet metrics.
 *
 * <p>This module targets Java 11 source level (the CLI diagnoses JVM 11+), so the value
 * objects below are plain final classes with accessors rather than records.
 *
 * <p>Honesty rails (see the W3 acceptance criteria):
 * <ul>
 *   <li>The recommended {@code -Xmx} is <b>never</b> below the observed post-GC live-set floor.</li>
 *   <li>Every recommended number carries its inputs and the safety factor that produced it.</li>
 *   <li>An OOMKill-risk flag fires when the container limit leaves no native headroom
 *       (limit &asymp; {@code -Xmx}, i.e. metaspace + threads + code-cache + direct buffers
 *       would have nowhere to live).</li>
 *   <li>When the observation window is too short to be defensible, no recommendation is
 *       produced — the caller is told to keep observing.</li>
 * </ul>
 */
public final class RightsizeRecommender {

    /**
     * Minimum observation window. Below this, the post-GC live-set floor and the
     * allocation/promotion signal are not yet trustworthy enough to right-size on.
     */
    public static final long MIN_OBSERVATION_MS = 60_000L;

    /**
     * Minimum number of observed GC cycles. The live-set floor is read off the heap
     * <i>after</i> collection; with too few collections we have not seen a real floor.
     */
    public static final long MIN_GC_CYCLES = 2L;

    /**
     * Headroom multiplier applied to the post-GC live-set floor to derive {@code -Xmx}.
     * 1.5x leaves room for transient allocation spikes between collections without
     * forcing the collector to run back-to-back. This is the published safety factor.
     */
    public static final double XMX_SAFETY_FACTOR = 1.5;

    /**
     * Extra native headroom (beyond metaspace + direct buffers + code cache + threads)
     * reserved on top of {@code -Xmx} when sizing the container memory limit. Covers
     * GC structures, JIT scratch, mmap'd files and the kernel page cache the JVM touches.
     */
    public static final double NATIVE_HEADROOM_FACTOR = 1.25;

    /** Per-thread native stack reservation used when sizing the container limit. */
    public static final long THREAD_STACK_BYTES = 1024L * 1024L; // 1 MiB

    /**
     * If the evaluated container limit is within this fraction of {@code -Xmx}, there is
     * effectively no native headroom and the deployment is at risk of an OOMKill that the
     * JVM's own heap accounting will never see coming. Fires the OOMKill-risk flag.
     */
    public static final double OOMKILL_HEADROOM_THRESHOLD = 0.10;

    private RightsizeRecommender() {}

    /**
     * OOMKill-risk verdict. The check is only meaningful against a real/observed container
     * limit; against Argus's own recommended limit it would be circular, so the third state
     * is {@link #UNKNOWN} rather than a false {@code OK}.
     */
    public enum OomKillRisk {
        /** An observed limit leaves less than the native-headroom threshold above {@code -Xmx}. */
        AT_RISK,
        /** An observed limit leaves adequate native headroom above {@code -Xmx}. */
        OK,
        /** No observed limit was supplied; risk cannot be evaluated honestly. */
        UNKNOWN
    }

    /**
     * Produces a right-sizing recommendation from an {@link Observation}. Never throws on
     * defensible-but-low data; instead returns a refusal when the window is too short.
     */
    public static Recommendation recommend(Observation o) {
        if (o.observationWindowMs() < MIN_OBSERVATION_MS
                || o.observedGcCycles() < MIN_GC_CYCLES) {
            String reason = String.format(
                    "observed %ds / %d GC cycles; need >= %ds and >= %d cycles for a defensible recommendation",
                    o.observationWindowMs() / 1000L, o.observedGcCycles(),
                    MIN_OBSERVATION_MS / 1000L, MIN_GC_CYCLES);
            return Recommendation.refused(reason, o);
        }

        // The live-set floor is the hard lower bound on -Xmx. We never recommend below it.
        long floor = Math.max(0L, o.postGcLiveSetBytes());

        // Candidate -Xmx = floor * safety factor. We size off the floor (not the HWM, which
        // an allocation burst can inflate); the safety factor provides the burst headroom.
        // The Math.max guarantees the hard floor even against rounding.
        long xmx = Math.max(floor, Math.round(floor * XMX_SAFETY_FACTOR));

        // -Xms: set equal to -Xmx for server workloads to avoid heap-resize churn.
        long xms = xmx;

        // Native footprint that lives OUTSIDE the heap and must fit under the container limit.
        long threadStacks = (long) o.threadCount() * THREAD_STACK_BYTES;
        long nativeFootprint = o.metaspaceBytes() + o.directBufferBytes()
                + o.codeCacheBytes() + threadStacks;

        // Container request = heap + native footprint (steady-state working set).
        long containerRequest = xmx + nativeFootprint;

        // Container limit = (heap + native footprint) * native headroom factor, giving the
        // process room for GC scratch / page cache before the kernel OOMKills it.
        long containerLimit = Math.round((xmx + nativeFootprint) * NATIVE_HEADROOM_FACTOR);

        // OOMKill risk: only meaningful against a REAL/observed container limit (the config
        // the deployment actually runs against). Comparing against the limit Argus itself
        // recommends would be circular — that limit is `(xmx + native) * 1.25` by construction,
        // so it always clears the headroom threshold and the flag could never fire. When no
        // observed limit is known we report UNKNOWN ("n/a"), never a false all-clear.
        OomKillRisk oomRisk;
        String oomReason;
        if (o.currentContainerLimitBytes() > 0) {
            long observedLimit = o.currentContainerLimitBytes();
            long headroomAboveXmx = observedLimit - xmx;
            // Rounded threshold (not truncated) so the boundary is symmetric, not biased down.
            long threshold = Math.round(xmx * OOMKILL_HEADROOM_THRESHOLD);
            if (headroomAboveXmx <= threshold) {
                oomRisk = OomKillRisk.AT_RISK;
                oomReason = String.format(
                        "observed container limit (%d MiB) leaves only %d MiB above -Xmx (%d MiB) — "
                        + "no room for metaspace/threads/code-cache/direct buffers; raise the limit",
                        observedLimit / (1024 * 1024),
                        Math.max(0L, headroomAboveXmx) / (1024 * 1024),
                        xmx / (1024 * 1024));
            } else {
                oomRisk = OomKillRisk.OK;
                oomReason = null;
            }
        } else {
            // No --limit supplied and no observed container limit: we cannot evaluate OOMKill
            // risk honestly. Tell the operator how to get a real answer instead of a tautology.
            oomRisk = OomKillRisk.UNKNOWN;
            oomReason = "no observed container limit — pass --limit=<size> (or run where the "
                    + "cgroup limit is readable) to evaluate native-headroom OOMKill risk";
        }

        double cpuRequest = cpuRequestCores(o);

        Recommendation r = new Recommendation();
        r.refused = false;
        r.refusalReason = null;
        r.recommendedXmxBytes = xmx;
        r.recommendedXmsBytes = xms;
        r.recommendedContainerRequestBytes = containerRequest;
        r.recommendedContainerLimitBytes = containerLimit;
        r.recommendedCpuRequestCores = cpuRequest;
        r.liveSetFloorBytes = floor;
        r.xmxSafetyFactor = XMX_SAFETY_FACTOR;
        r.oomKillRiskState = oomRisk;
        r.oomKillReason = oomReason;
        r.copyInputs(o);
        return r;
    }

    /**
     * CPU request in cores. Baseline 0.5 core; +0.5 core when the allocation rate is high
     * enough that concurrent GC threads need headroom (&gt; 256 MiB/s sustained), +0.25 core
     * when promotion is heavy (&gt; 64 MiB/s). Rounded to the nearest 0.25 core to match
     * typical Kubernetes request granularity.
     */
    static double cpuRequestCores(Observation o) {
        double cores = 0.5;
        if (o.allocationRateBytesPerSec() > 256.0 * 1024 * 1024) {
            cores += 0.5;
        }
        if (o.promotionRateBytesPerSec() > 64.0 * 1024 * 1024) {
            cores += 0.25;
        }
        return Math.round(cores * 4.0) / 4.0;
    }

    /**
     * Observed inputs for one JVM. All byte fields are absolute bytes; rates are bytes/sec.
     */
    public static final class Observation {
        private final long heapHighWaterMarkBytes;
        private final long postGcLiveSetBytes;
        private final long currentXmxBytes;
        private final long currentContainerLimitBytes;
        private final double allocationRateBytesPerSec;
        private final double promotionRateBytesPerSec;
        private final long metaspaceBytes;
        private final long directBufferBytes;
        private final long codeCacheBytes;
        private final int threadCount;
        private final long observationWindowMs;
        private final long observedGcCycles;

        /**
         * @param heapHighWaterMarkBytes peak observed heap used (the HWM)
         * @param postGcLiveSetBytes     heap used immediately after the most recent old/full GC
         *                               — the live-set <i>floor</i>; the recommendation never
         *                               drops {@code -Xmx} below this
         * @param currentXmxBytes        the JVM's current {@code -Xmx}; 0 if unknown
         * @param currentContainerLimitBytes current container memory limit; 0 if unknown
         * @param allocationRateBytesPerSec observed allocation rate (CPU-request input)
         * @param promotionRateBytesPerSec  observed promotion rate (CPU-request input)
         * @param metaspaceBytes         committed metaspace footprint
         * @param directBufferBytes      direct/native buffer footprint
         * @param codeCacheBytes         code cache footprint
         * @param threadCount            live thread count (each reserves a native stack)
         * @param observationWindowMs    how long we observed; gates the short-window refusal
         * @param observedGcCycles       GC cycles seen in the window; gates the refusal
         */
        public Observation(long heapHighWaterMarkBytes, long postGcLiveSetBytes,
                            long currentXmxBytes, long currentContainerLimitBytes,
                            double allocationRateBytesPerSec, double promotionRateBytesPerSec,
                            long metaspaceBytes, long directBufferBytes, long codeCacheBytes,
                            int threadCount, long observationWindowMs, long observedGcCycles) {
            this.heapHighWaterMarkBytes = heapHighWaterMarkBytes;
            this.postGcLiveSetBytes = postGcLiveSetBytes;
            this.currentXmxBytes = currentXmxBytes;
            this.currentContainerLimitBytes = currentContainerLimitBytes;
            this.allocationRateBytesPerSec = allocationRateBytesPerSec;
            this.promotionRateBytesPerSec = promotionRateBytesPerSec;
            this.metaspaceBytes = metaspaceBytes;
            this.directBufferBytes = directBufferBytes;
            this.codeCacheBytes = codeCacheBytes;
            this.threadCount = threadCount;
            this.observationWindowMs = observationWindowMs;
            this.observedGcCycles = observedGcCycles;
        }

        public long heapHighWaterMarkBytes() { return heapHighWaterMarkBytes; }
        public long postGcLiveSetBytes() { return postGcLiveSetBytes; }
        public long currentXmxBytes() { return currentXmxBytes; }
        public long currentContainerLimitBytes() { return currentContainerLimitBytes; }
        public double allocationRateBytesPerSec() { return allocationRateBytesPerSec; }
        public double promotionRateBytesPerSec() { return promotionRateBytesPerSec; }
        public long metaspaceBytes() { return metaspaceBytes; }
        public long directBufferBytes() { return directBufferBytes; }
        public long codeCacheBytes() { return codeCacheBytes; }
        public int threadCount() { return threadCount; }
        public long observationWindowMs() { return observationWindowMs; }
        public long observedGcCycles() { return observedGcCycles; }
    }

    /**
     * A right-sizing recommendation. When {@link #refused()} is true the observation window
     * was too short; the byte fields are 0 and {@link #refusalReason()} explains why. Inputs
     * are always echoed so the output is never a black box.
     */
    public static final class Recommendation {
        private boolean refused;
        private String refusalReason;
        private long recommendedXmxBytes;
        private long recommendedXmsBytes;
        private long recommendedContainerRequestBytes;
        private long recommendedContainerLimitBytes;
        private double recommendedCpuRequestCores;
        private long liveSetFloorBytes;
        private double xmxSafetyFactor;
        private OomKillRisk oomKillRiskState = OomKillRisk.UNKNOWN;
        private String oomKillReason;
        // Echo of inputs.
        private long inputHeapHighWaterMarkBytes;
        private long inputPostGcLiveSetBytes;
        private long inputCurrentXmxBytes;
        private long inputCurrentContainerLimitBytes;
        private long inputMetaspaceBytes;
        private long inputDirectBufferBytes;
        private long inputCodeCacheBytes;
        private int inputThreadCount;
        private double inputAllocationRateBytesPerSec;
        private double inputPromotionRateBytesPerSec;

        private void copyInputs(Observation o) {
            inputHeapHighWaterMarkBytes = o.heapHighWaterMarkBytes();
            inputPostGcLiveSetBytes = o.postGcLiveSetBytes();
            inputCurrentXmxBytes = o.currentXmxBytes();
            inputCurrentContainerLimitBytes = o.currentContainerLimitBytes();
            inputMetaspaceBytes = o.metaspaceBytes();
            inputDirectBufferBytes = o.directBufferBytes();
            inputCodeCacheBytes = o.codeCacheBytes();
            inputThreadCount = o.threadCount();
            inputAllocationRateBytesPerSec = o.allocationRateBytesPerSec();
            inputPromotionRateBytesPerSec = o.promotionRateBytesPerSec();
        }

        static Recommendation refused(String reason, Observation o) {
            Recommendation r = new Recommendation();
            r.refused = true;
            r.refusalReason = reason;
            r.xmxSafetyFactor = XMX_SAFETY_FACTOR;
            r.copyInputs(o);
            return r;
        }

        public boolean refused() { return refused; }
        public String refusalReason() { return refusalReason; }
        public long recommendedXmxBytes() { return recommendedXmxBytes; }
        public long recommendedXmsBytes() { return recommendedXmsBytes; }
        public long recommendedContainerRequestBytes() { return recommendedContainerRequestBytes; }
        public long recommendedContainerLimitBytes() { return recommendedContainerLimitBytes; }
        public double recommendedCpuRequestCores() { return recommendedCpuRequestCores; }
        public long liveSetFloorBytes() { return liveSetFloorBytes; }
        public double xmxSafetyFactor() { return xmxSafetyFactor; }
        /** Three-state OOMKill verdict. {@link OomKillRisk#UNKNOWN} when no observed limit. */
        public OomKillRisk oomKillRiskState() { return oomKillRiskState; }
        /** True only when an observed limit puts the deployment genuinely at risk. */
        public boolean oomKillRisk() { return oomKillRiskState == OomKillRisk.AT_RISK; }
        public String oomKillReason() { return oomKillReason; }
        public long inputHeapHighWaterMarkBytes() { return inputHeapHighWaterMarkBytes; }
        public long inputPostGcLiveSetBytes() { return inputPostGcLiveSetBytes; }
        public long inputCurrentXmxBytes() { return inputCurrentXmxBytes; }
        public long inputCurrentContainerLimitBytes() { return inputCurrentContainerLimitBytes; }
        public long inputMetaspaceBytes() { return inputMetaspaceBytes; }
        public long inputDirectBufferBytes() { return inputDirectBufferBytes; }
        public long inputCodeCacheBytes() { return inputCodeCacheBytes; }
        public int inputThreadCount() { return inputThreadCount; }
        public double inputAllocationRateBytesPerSec() { return inputAllocationRateBytesPerSec; }
        public double inputPromotionRateBytesPerSec() { return inputPromotionRateBytesPerSec; }
    }
}
