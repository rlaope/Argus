package io.argus.aggregator.profile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Configuration for {@link ProfileStore}: where profiles live on disk, how wide
 * each time window is, and how long windows are retained.
 *
 * <p>Defaults follow the W1 continuous-profiling roadmap: a 60s window cadence
 * and a 24h ring retention, stored under {@code ~/.argus/profile-store/}.
 * No external configuration framework is needed — construct via the static
 * factories or the all-args constructor.
 */
public final class ProfileStoreConfig {

    /** Default base directory: {@code ~/.argus/profile-store/}. */
    public static final Path DEFAULT_BASE_DIR =
            Paths.get(System.getProperty("user.home"), ".argus", "profile-store");

    /** Default window width: 60 seconds. */
    public static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);

    /** Default ring retention: 24 hours. */
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private final Path baseDir;
    private final Duration window;
    private final Duration retention;

    public ProfileStoreConfig(Path baseDir, Duration window, Duration retention) {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir must not be null");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.baseDir = baseDir;
        this.window = window;
        this.retention = retention;
    }

    /** Returns the canonical defaults (24h retention, 60s window, {@code ~/.argus/profile-store}). */
    public static ProfileStoreConfig defaults() {
        return new ProfileStoreConfig(DEFAULT_BASE_DIR, DEFAULT_WINDOW, DEFAULT_RETENTION);
    }

    /** Returns defaults rooted at a caller-supplied base directory (handy for tests). */
    public static ProfileStoreConfig at(Path baseDir) {
        return new ProfileStoreConfig(baseDir, DEFAULT_WINDOW, DEFAULT_RETENTION);
    }

    public Path baseDir() {
        return baseDir;
    }

    public Duration window() {
        return window;
    }

    public Duration retention() {
        return retention;
    }

    public long windowMillis() {
        return window.toMillis();
    }

    /** Floors a timestamp to the start of its window (epoch millis). */
    public long windowStartFor(long timestampMillis) {
        long w = windowMillis();
        return (timestampMillis / w) * w;
    }
}
