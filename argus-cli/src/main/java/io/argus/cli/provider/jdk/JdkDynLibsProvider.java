package io.argus.cli.provider.jdk;

import io.argus.cli.model.DynLibsResult;
import io.argus.cli.model.DynLibsResult.LibInfo;
import io.argus.cli.provider.DynLibsProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * DynLibsProvider that uses {@code jcmd VM.dynlibs}.
 */
public final class JdkDynLibsProvider implements DynLibsProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public DynLibsResult getDynLibs(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "VM.dynlibs");
        } catch (RuntimeException e) {
            return new DynLibsResult(0, List.of());
        }
        return parseOutput(output);
    }

    static DynLibsResult parseOutput(String output) {
        if (output == null || output.isEmpty()) {
            return new DynLibsResult(0, List.of());
        }

        List<LibInfo> libs = new ArrayList<>();

        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // Lines may be just paths, or "0x... ... /path/to/lib.so"
            String path = trimmed;

            // Extract path from hex-prefixed lines: "0x0001234 0x0005678 0x0004444 /usr/lib/libz.dylib"
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                String[] parts = trimmed.split("\\s+");
                // Last token that starts with / is the path
                for (int i = parts.length - 1; i >= 0; i--) {
                    if (parts[i].startsWith("/") || parts[i].contains(".so") || parts[i].contains(".dylib") || parts[i].contains(".dll")) {
                        path = parts[i];
                        break;
                    }
                }
                // If still hex, skip this line
                if (path.startsWith("0x") || path.startsWith("0X")) continue;
            }

            // Skip non-path lines (headers, etc.)
            if (!path.startsWith("/") && !path.contains(".so") && !path.contains(".dylib") && !path.contains(".dll")) {
                continue;
            }

            String category = classifyLib(path);
            libs.add(new LibInfo(path, category));
        }

        return new DynLibsResult(libs.size(), List.copyOf(libs));
    }

    static String classifyLib(String path) {
        String lower = path.toLowerCase();

        // JDK libraries
        if (lower.contains("/jdk/") || lower.contains("/jre/") || lower.contains("/jvm/")
                || lower.contains("java_home") || lower.contains("/jbr/")
                || lower.contains("libjvm") || lower.contains("libjava")
                || lower.contains("libjli") || lower.contains("libverify")
                || lower.contains("libzip") || lower.contains("libnet")
                || lower.contains("libnio") || lower.contains("libawt")
                || lower.contains("libmanagement") || lower.contains("libinstrument")
                || lower.contains("libattach") || lower.contains("libsaproc")
                || lower.contains("libj9") || lower.contains("libjsig")
                || lower.contains("jdk.") || lower.contains("java.")) {
            return "jdk";
        }

        // System libraries
        if (lower.startsWith("/usr/lib") || lower.startsWith("/lib")
                || lower.startsWith("/system") || lower.startsWith("/opt/homebrew")
                || lower.contains("libc.") || lower.contains("libpthread")
                || lower.contains("libm.") || lower.contains("libdl")
                || lower.contains("librt.") || lower.contains("libstdc++")
                || lower.contains("libsystem") || lower.contains("libdyld")) {
            return "system";
        }

        return "app";
    }
}
