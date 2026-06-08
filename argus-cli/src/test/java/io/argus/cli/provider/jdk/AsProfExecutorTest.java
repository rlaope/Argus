package io.argus.cli.provider.jdk;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AsProfExecutorTest {

    @Test
    void capturesFastCommandOutput() {
        AsProfExecutor.Result result = AsProfExecutor.execute(
                new String[]{javaExecutable(), "-version"},
                5,
                null);

        assertEquals(0, result.exitCode());
        assertTrue((result.stdout() + result.stderr()).contains("version"));
    }

    @Test
    void timeoutDoesNotWaitForTheFullBudgetTwice() {
        long startNanos = System.nanoTime();

        AsProfExecutor.Result result = AsProfExecutor.execute(
                new String[]{
                        javaExecutable(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Sleeper.class.getName(),
                        "8000"
                },
                2,
                null);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertTrue(elapsedMillis < 3000,
                "expected timeout near 2s, but executor returned after " + elapsedMillis + "ms");
        assertNotEquals(0, result.exitCode());
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
