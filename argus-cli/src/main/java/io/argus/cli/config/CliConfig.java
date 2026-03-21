package io.argus.cli.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Immutable configuration record for the Argus CLI.
 * Persisted to ~/.argus/config.properties.
 */
public record CliConfig(
        String lang,
        String defaultSource,
        boolean color,
        String format,
        int defaultPort
) {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".argus");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");

    /**
     * Loads configuration from ~/.argus/config.properties.
     * Returns defaults() if the file does not exist or cannot be read.
     */
    public static CliConfig load() {
        if (!Files.exists(CONFIG_FILE)) {
            return defaults();
        }
        try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
            Properties props = new Properties();
            props.load(in);
            return new CliConfig(
                    props.getProperty("lang", "en"),
                    props.getProperty("defaultSource", "auto"),
                    Boolean.parseBoolean(props.getProperty("color", "true")),
                    props.getProperty("format", "table"),
                    Integer.parseInt(props.getProperty("defaultPort", "9202"))
            );
        } catch (IOException | NumberFormatException e) {
            return defaults();
        }
    }

    /**
     * Saves this configuration to ~/.argus/config.properties.
     * Creates the directory if it does not exist.
     */
    public static void save(CliConfig config) throws IOException {
        if (!Files.exists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR);
        }
        Properties props = new Properties();
        props.setProperty("lang", config.lang());
        props.setProperty("defaultSource", config.defaultSource());
        props.setProperty("color", String.valueOf(config.color()));
        props.setProperty("format", config.format());
        props.setProperty("defaultPort", String.valueOf(config.defaultPort()));

        try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
            props.store(out, "Argus CLI configuration");
        }
    }

    /**
     * Returns the default configuration.
     */
    public static CliConfig defaults() {
        return new CliConfig("en", "auto", true, "table", 9202);
    }

    /**
     * Returns true if the config file exists on disk.
     */
    public static boolean isInitialized() {
        return Files.exists(CONFIG_FILE);
    }
}
