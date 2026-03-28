package io.argus.cli.provider.jdk;

import io.argus.cli.model.StringTableResult;
import io.argus.cli.provider.StringTableProvider;

/**
 * StringTableProvider that uses {@code jcmd VM.stringtable}.
 */
public final class JdkStringTableProvider implements StringTableProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public StringTableResult getStringTableInfo(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "VM.stringtable");
        } catch (RuntimeException e) {
            return new StringTableResult(0, 0, 0, 0, 0, 0, 0, 0.0);
        }
        return parseOutput(output);
    }

    static StringTableResult parseOutput(String output) {
        long bucketCount = 0, entryCount = 0, literalCount = 0;
        long bucketBytes = 0, entryBytes = 0, literalBytes = 0, totalBytes = 0;
        double avgLiteralSize = 0.0;

        for (String line : output.split("\n")) {
            String trimmed = line.trim();

            // "Number of buckets       :    65536 =  524288 bytes, each 8"
            if (trimmed.startsWith("Number of buckets")) {
                long[] parsed = parseCountAndBytes(trimmed);
                bucketCount = parsed[0];
                bucketBytes = parsed[1];
            }
            // "Number of entries       :    12345 =  197520 bytes, each 16"
            else if (trimmed.startsWith("Number of entries")) {
                long[] parsed = parseCountAndBytes(trimmed);
                entryCount = parsed[0];
                entryBytes = parsed[1];
            }
            // "Number of literals      :    12345 =  456789 bytes, avg  37.000"
            else if (trimmed.startsWith("Number of literals")) {
                long[] parsed = parseCountAndBytes(trimmed);
                literalCount = parsed[0];
                literalBytes = parsed[1];
                // Extract avg
                int avgIdx = trimmed.indexOf("avg");
                if (avgIdx >= 0) {
                    String rest = trimmed.substring(avgIdx + 3).trim();
                    try {
                        avgLiteralSize = Double.parseDouble(rest.split("\\s+")[0]);
                    } catch (NumberFormatException ignored) {}
                }
            }
            // "Total footprint         :          = 1178597 bytes"
            else if (trimmed.startsWith("Total footprint") || trimmed.startsWith("Total foot")) {
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx >= 0) {
                    String rest = trimmed.substring(eqIdx + 1).trim();
                    for (String token : rest.split("\\s+")) {
                        try {
                            totalBytes = Long.parseLong(token);
                            break;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }

        if (totalBytes == 0) {
            totalBytes = bucketBytes + entryBytes + literalBytes;
        }

        return new StringTableResult(bucketCount, entryCount, literalCount,
                bucketBytes, entryBytes, literalBytes, totalBytes, avgLiteralSize);
    }

    private static long[] parseCountAndBytes(String line) {
        long count = 0;
        long bytes = 0;

        int colonIdx = line.indexOf(':');
        if (colonIdx < 0) return new long[]{0, 0};

        String rest = line.substring(colonIdx + 1).trim();
        // "65536 =  524288 bytes, each 8"
        String[] parts = rest.split("=");
        if (parts.length >= 1) {
            try {
                count = Long.parseLong(parts[0].trim());
            } catch (NumberFormatException ignored) {}
        }
        if (parts.length >= 2) {
            String bytesPart = parts[1].trim();
            for (String token : bytesPart.split("\\s+")) {
                try {
                    bytes = Long.parseLong(token);
                    break;
                } catch (NumberFormatException ignored) {}
            }
        }
        return new long[]{count, bytes};
    }
}
