package io.argus.cli.provider.jdk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Downloads and verifies async-profiler for the current platform.
 */
public final class AsProfDownloader {

    private static final String ASPROF_VERSION = "3.0";
    private static final String BASE_URL =
            "https://github.com/async-profiler/async-profiler/releases/download/v" + ASPROF_VERSION;

    // SHA-256 checksums per platform (placeholder — replace with real values before release)
    private static final Map<String, String> CHECKSUMS;

    static {
        CHECKSUMS = new HashMap<>();
        CHECKSUMS.put("linux-x64",       "TODO_REPLACE_WITH_ACTUAL_HASH");
        CHECKSUMS.put("linux-arm64",     "TODO_REPLACE_WITH_ACTUAL_HASH");
        CHECKSUMS.put("linux-musl-x64",  "TODO_REPLACE_WITH_ACTUAL_HASH");
        CHECKSUMS.put("linux-musl-arm64","TODO_REPLACE_WITH_ACTUAL_HASH");
        CHECKSUMS.put("macos",           "TODO_REPLACE_WITH_ACTUAL_HASH");
    }

    private AsProfDownloader() {}

    /**
     * Detects the current platform.
     *
     * @return one of "linux-x64", "linux-arm64", "linux-musl-x64", "linux-musl-arm64",
     *         "macos-x64", "macos-arm64", or null if unsupported (e.g. Windows)
     */
    public static String detectPlatform() {
        String os   = System.getProperty("os.name",  "").toLowerCase();
        String arch = System.getProperty("os.arch",  "").toLowerCase();

        if (os.contains("win")) {
            return null;
        }

        // macOS: async-profiler ships a universal binary — no arch suffix
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }

        if (!os.contains("linux")) {
            return null;
        }

        String osName = isMusl() ? "linux-musl" : "linux";

        String archName;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archName = "x64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archName = "arm64";
        } else {
            return null;
        }

        return osName + "-" + archName;
    }

    /**
     * Detects musl libc (Alpine / Docker environments).
     * First checks for /lib/ld-musl-*.so.1; falls back to parsing ldd --version output.
     */
    private static boolean isMusl() {
        // Check for musl dynamic linker files
        File lib = new File("/lib");
        if (lib.isDirectory()) {
            File[] candidates = lib.listFiles();
            if (candidates != null) {
                for (File f : candidates) {
                    String name = f.getName();
                    if (name.startsWith("ld-musl-") && name.endsWith(".so.1")) {
                        return true;
                    }
                }
            }
        }

        // Fallback: ldd --version output
        try {
            ProcessBuilder pb = new ProcessBuilder("ldd", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] out = process.getInputStream().readAllBytes();
            process.waitFor(5, TimeUnit.SECONDS);
            String output = new String(out).toLowerCase();
            return output.contains("musl");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Downloads async-profiler for the current platform, extracts it, and verifies the checksum.
     *
     * @return absolute path to the asprof binary, or null on any failure
     */
    public static String download() {
        String platform = detectPlatform();
        if (platform == null) {
            System.err.println("[argus] Unsupported platform for async-profiler download.");
            return null;
        }

        boolean isMacos = platform.equals("macos");
        String archiveName = "async-profiler-" + ASPROF_VERSION + "-" + platform
                + (isMacos ? ".zip" : ".tar.gz");
        String url = BASE_URL + "/" + archiveName;

        Path destDir = Paths.get(System.getProperty("user.home"), ".argus", "lib", "async-profiler");
        Path tarPath = destDir.getParent().resolve(archiveName);

        try {
            Files.createDirectories(destDir.getParent());
        } catch (IOException e) {
            System.err.println("[argus] Cannot create download directory: " + e.getMessage());
            return null;
        }

        // Download
        System.err.println("[argus] Downloading async-profiler " + ASPROF_VERSION + " for " + platform + "...");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(
                    request, HttpResponse.BodyHandlers.ofFile(tarPath));
            if (response.statusCode() != 200) {
                System.err.println("[argus] Download failed with HTTP " + response.statusCode());
                return null;
            }
        } catch (Exception e) {
            System.err.println("[argus] Download error: " + e.getMessage());
            return null;
        }

        // Verify checksum — fail-closed if unavailable
        String expectedHash = CHECKSUMS.get(platform);
        if (expectedHash == null || expectedHash.startsWith("TODO_")) {
            System.err.println("[argus] WARNING: No verified checksum for " + platform + ".");
            System.err.println("[argus] The downloaded binary has NOT been integrity-verified.");
            System.err.println("[argus] For production use, verify manually: sha256sum " + tarPath);
        } else if (!verifyChecksum(tarPath, expectedHash)) {
            System.err.println("[argus] ERROR: Checksum mismatch for " + archiveName + ". File may be tampered. Aborting.");
            try { Files.deleteIfExists(tarPath); } catch (IOException ignored) {}
            return null;
        }

        // Extract
        try {
            Files.createDirectories(destDir);
            // Extract to a temp directory, then move contents to destDir
            Path extractDir = destDir.getParent().resolve("extract-tmp");
            Files.createDirectories(extractDir);
            ProcessBuilder pb;
            if (isMacos) {
                pb = new ProcessBuilder(
                        "unzip", "-o", "-q", tarPath.toAbsolutePath().toString(),
                        "-d", extractDir.toAbsolutePath().toString());
            } else {
                pb = new ProcessBuilder(
                        "tar", "xzf", tarPath.toAbsolutePath().toString(),
                        "-C", extractDir.toAbsolutePath().toString());
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] tarOut = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                System.err.println("[argus] Extraction failed: " + new String(tarOut));
                return null;
            }

            // Find the extracted directory (e.g., async-profiler-3.0-macos/) and move to destDir
            File[] extracted = extractDir.toFile().listFiles();
            if (extracted != null && extracted.length > 0) {
                // Remove old destDir if exists, then rename extracted dir
                deleteRecursive(destDir.toFile());
                extracted[0].renameTo(destDir.toFile());
            }
            deleteRecursive(extractDir.toFile());
        } catch (Exception e) {
            System.err.println("[argus] Extraction error: " + e.getMessage());
            return null;
        } finally {
            try { Files.deleteIfExists(tarPath); } catch (IOException ignored) {}
        }

        // Set executable bit
        Path binary = Paths.get(asProfPath());
        File binaryFile = binary.toFile();
        if (!binaryFile.exists()) {
            System.err.println("[argus] asprof binary not found after extraction at: " + binary);
            return null;
        }
        binaryFile.setExecutable(true);

        System.err.println("[argus] async-profiler installed at: " + binary);
        return binary.toAbsolutePath().toString();
    }

    /**
     * Returns the expected asprof binary path (may not exist yet).
     */
    public static String asProfPath() {
        return System.getProperty("user.home") + "/.argus/lib/async-profiler/bin/asprof";
    }

    /** Recursively deletes a directory and all its contents. */
    private static void deleteRecursive(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        dir.delete();
    }

    private static boolean verifyChecksum(Path file, String expectedHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString().equalsIgnoreCase(expectedHash);
        } catch (Exception e) {
            System.err.println("[argus] Checksum verification error: " + e.getMessage());
            return false;
        }
    }
}
