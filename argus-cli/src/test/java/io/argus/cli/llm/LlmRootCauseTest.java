package io.argus.cli.llm;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.Severity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LlmRootCauseTest {

    /** Captures the prompt it is given and never touches the network. */
    private static final class CapturingStub implements LlmProvider {
        String systemInstruction;
        String userPrompt;
        boolean invoked = false;

        @Override
        public String complete(String systemInstruction, String userPrompt) {
            this.invoked = true;
            this.systemInstruction = systemInstruction;
            this.userPrompt = userPrompt;
            return "Stub advisory: heap pressure is the likely root cause.";
        }

        @Override
        public String name() { return "stub"; }
    }

    private static List<Finding> sampleFindings() {
        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder(Severity.CRITICAL, "Heap", "Heap nearly full")
                .detail("Heap usage at 92% of 4096 MB max")
                .recommend("Increase -Xmx to 6144")
                .flag("-Xmx6144m")
                .build());
        findings.add(Finding.builder(Severity.WARNING, "GC", "High GC overhead")
                .detail("GC overhead at 18.5% over the last 60 seconds")
                .recommend("Investigate allocation rate")
                .build());
        return findings;
    }

    // AC2: disabled => provider is NEVER invoked and there is zero network call.
    @Test
    void disabled_neverInvokesProvider() {
        LlmConfig disabled = new LlmConfig(false, "sk-present", "https://example", "model");
        AtomicBoolean factoryCalled = new AtomicBoolean(false);
        LlmRootCause rca = new LlmRootCause(disabled, cfg -> {
            factoryCalled.set(true);
            throw new AssertionError("provider must not be constructed when disabled");
        });

        LlmRootCause.Result result = rca.analyze(sampleFindings());

        assertFalse(factoryCalled.get(), "provider factory must not run when disabled");
        assertFalse(result.hasAdvisory(), "no advisory when disabled");
        assertNotNull(result.skippedReason());
        assertEquals(2, result.findings().size(), "findings always flow through");
    }

    // AC2: enabled but no key => still treated as disabled, no provider call.
    @Test
    void enabledWithoutKey_neverInvokesProvider() {
        LlmConfig noKey = new LlmConfig(true, "  ", "https://example", "model");
        LlmRootCause rca = new LlmRootCause(noKey, cfg -> {
            throw new AssertionError("provider must not be constructed without a key");
        });

        LlmRootCause.Result result = rca.analyze(sampleFindings());

        assertFalse(result.hasAdvisory());
        assertFalse(noKey.isActivatable());
    }

    // AC2: activatable => stub provider IS invoked.
    @Test
    void activatable_invokesProvider() {
        LlmConfig on = new LlmConfig(true, "sk-test", "https://example", "model");
        CapturingStub stub = new CapturingStub();
        LlmRootCause rca = new LlmRootCause(on, cfg -> stub);

        LlmRootCause.Result result = rca.analyze(sampleFindings());

        assertTrue(stub.invoked, "stub must be invoked when activatable");
        assertTrue(result.hasAdvisory());
        assertEquals("stub", result.providerName());
    }

    // AC3: findings-only guarantee. Every number in the prompt must also appear
    // in the findings payload — the prompt invents no metric.
    @Test
    void prompt_containsOnlyNumbersFromFindings() {
        LlmConfig on = new LlmConfig(true, "sk-test", "https://example", "model");
        CapturingStub stub = new CapturingStub();
        List<Finding> findings = sampleFindings();
        new LlmRootCause(on, cfg -> stub).analyze(findings);

        assertNotNull(stub.userPrompt, "prompt must be captured");

        // Numbers present in the findings themselves (the source of truth).
        String findingsText = FindingsPrompt.serialize(findings);
        var allowed = extractNumbers(findingsText);

        // Every number in the prompt must be a subset of the findings numbers.
        for (String num : extractNumbers(stub.userPrompt)) {
            assertTrue(allowed.contains(num),
                    "prompt contains a number not present in findings: " + num
                            + " (allowed=" + allowed + ")");
        }

        // Sanity: known finding numbers DID make it into the prompt.
        assertTrue(stub.userPrompt.contains("92"));
        assertTrue(stub.userPrompt.contains("4096"));
        assertTrue(stub.userPrompt.contains("18.5"));

        // The system instruction forbids inventing metrics.
        assertTrue(stub.systemInstruction.toLowerCase().contains("do not invent"));
    }

    // AC5: offline bundle path — bundle -> findings -> prompt, with a stub.
    @Test
    void bundlePath_feedsFindingsToProvider() throws Exception {
        String doctorTxt =
                "[CRITICAL] Heap: Heap nearly full\n"
                + "  Heap usage at 92% of 4096 MB max\n"
                + "  -> Increase -Xmx to 6144\n"
                + "\n"
                + "[WARNING] GC: High GC overhead\n"
                + "  GC overhead at 18.5% over 60 seconds\n"
                + "  -> Investigate allocation rate\n";

        Path bundle = Files.createTempFile("argus-snap", ".tar.gz");
        try {
            writeTarGz(bundle, "doctor.txt", doctorTxt.getBytes(StandardCharsets.UTF_8));

            List<Finding> findings = BundleFindings.fromBundle(bundle);
            assertEquals(2, findings.size(), "two findings parsed from bundle");
            assertEquals(Severity.CRITICAL, findings.get(0).severity());
            assertEquals("Heap", findings.get(0).category());
            assertEquals("Heap nearly full", findings.get(0).title());
            assertTrue(findings.get(0).detail().contains("92%"));
            assertEquals(1, findings.get(0).recommendations().size());

            LlmConfig on = new LlmConfig(true, "sk-test", "https://example", "model");
            CapturingStub stub = new CapturingStub();
            LlmRootCause.Result result =
                    new LlmRootCause(on, cfg -> stub).analyze(findings);

            assertTrue(stub.invoked, "bundle findings must reach the provider");
            assertTrue(stub.userPrompt.contains("Heap nearly full"));
            assertTrue(stub.userPrompt.contains("92"));
            assertTrue(stub.userPrompt.contains("4096"));
            assertTrue(result.hasAdvisory());

            // findings-only also holds for the bundle path
            var allowed = extractNumbers(FindingsPrompt.serialize(findings));
            for (String num : extractNumbers(stub.userPrompt)) {
                assertTrue(allowed.contains(num),
                        "bundle prompt invented number: " + num);
            }
        } finally {
            Files.deleteIfExists(bundle);
        }
    }

    // Hardening: a detail/recommendation line that starts with '[' but is not a
    // valid [SEVERITY] header (e.g. a quoted GC-log tag or a JVM flag bracket)
    // must NOT be mistaken for a new finding. The finding and all its lines must
    // survive, and a following real finding must still parse.
    @Test
    void bracketLine_thatIsNotASeverityHeader_doesNotDropLines() {
        String doctorTxt =
                "[CRITICAL] GC: Long pauses\n"
                + "  [gc,heap] 2048M->1900M observed in the log\n"
                + "  -> review [InitiatingHeapOccupancyPercent] tuning\n"
                + "  -> Investigate allocation rate\n"
                + "\n"
                + "[WARNING] Threads: Pool saturation\n"
                + "  All worker threads busy\n"
                + "  -> Increase pool size\n";

        List<Finding> findings = BundleFindings.parse(doctorTxt);

        // Both findings survive — the bracketed lines did not split or drop them.
        assertEquals(2, findings.size(), "both findings must survive bracketed lines");

        Finding first = findings.get(0);
        assertEquals(Severity.CRITICAL, first.severity());
        assertEquals("GC", first.category());
        assertEquals("Long pauses", first.title());
        // The bracketed detail line was retained as detail, not treated as a header.
        assertTrue(first.detail().contains("[gc,heap]"),
                "bracketed detail line must be preserved: " + first.detail());
        // Both recommendations survive, including the one containing a '[' bracket.
        assertEquals(2, first.recommendations().size(),
                "both recommendations must survive: " + first.recommendations());
        assertTrue(first.recommendations().get(0).contains("[InitiatingHeapOccupancyPercent]"),
                "bracketed recommendation must be preserved");
        assertTrue(first.recommendations().get(1).contains("allocation rate"));

        // The following real finding still parses normally.
        Finding second = findings.get(1);
        assertEquals(Severity.WARNING, second.severity());
        assertEquals("Threads", second.category());
        assertEquals("Pool saturation", second.title());
        assertEquals(1, second.recommendations().size());
    }

    // Provider failure must be caught; findings still flow through.
    @Test
    void providerFailure_fallsBackToFindings() {
        LlmConfig on = new LlmConfig(true, "sk-test", "https://example", "model");
        LlmRootCause rca = new LlmRootCause(on, cfg -> new LlmProvider() {
            @Override public String complete(String s, String u) throws LlmException {
                throw new LlmException("boom");
            }
            @Override public String name() { return "failing"; }
        });

        LlmRootCause.Result result = rca.analyze(sampleFindings());

        assertFalse(result.hasAdvisory());
        assertNotNull(result.skippedReason());
        assertTrue(result.skippedReason().contains("boom"));
        assertEquals(2, result.findings().size());
    }

    // -------------------------------------------------------------------------

    private static List<String> extractNumbers(String text) {
        List<String> nums = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(text);
        while (m.find()) nums.add(m.group());
        return nums;
    }

    /** Minimal single-entry tar.gz writer matching the snapshot bundle format. */
    private static void writeTarGz(Path dest, String name, byte[] data) throws Exception {
        try (OutputStream fos = Files.newOutputStream(dest);
             GZIPOutputStream gzip = new GZIPOutputStream(fos)) {
            byte[] header = new byte[512];
            byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 99));
            putOctal(header, 100, 8, 0644);
            putOctal(header, 124, 12, data.length);
            putOctal(header, 136, 12, System.currentTimeMillis() / 1000L);
            java.util.Arrays.fill(header, 148, 156, (byte) ' ');
            header[156] = '0';
            byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(magic, 0, header, 257, magic.length);
            header[263] = '0';
            header[264] = '0';
            int checksum = 0;
            for (byte b : header) checksum += (b & 0xFF);
            byte[] cs = String.format("%06o", checksum).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(cs, 0, header, 148, 6);
            header[154] = 0;
            header[155] = ' ';
            gzip.write(header);
            gzip.write(data);
            int remainder = data.length % 512;
            if (remainder != 0) gzip.write(new byte[512 - remainder]);
            gzip.write(new byte[1024]);
        }
    }

    private static void putOctal(byte[] buf, int offset, int length, long value) {
        String octal = String.format("%" + (length - 1) + "s", Long.toOctalString(value))
                .replace(' ', '0');
        byte[] bytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buf, offset, Math.min(bytes.length, length - 1));
        buf[offset + length - 1] = 0;
    }
}
