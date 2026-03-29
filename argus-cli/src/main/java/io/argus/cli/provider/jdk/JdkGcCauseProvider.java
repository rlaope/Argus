package io.argus.cli.provider.jdk;

import io.argus.cli.model.GcCauseResult;
import io.argus.cli.provider.GcCauseProvider;

/**
 * GcCauseProvider that uses {@code jstat -gccause} to get GC cause info.
 */
public final class JdkGcCauseProvider implements GcCauseProvider {

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
        try {
            ProcessBuilder pb = new ProcessBuilder("jstat", "-gccause", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return parseOutput(output);
        } catch (Exception e) {
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

        // Extract LGCC and GCC - they're at the end and may contain spaces
        // Rejoin the data line and extract by column positions from header
        if (lgccIdx >= 0 && lgccIdx < values.length) {
            // Simple case: single-word causes
            lgcc = values[lgccIdx];
            if (gccIdx >= 0 && gccIdx < values.length) {
                gcc = values[gccIdx];
            }

            // Handle multi-word causes by using header positions
            int lgccStart = headerLine.indexOf("LGCC");
            int gccStart = headerLine.indexOf(" GCC");
            if (lgccStart >= 0 && gccStart >= 0 && dataLine.length() >= lgccStart) {
                String causeSection = dataLine.length() > lgccStart ? dataLine.substring(lgccStart) : "";
                if (gccStart > lgccStart) {
                    int relativeGcc = gccStart - lgccStart;
                    if (causeSection.length() > relativeGcc) {
                        lgcc = causeSection.substring(0, relativeGcc).trim();
                        gcc = causeSection.substring(relativeGcc).trim();
                    } else {
                        lgcc = causeSection.trim();
                    }
                } else {
                    lgcc = causeSection.trim();
                }
            }
        }

        return new GcCauseResult(s0, s1, eden, old, meta, ccs, ygc, ygct, fgc, fgct, gct, lgcc, gcc);
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
