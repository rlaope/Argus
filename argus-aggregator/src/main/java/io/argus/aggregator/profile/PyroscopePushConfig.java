package io.argus.aggregator.profile;

/**
 * Configuration for the optional {@link PyroscopePusher}. The push is OFF unless
 * an ingest endpoint is configured, so the default behaviour is the existing
 * local-only {@link ProfileStore} with zero outbound traffic.
 *
 * <p>The endpoint is read from the system property
 * {@code argus.profile.push.pyroscope.endpoint} (e.g.
 * {@code http://pyroscope:4040}). An optional region label comes from
 * {@code argus.profile.push.pyroscope.region}. When the endpoint is unset, blank,
 * or this config is constructed with a null/blank endpoint, {@link #enabled()}
 * is {@code false} and no push is ever attempted.
 */
public final class PyroscopePushConfig {

    /** System property holding the Pyroscope ingest base URL. */
    public static final String ENDPOINT_PROPERTY = "argus.profile.push.pyroscope.endpoint";

    /** Optional system property holding a {@code region} label value. */
    public static final String REGION_PROPERTY = "argus.profile.push.pyroscope.region";

    private final String endpoint;
    private final String region;
    private final boolean enabled;

    public PyroscopePushConfig(String endpoint, String region) {
        this.endpoint = trimToNull(endpoint);
        this.region = trimToNull(region);
        this.enabled = this.endpoint != null;
    }

    /** Reads the config from system properties. */
    public static PyroscopePushConfig fromSystemProperties() {
        return new PyroscopePushConfig(
                System.getProperty(ENDPOINT_PROPERTY),
                System.getProperty(REGION_PROPERTY));
    }

    /** Ingest base URL (no trailing slash assumed), or null when disabled. */
    public String endpoint() {
        return endpoint;
    }

    /** Optional {@code region} label value, or null when unset. */
    public String region() {
        return region;
    }

    /** True iff an ingest endpoint is configured and push should be attempted. */
    public boolean enabled() {
        return enabled;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
