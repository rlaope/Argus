package io.argus.cli.provider.jdk;

import io.argus.cli.model.GcCauseResult;
import io.argus.cli.provider.GcCauseProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * GcCauseProvider that uses {@code jstat -gccause} to get GC cause info.
 */
public final class JdkGcCauseProvider implements GcCauseProvider {

    private static final long TIMEOUT_SECONDS = 10;

    @Override
    public boolean isAvailable(long pid) {
        return isJstatAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public GcCauseResult getGcCause(long pid) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("jstat", "-gccause", String.valueOf(pid));
            pb.redirectErrorStream(true);
            process = pb.start();
            // Read stdout on a daemon thread so a stuck child can't block the reader thread
            // forever. Bound the wait with a timeout.
            StringBuilder out = new StringBuilder();
            Process p = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                }
            }, "jstat-reader");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return empty();
            }
            reader.join(500);
            return parseOutput(out.toString().trim());
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return empty();
        }
    }

    /**
     * Parses jstat -gccause output.
     * Header: S0  S1  E  O  M  CCS  YGC  YGCT  FGC  FGCT  CGC  CGCT  GCT  LGCC  GCC
     * Or older: S0  S1  E  O  M  CCS  YGC  YGCT  FGC  FGCT  GCT  LGCC  GCC
     */
    static GcCauseResult parseOutput(String output) {
        if (output == null || output.isBlank()) return empty();

        String[] lines = output.split("\n");
        if (lines.length < 2) return empty();

        String headerLine = lines[0].trim();
        String dataLine = lines[lines.length - 1].trim();
        String[] headers = headerLine.split("\\s+");
        String[] values = dataLine.split("\\s+");

        // Find column indices
        int lgccIdx = -1, gccIdx = -1, gctIdx = -1;
        for (int i = 0; i < headers.length; i++) {
            if ("LGCC".equals(headers[i])) lgccIdx = i;
            else if ("GCC".equals(headers[i])) gccIdx = i;
            else if ("GCT".equals(headers[i])) gctIdx = i;
        }

        // LGCC and GCC may contain spaces (e.g. "Allocation Failure")
        // Numeric fields are at known positions 0-10 or 0-12
        double s0 = safeDouble(values, 0);
        double s1 = safeDouble(values, 1);
        double eden = safeDouble(values, 2);
        double old = safeDouble(values, 3);
        double meta = safeDouble(values, 4);
        double ccs = safeDouble(values, 5);
        long ygc = safeLong(values, 6);
        double ygct = safeDouble(values, 7);
        long fgc = safeLong(values, 8);
        double fgct = safeDouble(values, 9);

        double gct;
        String lgcc = "No GC";
        String gcc = "No GC";

        if (gctIdx >= 0 && gctIdx < values.length) {
            gct = safeDouble(values, gctIdx);
        } else {
            gct = safeDouble(values, 10);
        }

        // Extract LGCC and GCC from the tail of the data line.
        // After the last numeric column (GCT) the remainder is "<LGCC> <GCC>" where
        // both values may be multi-word (e.g. "G1 Humongous Allocation").
        // We cannot rely on header byte offsets because jstat pads numeric columns
        // differently from text columns — the byte positions do not match.
        //
        // Strategy:
        //  1. Slice the data line from right after the GCT value to end.
        //  2. If the tail ends with " No GC"  →  LGCC = rest before it, GCC = "No GC"
        //  3. Otherwise split on 2+ consecutive spaces (jstat column separator).
        //     If that yields exactly 2 parts use them; if not, assume LGCC == GCC
        //     (ongoing GC) and fall back to single-word values[] as safety net.
        if (lgccIdx >= 0) {
            // Defensive single-word fallback in case tail extraction fails.
            lgcc = values[lgccIdx];
            if (gccIdx >= 0 && gccIdx < values.length) {
                gcc = values[gccIdx];
            }

            // Locate the GCT value in the data line to find where numeric area ends.
            int effectiveGctIdx = (gctIdx >= 0) ? gctIdx : 10;
            String causeTail = extractCauseTail(dataLine, effectiveGctIdx);

            if (causeTail != null && !causeTail.isEmpty()) {
                // Case 1: GCC is "No GC" (no GC currently running).
                if (causeTail.endsWith(" No GC")) {
                    gcc = "No GC";
                    lgcc = causeTail.substring(0, causeTail.length() - " No GC".length()).trim();
                    if (lgcc.isEmpty()) lgcc = "No GC";
                } else {
                    // Case 2: try splitting on 2+ spaces.
                    String[] parts = causeTail.split("\\s{2,}", 2);
                    if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                        lgcc = parts[0].trim();
                        gcc = parts[1].trim();
                    } else {
                        // Case 3: LGCC == GCC (GC in progress) — whole tail is the cause.
                        lgcc = causeTail.trim();
                        gcc = lgcc;
                    }
                }
            }
        }

        return new GcCauseResult(s0, s1, eden, old, meta, ccs, ygc, ygct, fgc, fgct, gct, lgcc, gcc);
    }

    /**
     * Returns the cause tail — everything after the last numeric token at position
     * {@code gctIdx} in the data line — or {@code null} if it cannot be located.
     *
     * <p>jstat -gccause data lines look like:
     * {@code  0.00  0.00  98.7  57.2  7.0  30312  4.880  0  0.000  0  0.000  4.880  G1 Humongous Allocation No GC}
     * The numeric columns are whitespace-delimited; once we skip past {@code gctIdx}
     * of them we read the rest of the trimmed line as the cause section.</p>
     */
    private static String extractCauseTail(String dataLine, int gctIdx) {
        // Walk through the line token-by-token until we have consumed gctIdx+1 tokens.
        int tokenCount = 0;
        int pos = 0;
        int len = dataLine.length();
        while (pos < len) {
            // Skip leading whitespace.
            while (pos < len && Character.isWhitespace(dataLine.charAt(pos))) pos++;
            if (pos >= len) break;
            // Find end of this token.
            int start = pos;
            while (pos < len && !Character.isWhitespace(dataLine.charAt(pos))) pos++;
            // We have consumed one token [start, pos).
            if (tokenCount == gctIdx) {
                // The tail begins right after this token.
                return dataLine.substring(pos).trim();
            }
            tokenCount++;
        }
        return null;
    }

    private static double safeDouble(String[] arr, int idx) {
        if (idx >= arr.length) return 0;
        return JdkParseUtils.parseDouble(arr[idx]);
    }

    private static long safeLong(String[] arr, int idx) {
        if (idx >= arr.length) return 0;
        return JdkParseUtils.parseLong(arr[idx]);
    }

    private static GcCauseResult empty() {
        return new GcCauseResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "No GC", "No GC");
    }

    private static boolean isJstatAvailable() {
        try {
            Process p = new ProcessBuilder("jstat", "-help")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
