package io.argus.aggregator.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Disk-backed, append-only, time-windowed store of collapsed-stack profile
 * samples — a Pyroscope-lite local profile store with no external TSDB.
 *
 * <p>Samples are keyed by {@code (podId, service, eventType, windowStart)} where
 * {@code windowStart} is the epoch-millis floor of the sample timestamp to the
 * configured window width (default 60s). Each window is a segment file holding
 * {@code count\tstack} records; appending the same stack across appends or
 * windows is summed by the {@link #merged} read path.
 *
 * <h2>Disk layout</h2>
 * <pre>
 *   &lt;baseDir&gt;/&lt;encodedKey&gt;/&lt;windowStartMillis&gt;.seg
 * </pre>
 * where {@code encodedKey} is {@code enc(podId)+'~'+enc(service)+'~'+enc(eventType)}
 * (each field URL-encoded so it is filesystem-safe). Each segment file is
 * self-describing: line 1 is a header
 * {@code ARGUSPROF1 enc(podId) enc(service) enc(eventType) windowStartMillis};
 * subsequent lines are {@code count\tstack} records appended on every
 * {@link #append}. On construction the store re-indexes by scanning the base
 * directory, so profiles survive a process restart.
 *
 * <h2>Thread-safety</h2>
 * Append/evict/read are guarded so the background scrape loop can call
 * {@link #append} from multiple threads safely. Per-window writes are serialized
 * via a per-key lock; the segment index is a {@link ConcurrentHashMap}.
 */
public final class ProfileStore {

    private static final System.Logger LOG = System.getLogger(ProfileStore.class.getName());

    private static final String MAGIC = "ARGUSPROF1";
    private static final String SEG_SUFFIX = ".seg";
    private static final char KEY_SEP = '~';

    private final ProfileStoreConfig config;
    /** windowKey ("encodedKey/windowStartMillis") -> segment file path. */
    private final Map<String, Path> segments = new ConcurrentHashMap<>();
    /** Per-window write lock so concurrent appends to the same window serialize. */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ProfileStore(ProfileStoreConfig config) {
        this.config = config;
        try {
            Files.createDirectories(config.baseDir());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create profile-store base dir " + config.baseDir(), e);
        }
        reindex();
    }

    public ProfileStoreConfig config() {
        return config;
    }

    // ── Write path ───────────────────────────────────────────────────────────

    /**
     * Appends a set of collapsed stacks to the window covering {@code timestampMillis}.
     *
     * <p>The stacks are summed into the existing window on read; this method only
     * appends new record lines, so it is O(stacks) and never rewrites the file.
     *
     * @param podId            originating pod id (non-null, non-blank)
     * @param service          logical service name (may be empty)
     * @param eventType        async-profiler event ("cpu", "alloc", "lock", …)
     * @param timestampMillis  sample wall-clock time; floored to the window start
     * @param collapsedCounts  {@code stack -> sampleCount} for this capture cycle
     */
    public void append(String podId, String service, String eventType,
                        long timestampMillis, Map<String, Long> collapsedCounts) {
        if (podId == null || podId.isBlank()) {
            throw new IllegalArgumentException("podId must not be blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (collapsedCounts == null || collapsedCounts.isEmpty()) {
            return;
        }
        String svc = service == null ? "" : service;
        long windowStart = config.windowStartFor(timestampMillis);
        String encodedKey = encodeKey(podId, svc, eventType);
        String windowKey = encodedKey + '/' + windowStart;

        ReentrantLock lock = locks.computeIfAbsent(windowKey, k -> new ReentrantLock());
        lock.lock();
        try {
            Path seg = segments.get(windowKey);
            StringBuilder sb = new StringBuilder();
            if (seg == null) {
                Path dir = config.baseDir().resolve(encodedKey);
                Files.createDirectories(dir);
                seg = dir.resolve(windowStart + SEG_SUFFIX);
                if (!Files.exists(seg)) {
                    sb.append(MAGIC).append(' ')
                      .append(enc(podId)).append(' ')
                      .append(enc(svc)).append(' ')
                      .append(enc(eventType)).append(' ')
                      .append(windowStart).append('\n');
                }
                segments.put(windowKey, seg);
            }
            for (Map.Entry<String, Long> e : collapsedCounts.entrySet()) {
                long count = e.getValue() == null ? 0L : e.getValue();
                if (count == 0L) {
                    continue;
                }
                // Records are count<TAB>stack; stacks never contain a tab.
                sb.append(count).append('\t').append(sanitizeStack(e.getKey())).append('\n');
            }
            Files.writeString(seg, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append profile window " + windowKey, e);
        } finally {
            lock.unlock();
        }
    }

    // ── Retention ─────────────────────────────────────────────────────────────

    /** Evicts (deletes) every window whose start is strictly before {@code cutoff}. */
    public void evictOlderThan(Instant cutoff) {
        long cutoffMillis = cutoff.toEpochMilli();
        for (Map.Entry<String, Path> e : segments.entrySet()) {
            long windowStart = windowStartOf(e.getKey());
            if (windowStart < cutoffMillis) {
                ReentrantLock lock = locks.computeIfAbsent(e.getKey(), k -> new ReentrantLock());
                lock.lock();
                try {
                    Files.deleteIfExists(e.getValue());
                    segments.remove(e.getKey());
                } catch (IOException io) {
                    LOG.log(System.Logger.Level.WARNING,
                            "failed to evict profile window " + e.getKey(), io);
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /** Evicts windows older than the configured retention relative to now. */
    public void evictExpired() {
        evictOlderThan(Instant.now().minus(config.retention()));
    }

    // ── Read path (the seam the later query API calls) ─────────────────────────

    /**
     * Merges all windows for {@code (podId, eventType)} whose window-start falls in
     * {@code [from, to)} into a single {@code stack -> totalCount} map, summing
     * counts across windows and across services for that pod.
     *
     * <p>This is the read seam the Phase-2 query API will build a flamegraph from.
     * It is cheap: it streams each matching segment file and sums in memory.
     */
    public Map<String, Long> merged(String podId, String eventType, Instant from, Instant to) {
        long fromMillis = from.toEpochMilli();
        long toMillis = to.toEpochMilli();
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Path> e : segments.entrySet()) {
            long windowStart = windowStartOf(e.getKey());
            if (windowStart < fromMillis || windowStart >= toMillis) {
                continue;
            }
            Header header = readHeader(e.getValue());
            if (header == null) {
                continue;
            }
            if (!header.podId.equals(podId) || !header.eventType.equals(eventType)) {
                continue;
            }
            sumRecords(e.getValue(), out);
        }
        return out;
    }

    // ── Re-index on boot ────────────────────────────────────────────────────────

    /** Scans the base dir and rebuilds the in-memory segment index. */
    private void reindex() {
        segments.clear();
        if (!Files.isDirectory(config.baseDir())) {
            return;
        }
        try (DirectoryStream<Path> keyDirs = Files.newDirectoryStream(config.baseDir())) {
            for (Path keyDir : keyDirs) {
                if (!Files.isDirectory(keyDir)) {
                    continue;
                }
                String encodedKey = keyDir.getFileName().toString();
                try (DirectoryStream<Path> segFiles = Files.newDirectoryStream(keyDir, "*" + SEG_SUFFIX)) {
                    for (Path seg : segFiles) {
                        String fileName = seg.getFileName().toString();
                        String windowStartStr = fileName.substring(0, fileName.length() - SEG_SUFFIX.length());
                        try {
                            Long.parseLong(windowStartStr); // validate
                            segments.put(encodedKey + '/' + windowStartStr, seg);
                        } catch (NumberFormatException ignored) {
                            // not a window segment; skip
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "profile-store reindex failed", e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static long windowStartOf(String windowKey) {
        int slash = windowKey.lastIndexOf('/');
        return Long.parseLong(windowKey.substring(slash + 1));
    }

    private static void sumRecords(Path seg, Map<String, Long> out) {
        try {
            boolean first = true;
            for (String line : Files.readAllLines(seg, StandardCharsets.UTF_8)) {
                if (first) {
                    first = false;
                    continue; // header
                }
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    continue;
                }
                long count;
                try {
                    count = Long.parseLong(line.substring(0, tab));
                } catch (NumberFormatException e) {
                    continue;
                }
                String stack = line.substring(tab + 1);
                out.merge(stack, count, Long::sum);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "failed to read profile segment " + seg, e);
        }
    }

    private static Header readHeader(Path seg) {
        try {
            for (String line : Files.readAllLines(seg, StandardCharsets.UTF_8)) {
                if (line.startsWith(MAGIC + " ")) {
                    String[] parts = line.split(" ");
                    if (parts.length >= 5) {
                        return new Header(dec(parts[1]), dec(parts[2]), dec(parts[3]));
                    }
                }
                return null; // header must be line 1
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "failed to read profile header " + seg, e);
        }
        return null;
    }

    private static String encodeKey(String podId, String service, String eventType) {
        return enc(podId) + KEY_SEP + enc(service) + KEY_SEP + enc(eventType);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** Strips any tab / newline so a stack stays a single record line. */
    private static String sanitizeStack(String stack) {
        if (stack == null) {
            return "";
        }
        if (stack.indexOf('\t') < 0 && stack.indexOf('\n') < 0 && stack.indexOf('\r') < 0) {
            return stack;
        }
        return stack.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Decoded segment header fields. */
    private record Header(String podId, String service, String eventType) {}
}
