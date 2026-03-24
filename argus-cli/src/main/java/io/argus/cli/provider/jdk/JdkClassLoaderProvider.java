package io.argus.cli.provider.jdk;

import io.argus.cli.model.ClassLoaderResult;
import io.argus.cli.model.ClassLoaderResult.ClassLoaderInfo;
import io.argus.cli.provider.ClassLoaderProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides classloader hierarchy data via {@code jcmd VM.classloaders}.
 * Falls back to an empty result if the command is not available.
 */
public final class JdkClassLoaderProvider implements ClassLoaderProvider {

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
    public ClassLoaderResult getClassLoaders(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "VM.classloaders");
        } catch (RuntimeException e) {
            return new ClassLoaderResult(List.of(), 0L);
        }

        List<ClassLoaderInfo> loaders = parseClassLoaders(output);
        long totalClasses = loaders.stream().mapToLong(ClassLoaderInfo::classCount).sum();
        return new ClassLoaderResult(List.copyOf(loaders), totalClasses);
    }

    /**
     * Parses VM.classloaders output. Example format:
     * <pre>
     * +-- &lt;bootstrap&gt;
     *       +-- jdk.internal.loader.ClassLoaders$PlatformClassLoader (512 classes)
     *             +-- jdk.internal.loader.ClassLoaders$AppClassLoader (1024 classes)
     *                   +-- custom.MyClassLoader (32 classes)
     * </pre>
     * Indentation level determines parent relationship.
     */
    private static List<ClassLoaderInfo> parseClassLoaders(String output) {
        List<ClassLoaderInfo> result = new ArrayList<>();
        // Track the last loader name at each indent depth for parent resolution
        String[] lastAtDepth = new String[64];

        for (String line : output.split("\n")) {
            if (line.trim().isEmpty()) continue;

            // Find the "+--" marker
            int markerIdx = line.indexOf("+--");
            if (markerIdx < 0) continue;

            int depth = markerIdx / 2; // each level is 2 spaces of indent before "+--"
            String rest = line.substring(markerIdx + 3).trim();
            if (rest.isEmpty()) continue;

            // Extract class count from "(N classes)" suffix
            long classCount = 0L;
            int parenOpen = rest.lastIndexOf('(');
            int parenClose = rest.lastIndexOf(')');
            if (parenOpen >= 0 && parenClose > parenOpen) {
                String inside = rest.substring(parenOpen + 1, parenClose).trim();
                // "512 classes" or "512 class"
                String[] parts = inside.split("\\s+");
                if (parts.length >= 1) {
                    classCount = parseLong(parts[0]);
                }
                rest = rest.substring(0, parenOpen).trim();
            }

            String loaderName = rest.isEmpty() ? "<unknown>" : rest;

            // Determine parent: look at depth - 1
            String parent = (depth > 0 && lastAtDepth[depth - 1] != null)
                    ? lastAtDepth[depth - 1]
                    : null;

            if (depth < lastAtDepth.length) {
                lastAtDepth[depth] = loaderName;
                // Clear deeper entries
                for (int i = depth + 1; i < lastAtDepth.length; i++) lastAtDepth[i] = null;
            }

            result.add(new ClassLoaderInfo(loaderName, classCount, parent));
        }

        return result;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
