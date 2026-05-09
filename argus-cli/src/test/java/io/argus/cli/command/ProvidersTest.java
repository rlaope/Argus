package io.argus.cli.command;

import io.argus.cli.config.Messages;
import io.argus.cli.provider.GcProvider;
import io.argus.cli.provider.jdk.JdkGcProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProvidersTest {

    private static final Messages EN = new Messages("en");

    @Test
    void requireReturnsProviderWhenNonNull() {
        GcProvider provider = new JdkGcProvider();
        assertSame(provider, Providers.require(provider, 12345L, EN));
    }

    @Test
    void requireThrowsWithExitCodeOneWhenNull() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            CommandExitException ex = assertThrows(
                    CommandExitException.class,
                    () -> Providers.require((GcProvider) null, 12345L, EN));
            assertEquals(1, ex.exitCode());
            String stderr = captured.toString(StandardCharsets.UTF_8);
            assertTrue(stderr.contains("12345"),
                    "stderr should mention the pid; was: " + stderr);
        } finally {
            System.setErr(originalErr);
        }
    }
}
