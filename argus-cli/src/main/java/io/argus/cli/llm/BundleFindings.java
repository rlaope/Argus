package io.argus.cli.llm;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.Severity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reads diagnostic findings back out of an offline {@code snapshot} bundle
 * (the tar.gz produced by {@code argus snapshot}).
 *
 * <p>The bundle stores findings as plain text in {@code doctor.txt} using the
 * format written by the snapshot command:
 * <pre>
 * [SEVERITY] category: title
 *   detail line
 *   -&gt; recommendation
 * </pre>
 * This parses that block back into {@link Finding} objects so the offline path
 * (bundle → findings → prompt) can reuse the same serializer.
 */
public final class BundleFindings {

    private BundleFindings() {}

    /** Reads {@code doctor.txt} from the bundle and parses its findings. */
    public static List<Finding> fromBundle(Path bundle) throws IOException {
        String doctorTxt = readEntry(bundle, "doctor.txt");
        if (doctorTxt == null) {
            throw new IOException("Bundle does not contain doctor.txt: " + bundle);
        }
        return parse(doctorTxt);
    }

    /**
     * Parses the {@code doctor.txt} text format into findings. Lines that do not
     * match the expected shape are ignored, so a "healthy" bundle yields an
     * empty list.
     */
    public static List<Finding> parse(String doctorTxt) {
        List<Finding> findings = new ArrayList<>();
        Finding.Builder current = null;
        for (String raw : doctorTxt.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            // A line only starts a new finding when it parses as a real header
            // (a recognized [SEVERITY] token). A line that merely starts with
            // '[' but is not a valid header (e.g. a GC-log tag like "[gc,heap]"
            // or a JVM flag bracket quoted in a detail/recommendation) falls
            // through and is attached to the current finding instead of silently
            // dropping the rest of its lines.
            Finding.Builder header = parseHeader(line);
            if (header != null) {
                if (current != null) findings.add(current.build());
                current = header;
            } else if (current != null && line.startsWith("->")) {
                current.recommend(line.substring(2).strip());
            } else if (current != null) {
                current.detail(line);
            }
        }
        if (current != null) findings.add(current.build());
        return findings;
    }

    /**
     * Parses a finding header of the form {@code [SEVERITY] category: title}.
     * Returns {@code null} when the line is not a valid header: it must start
     * with {@code [}, contain a closing {@code ]}, and the bracketed token must
     * be a recognized {@link Severity}. Anything else is treated as detail or
     * recommendation by the caller.
     */
    private static Finding.Builder parseHeader(String line) {
        if (!line.startsWith("[")) return null;
        int close = line.indexOf(']');
        if (close < 0) return null;
        String sevToken = line.substring(1, close).trim();
        Severity severity = parseSeverity(sevToken);
        if (severity == null) return null;
        String rest = line.substring(close + 1).strip();
        int colon = rest.indexOf(':');
        String category = colon >= 0 ? rest.substring(0, colon).strip() : rest;
        String title = colon >= 0 ? rest.substring(colon + 1).strip() : "";
        return Finding.builder(severity, category, title);
    }

    /** Returns the matching {@link Severity}, or {@code null} when unrecognized. */
    private static Severity parseSeverity(String token) {
        try {
            return Severity.valueOf(token.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String readEntry(Path bundle, String wanted) throws IOException {
        try (InputStream fis = Files.newInputStream(bundle);
             GZIPInputStream gzip = new GZIPInputStream(fis)) {
            byte[] header = new byte[512];
            while (true) {
                int read = readFully(gzip, header);
                if (read < 512) break;
                boolean allZero = true;
                for (byte b : header) { if (b != 0) { allZero = false; break; } }
                if (allZero) break;

                String name = parseName(header);
                if (name.isEmpty()) break;
                long size = parseOctal(header);

                byte[] data = new byte[(int) size];
                readFully(gzip, data);
                int remainder = (int) (size % 512);
                if (remainder != 0) {
                    readFully(gzip, new byte[512 - remainder]);
                }
                if (name.equals(wanted)) {
                    return new String(data, StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static String parseName(byte[] buf) {
        int end = 0;
        while (end < 100 && buf[end] != 0) end++;
        return new String(buf, 0, end, StandardCharsets.US_ASCII).trim();
    }

    private static long parseOctal(byte[] buf) {
        String s = new String(buf, 124, 12, StandardCharsets.US_ASCII)
                .replaceAll("[\\x00 ]", "");
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s, 8); }
        catch (NumberFormatException e) { return 0; }
    }
}
