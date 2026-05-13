package io.argus.cli.provider.jdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdkCompilerProviderDeoptTest {

    @Test
    void emptyOutput_returnsZero() {
        assertEquals(0L, JdkCompilerProvider.parseDeoptCount(""));
        assertEquals(0L, JdkCompilerProvider.parseDeoptCount(null));
    }

    @Test
    void counterPresent_returnsValue() {
        String out = String.join("\n",
                "java.ci.totalTime=3946042",
                "sun.ci.standardCompiles=56",
                "sun.ci.totalInvalidates=42",
                "sun.ci.totalBailouts=1");
        assertEquals(42L, JdkCompilerProvider.parseDeoptCount(out));
    }

    @Test
    void counterAbsent_returnsZero() {
        String out = String.join("\n",
                "sun.ci.standardCompiles=56",
                "sun.ci.totalCompiles=56");
        assertEquals(0L, JdkCompilerProvider.parseDeoptCount(out));
    }

    @Test
    void ignoresSimilarlyNamedCounters() {
        // lastInvalidatedType / lastInvalidatedMethod also contain "Invalidate" but
        // are not the canonical totals counter.
        String out = String.join("\n",
                "sun.ci.lastInvalidatedMethod=\"\"",
                "sun.ci.lastInvalidatedType=0",
                "sun.ci.totalInvalidates=7");
        assertEquals(7L, JdkCompilerProvider.parseDeoptCount(out));
    }

    @Test
    void malformedValue_returnsZero() {
        String out = "sun.ci.totalInvalidates=not-a-number";
        assertEquals(0L, JdkCompilerProvider.parseDeoptCount(out));
    }

    @Test
    void wholePipelineParse_carriesDeoptIntoResult() {
        // Sanity check: the package-private parseOutput(...) overload threads
        // deopt count into the returned CompilerResult.
        String codecache = "CodeCache: size=245760Kb used=12345Kb max_used=20000Kb free=200000Kb";
        String queue = "";
        String perfCounter = "sun.ci.totalInvalidates=99\nsun.ci.standardCompiles=200";
        var r = JdkCompilerProvider.parseOutput(codecache, queue, perfCounter);
        assertEquals(99L, r.deoptCount());
        assertEquals(245760L, r.codeCacheSizeKb());
        assertEquals(12345L, r.codeCacheUsedKb());
    }
}
