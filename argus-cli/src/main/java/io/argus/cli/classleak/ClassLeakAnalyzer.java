package io.argus.cli.classleak;

import io.argus.cli.provider.jdk.JcmdExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code jcmd VM.classloader_stats} output into {@link ClassLoaderEntry} records.
 *
 * <p>Actual output format (observed from OpenJDK 21):
 * <pre>
 * ClassLoader         Parent              CLD*               Classes   ChunkSz   BlockSz  Type
 * 0x0000000000000000  0x0000000000000000  0x0000000cb30b8e60    2447   8028160   7669896  &lt;boot class loader&gt;
 *                                                                 62    191488    123512   + hidden classes
 * 0x0000000500068770  0x0000000500069008  0x0000000cb30b9400     735   5586944   5581408  jdk.internal.loader.ClassLoaders$AppClassLoader
 * 0x0000000500069008  0x0000000000000000  0x0000000cb30b9360       3     41984     39576  jdk.internal.loader.ClassLoaders$PlatformClassLoader
 * Total = 3                                                      3247  13848576  13414392
 * </pre>
 *
 * <p>Hidden-class continuation lines (no leading address) contribute their counts to the
 * preceding loader's totals.
 */
public final class ClassLeakAnalyzer {

    private ClassLeakAnalyzer() {}

    /**
     * Runs {@code jcmd <pid> VM.classloader_stats} and returns the parsed entries.
     *
     * @throws RuntimeException if jcmd is unavailable or fails
     */
    public static List<ClassLoaderEntry> collect(long pid) {
        String output = JcmdExecutor.execute(pid, "VM.classloader_stats");
        return parse(output);
    }

    /**
     * Parses the raw text output of {@code VM.classloader_stats}.
     * Package-private for unit testing.
     */
    static List<ClassLoaderEntry> parse(String output) {
        List<ClassLoaderEntry> result = new ArrayList<>();
        ClassLoaderEntry last = null; // track previous entry to merge hidden-class lines

        for (String line : output.split("\n")) {
            // Skip header, blank, and legend lines
            String stripped = line.strip();
            if (stripped.isEmpty()) continue;
            if (stripped.startsWith("ClassLoader")) continue;
            if (stripped.startsWith("ChunkSz:") || stripped.startsWith("BlockSz:")) continue;
            if (stripped.startsWith("Total")) continue;

            // Hidden-class continuation line: indented, no leading hex address, "+ hidden classes" at the end.
            // Format: "                                  62    191488    123512   + hidden classes"
            // After stripping leading whitespace, the numbers come first and "+ hidden" comes at the end.
            if (line.startsWith(" ") && stripped.contains("+ hidden")) {
                if (last != null) {
                    // Pull numbers from the part before "+"
                    String numPart = stripped.split("\\+")[0].trim();
                    long[] n = parseNumbers(numPart);
                    if (n.length >= 3) {
                        last = new ClassLoaderEntry(
                                last.address(), last.parent(), last.type(),
                                last.classCount() + n[0],
                                last.chunkBytes()  + n[1],
                                last.blockBytes()  + n[2]
                        );
                        result.set(result.size() - 1, last);
                    }
                }
                continue;
            }

            // Normal data line: 3 hex addresses, then 3 numbers, then type
            // 0x0000000500068770  0x0000000500069008  0x0000000cb30b9400     735   5586944   5581408  Type
            if (!stripped.startsWith("0x")) continue;

            String[] tokens = stripped.split("\\s+");
            // tokens[0] = ClassLoader addr, [1] = Parent addr, [2] = CLD addr,
            // [3] = classCount, [4] = chunkBytes, [5] = blockBytes, [6...] = type name
            if (tokens.length < 7) continue;

            String address = tokens[0];
            String parent  = tokens[1];
            long classCount, chunkBytes, blockBytes;
            try {
                classCount = Long.parseLong(tokens[3]);
                chunkBytes = Long.parseLong(tokens[4]);
                blockBytes = Long.parseLong(tokens[5]);
            } catch (NumberFormatException e) {
                continue;
            }

            // Everything from token[6] onward is the type name
            StringBuilder typeSb = new StringBuilder();
            for (int i = 6; i < tokens.length; i++) {
                if (i > 6) typeSb.append(' ');
                typeSb.append(tokens[i]);
            }
            String type = typeSb.toString().trim();

            last = new ClassLoaderEntry(address, parent, type, classCount, chunkBytes, blockBytes);
            result.add(last);
        }

        return result;
    }

    /** Extracts all whitespace-separated long values from a string. */
    private static long[] parseNumbers(String s) {
        if (s == null || s.isBlank()) return new long[0];
        String[] parts = s.trim().split("\\s+");
        long[] nums = new long[parts.length];
        int count = 0;
        for (String p : parts) {
            try { nums[count++] = Long.parseLong(p); } catch (NumberFormatException ignored) {}
        }
        long[] out = new long[count];
        System.arraycopy(nums, 0, out, 0, count);
        return out;
    }
}
