package io.argus.server.analysis;

import io.argus.server.analysis.AnomalyDetector.AnomalyType;
import io.argus.server.analysis.AnomalyDetector.CaptureRecommendation;

import java.time.Duration;
import java.time.Instant;

/**
 * Turns an {@link AnomalyDetector} {@link CaptureRecommendation} into a concrete,
 * reason-tagged {@link ProfileCaptureRequest}.
 *
 * <p>This is the W4 anomaly-triggered-capture binding: when the detector reports a
 * capture recommendation (an anomaly fired while continuous profiling is on), this
 * binder produces a short profile-capture request whose {@code reason} carries the
 * triggering anomaly's reason verbatim, so the captured artifact is attributable to
 * the anomaly that motivated it.
 *
 * <p>The binder is intentionally side-effect free and does not drive a live agent
 * capture loop: it models the capture <em>request</em> (and, via
 * {@link ProfileCaptureRequest#completed}, the resulting <em>artifact</em>) so the
 * binding is unit testable without a JVM/agent attach. Wiring the request to an
 * actual async-profiler capture on the agent side is the integration follow-up.
 *
 * <p>The async-profiler event type is chosen from the triggering signal: a CPU
 * anomaly requests a {@code "cpu"} capture; an ALLOC anomaly requests an
 * {@code "alloc"} capture. Capture duration defaults to {@link #DEFAULT_DURATION}.
 */
public final class AnomalyCaptureBinder {

    /** Default short-capture duration when none is supplied. */
    public static final Duration DEFAULT_DURATION = Duration.ofSeconds(30);

    private final Duration captureDuration;

    /** Creates a binder using {@link #DEFAULT_DURATION}. */
    public AnomalyCaptureBinder() {
        this(DEFAULT_DURATION);
    }

    /**
     * Creates a binder with an explicit capture duration.
     *
     * @param captureDuration how long the requested capture should run (non-null, positive)
     */
    public AnomalyCaptureBinder(Duration captureDuration) {
        if (captureDuration == null || captureDuration.isZero() || captureDuration.isNegative()) {
            throw new IllegalArgumentException("captureDuration must be positive");
        }
        this.captureDuration = captureDuration;
    }

    /**
     * Builds a reason-tagged capture request for the given recommendation.
     *
     * @param recommendation the active capture recommendation (must be non-null)
     * @return a capture request carrying the recommendation's triggering reason
     * @throws IllegalArgumentException if {@code recommendation} is null
     */
    public ProfileCaptureRequest bind(CaptureRecommendation recommendation) {
        if (recommendation == null) {
            throw new IllegalArgumentException("recommendation must not be null");
        }
        String eventType = eventTypeFor(recommendation.triggerType());
        return new ProfileCaptureRequest(
                recommendation.timestamp(),
                recommendation.triggerType(),
                eventType,
                captureDuration,
                recommendation.reason());
    }

    /**
     * Builds a request from the detector's currently-active recommendation, if any.
     *
     * @param detector the anomaly detector to read from (non-null)
     * @return the bound request, or {@code null} if no capture is currently recommended
     */
    public ProfileCaptureRequest bindActive(AnomalyDetector detector) {
        if (detector == null) {
            throw new IllegalArgumentException("detector must not be null");
        }
        CaptureRecommendation rec = detector.captureRecommendation();
        return rec == null ? null : bind(rec);
    }

    private static String eventTypeFor(AnomalyType type) {
        // CPU anomaly -> sample CPU stacks; ALLOC anomaly -> sample allocation stacks.
        return type == AnomalyType.ALLOC ? "alloc" : "cpu";
    }

    /**
     * A short profile-capture request produced from an anomaly capture
     * recommendation. The {@code reason} is the triggering anomaly's reason,
     * preserved verbatim so the eventual artifact is attributable to it.
     */
    public static final class ProfileCaptureRequest {
        private final Instant requestedAt;
        private final AnomalyType triggerType;
        private final String eventType;
        private final Duration duration;
        private final String reason;

        public ProfileCaptureRequest(Instant requestedAt, AnomalyType triggerType,
                                     String eventType, Duration duration, String reason) {
            this.requestedAt = requestedAt;
            this.triggerType = triggerType;
            this.eventType = eventType;
            this.duration = duration;
            this.reason = reason;
        }

        public Instant requestedAt() { return requestedAt; }
        public AnomalyType triggerType() { return triggerType; }
        public String eventType() { return eventType; }
        public Duration duration() { return duration; }
        public String reason() { return reason; }

        /**
         * Records the artifact that results from fulfilling this request, carrying
         * the same triggering reason forward onto the captured artifact.
         *
         * @param artifactRef opaque reference to the captured profile (path, id, …)
         * @return a reason-tagged capture result
         */
        public ProfileCaptureResult completed(String artifactRef) {
            return new ProfileCaptureResult(this, artifactRef, reason);
        }
    }

    /**
     * The artifact produced by fulfilling a {@link ProfileCaptureRequest}. Carries
     * the triggering {@code reason} so a stored capture remains attributable to the
     * anomaly that motivated it.
     */
    public static final class ProfileCaptureResult {
        private final ProfileCaptureRequest request;
        private final String artifactRef;
        private final String reason;

        public ProfileCaptureResult(ProfileCaptureRequest request, String artifactRef, String reason) {
            this.request = request;
            this.artifactRef = artifactRef;
            this.reason = reason;
        }

        public ProfileCaptureRequest request() { return request; }
        public String artifactRef() { return artifactRef; }
        public String reason() { return reason; }
    }
}
