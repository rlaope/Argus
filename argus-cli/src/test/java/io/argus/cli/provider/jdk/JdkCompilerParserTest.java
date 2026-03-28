package io.argus.cli.provider.jdk;

import io.argus.cli.model.CompilerResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdkCompilerParserTest {

    private static final String CODECACHE_OUTPUT =
            "CodeHeap 'non-profiled nmethods': size=120032Kb used=1234Kb max_used=2345Kb free=118798Kb\n" +
            " bounds [0x0000000108600000, 0x0000000108870000, 0x000000010fb60000]\n" +
            "CodeHeap 'profiled nmethods': size=120028Kb used=5678Kb max_used=6789Kb free=114350Kb\n" +
            " bounds [0x0000000110ca0000, 0x00000001118a0000, 0x00000001181a0000]\n" +
            "CodeHeap 'non-nmethods': size=5700Kb used=1100Kb max_used=1200Kb free=4600Kb\n" +
            " bounds [0x00000001081a0000, 0x0000000108410000, 0x0000000108730000]\n" +
            " total_blobs=4567 nmethods=3456 adapters=789\n" +
            " compilation: enabled\n";

    @Test
    void parseOutput_codeCacheSizeAggregated() {
        CompilerResult r = JdkCompilerProvider.parseOutput(CODECACHE_OUTPUT, "");
        assertEquals(245760, r.codeCacheSizeKb());
    }

    @Test
    void parseOutput_codeCacheUsedAggregated() {
        CompilerResult r = JdkCompilerProvider.parseOutput(CODECACHE_OUTPUT, "");
        assertEquals(8012, r.codeCacheUsedKb());
    }

    @Test
    void parseOutput_compilationEnabled() {
        CompilerResult r = JdkCompilerProvider.parseOutput(CODECACHE_OUTPUT, "");
        assertTrue(r.compilationEnabled());
    }

    @Test
    void parseOutput_blobsParsed() {
        CompilerResult r = JdkCompilerProvider.parseOutput(CODECACHE_OUTPUT, "");
        assertEquals(4567, r.totalBlobs());
        assertEquals(3456, r.nmethods());
        assertEquals(789, r.adapters());
    }

    @Test
    void parseOutput_emptyInput() {
        CompilerResult r = JdkCompilerProvider.parseOutput("", "");
        assertEquals(0, r.codeCacheSizeKb());
        assertEquals(0, r.totalBlobs());
    }

    @Test
    void parseOutput_queueEntries() {
        String queue = "1       4       io.argus.cli.Main::main\n" +
                       "2       3       java.lang.String::hashCode\n";
        CompilerResult r = JdkCompilerProvider.parseOutput(CODECACHE_OUTPUT, queue);
        assertEquals(2, r.queueSize());
    }
}
