package io.argus.server.command.impl;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the secret-masking behavior of {@link DiagnosticUtil#maskIfSensitive(String, String)}.
 *
 * <p>Important now that {@code sysprops} and {@code env} are reachable
 * cross-pod via the aggregator console proxy (see {@code argus-aggregator}).
 * A leaked credential through one pod becomes a fleet-wide problem, so the
 * mask list must cover both key-name patterns and value-shape patterns
 * (e.g. connection strings with embedded {@code user:password@}).
 */
class DiagnosticUtilTest {

    private static String mask(String key, String value) {
        return DiagnosticUtil.maskIfSensitive(key, value);
    }

    private static void assertMasked(String key, String value) {
        String out = mask(key, value);
        assertNotEquals(value, out, "expected mask for " + key + "=" + value + " but got " + out);
        assertTrue(out.contains("****"), "expected mask marker in " + out);
    }

    @Test
    void masksKeysWithKnownCredentialMarkers() {
        assertMasked("MY_PASSWORD", "hunter2");
        assertMasked("APP_SECRET", "hunter2");
        assertMasked("API_KEY", "hunter2");
        assertMasked("ACCESS_TOKEN", "hunter2");
        assertMasked("AUTH_BEARER", "hunter2");
        assertMasked("DB_CREDENTIAL", "hunter2");
        assertMasked("PRIVATE_KEY", "hunter2");
    }

    @Test
    void masksConnectionStringEnvs() {
        assertMasked("DATABASE_URL", "postgresql://user:pass@db:5432/app");
        assertMasked("JDBC_URL", "jdbc:postgresql://user:pass@db:5432/app");
        assertMasked("MONGODB_URI", "mongodb://user:pass@db:27017/app");
        assertMasked("REDIS_URL", "redis://default:pass@redis:6379");
    }

    @Test
    void masksDsnAndSessionKeys() {
        assertMasked("SENTRY_DSN", "https://abc@sentry.example/123");
        assertMasked("APP_SESSION", "deadbeef");
        assertMasked("PASSPHRASE", "deadbeef");
    }

    @Test
    void masksCertAndPemKeys() {
        assertMasked("TLS_CERT", "-----BEGIN CERT-----...");
        assertMasked("MY_PEM", "-----BEGIN RSA PRIVATE KEY-----");
    }

    @Test
    void masksConnectionStringValueEvenWhenKeyLooksGeneric() {
        // The key alone ("url"/"endpoint"/"upstream") would not raise a flag;
        // the value shape (scheme://user:password@…) does.
        assertMasked("url", "postgresql://admin:secret@db:5432/app");
        assertMasked("endpoint", "amqp://guest:guest@rabbit:5672/");
        assertMasked("upstream", "mongodb://root:topsecret@mongo:27017");
    }

    @Test
    void doesNotMaskHarmlessValues() {
        // Generic configuration must NOT be masked; mask must be precise.
        assertEquals("https://example.com/health",
                mask("HEALTH_URL", "https://example.com/health"));
        assertEquals("21",
                mask("JAVA_VERSION", "21"));
        assertEquals("UTF-8",
                mask("file.encoding", "UTF-8"));
        // URL without userinfo must not be masked.
        assertEquals("postgresql://db:5432/app",
                mask("url", "postgresql://db:5432/app"));
    }

    @Test
    void handlesNullsGracefully() {
        // Null key falls through as if it had no markers.
        assertEquals("value", mask(null, "value"));
        // Null value with a sensitive key returns the masked placeholder.
        String masked = mask("PASSWORD", null);
        assertTrue(masked.contains("****"), masked);
    }

    @Test
    void shortValuesAreFullyMasked() {
        // Values too short to slice are replaced wholesale.
        assertEquals("****", mask("PASSWORD", "abc"));
        assertEquals("****", mask("PASSWORD", ""));
    }

    @Test
    void executeProcessTimesOutEvenWhenChildProducesNoOutput() throws Exception {
        long startNanos = System.nanoTime();

        String result = DiagnosticUtil.executeProcess(
                List.of(
                        javaExecutable(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Sleeper.class.getName(),
                        "5000"),
                1,
                "timed out");

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertEquals("timed out", result);
        assertTrue(elapsedMillis < 2500,
                "expected timeout near 1s, but diagnostic process returned after " + elapsedMillis + "ms");
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(Long.parseLong(args[0]));
        }
    }
}
