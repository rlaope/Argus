package io.argus.cli.provider.jdk;

import io.argus.cli.model.SearchClassResult;
import io.argus.cli.model.SearchClassResult.ClassInfo;
import io.argus.cli.provider.SearchClassProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SearchClassProvider using jcmd GC.class_histogram to find loaded classes.
 * Supports glob patterns (*.UserService) converted to regex.
 */
public final class JdkSearchClassProvider implements SearchClassProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String source() {
        return "jdk";
    }

    @Override
    public SearchClassResult searchClasses(long pid, String pattern) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "GC.class_histogram");
        } catch (RuntimeException e) {
            return new SearchClassResult(List.of(), 0, pattern);
        }
        return parseOutput(output, pattern);
    }

    static SearchClassResult parseOutput(String output, String pattern) {
        if (output == null || output.isEmpty()) {
            return new SearchClassResult(List.of(), 0, pattern);
        }

        // Convert glob pattern to regex
        Pattern regex = globToRegex(pattern);

        List<ClassInfo> matches = new ArrayList<>();

        // GC.class_histogram output:
        //  num     #instances         #bytes  class name (module)
        // -------------------------------------------------------
        //    1:         12345      1234567  java.lang.String (java.base)
        //    2:          5678       567890  [B (java.base)
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("num") || trimmed.startsWith("---")
                    || trimmed.startsWith("Total")) {
                continue;
            }

            // Parse: "1:  12345  1234567  java.lang.String (java.base)"
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 4) continue;

            // parts[0] = "1:", parts[1] = instances, parts[2] = bytes, parts[3+] = class name
            String className = parts[3];
            // Remove module suffix like "(java.base)"
            if (className.endsWith(")")) {
                // className might be part of module, actual name is parts[3]
            }
            // Rebuild full class name (may contain spaces for array types)
            StringBuilder nameBuilder = new StringBuilder(parts[3]);
            for (int i = 4; i < parts.length; i++) {
                if (parts[i].startsWith("(")) break; // module info
                nameBuilder.append(' ').append(parts[i]);
            }
            className = nameBuilder.toString();

            if (!regex.matcher(className).find()) {
                continue;
            }

            long instances;
            long bytes;
            try {
                instances = Long.parseLong(parts[1]);
                bytes = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }

            matches.add(new ClassInfo(className, instances, bytes));
        }

        return new SearchClassResult(List.copyOf(matches), matches.size(), pattern);
    }

    private static Pattern globToRegex(String glob) {
        if (glob == null || glob.isEmpty()) {
            return Pattern.compile(".*");
        }

        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '$' -> regex.append("\\$");
                case '[' -> regex.append("\\[");
                case ']' -> regex.append("\\]");
                case '(' -> regex.append("\\(");
                case ')' -> regex.append("\\)");
                default -> regex.append(c);
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
