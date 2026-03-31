package io.argus.cli.provider.jdk;

import io.argus.cli.model.SymbolTableResult;
import io.argus.cli.provider.SymbolTableProvider;

/**
 * SymbolTableProvider that uses {@code jcmd VM.symboltable}.
 * Same output format as VM.stringtable.
 */
public final class JdkSymbolTableProvider implements SymbolTableProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public SymbolTableResult getSymbolTableInfo(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "VM.symboltable");
        } catch (RuntimeException e) {
            return new SymbolTableResult(0, 0, 0, 0, 0, 0, 0, 0.0);
        }
        return parseOutput(output);
    }

    static SymbolTableResult parseOutput(String output) {
        if (output == null || output.isEmpty()) {
            return new SymbolTableResult(0, 0, 0, 0, 0, 0, 0, 0.0);
        }

        long bucketCount = 0, entryCount = 0, literalCount = 0;
        long bucketBytes = 0, entryBytes = 0, literalBytes = 0, totalBytes = 0;
        double avgLiteralSize = 0.0;

        for (String line : output.split("\n")) {
            String trimmed = line.trim();

            if (trimmed.startsWith("Number of buckets")) {
                long[] parsed = parseCountAndBytes(trimmed);
                bucketCount = parsed[0]; bucketBytes = parsed[1];
            } else if (trimmed.startsWith("Number of entries")) {
                long[] parsed = parseCountAndBytes(trimmed);
                entryCount = parsed[0]; entryBytes = parsed[1];
            } else if (trimmed.startsWith("Number of literals")) {
                long[] parsed = parseCountAndBytes(trimmed);
                literalCount = parsed[0]; literalBytes = parsed[1];
                int avgIdx = trimmed.indexOf("avg");
                if (avgIdx >= 0) {
                    String rest = trimmed.substring(avgIdx + 3).trim();
                    try { avgLiteralSize = Double.parseDouble(rest.split("\\s+")[0]); }
                    catch (NumberFormatException ignored) {}
                }
            } else if (trimmed.startsWith("Total footprint") || trimmed.startsWith("Total foot")) {
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx >= 0) {
                    String rest = trimmed.substring(eqIdx + 1).trim();
                    for (String token : rest.split("\\s+")) {
                        try { totalBytes = Long.parseLong(token); break; }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        }

        if (totalBytes == 0) totalBytes = bucketBytes + entryBytes + literalBytes;

        return new SymbolTableResult(bucketCount, entryCount, literalCount,
                bucketBytes, entryBytes, literalBytes, totalBytes, avgLiteralSize);
    }

    private static long[] parseCountAndBytes(String line) {
        long count = 0, bytes = 0;
        int colonIdx = line.indexOf(':');
        if (colonIdx < 0) return new long[]{0, 0};

        String rest = line.substring(colonIdx + 1).trim();
        String[] parts = rest.split("=");
        if (parts.length >= 1) {
            try { count = Long.parseLong(parts[0].trim()); }
            catch (NumberFormatException ignored) {}
        }
        if (parts.length >= 2) {
            for (String token : parts[1].trim().split("\\s+")) {
                try { bytes = Long.parseLong(token); break; }
                catch (NumberFormatException ignored) {}
            }
        }
        return new long[]{count, bytes};
    }
}
