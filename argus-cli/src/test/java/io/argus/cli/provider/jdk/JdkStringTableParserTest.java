package io.argus.cli.provider.jdk;

import io.argus.cli.model.StringTableResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdkStringTableParserTest {

    private static final String SAMPLE_OUTPUT =
            "StringTable statistics:\n" +
            "Number of buckets       :     65536 =  524288 bytes, each 8\n" +
            "Number of entries       :     12345 =  197520 bytes, each 16\n" +
            "Number of literals      :     12345 =  456789 bytes, avg  37.000\n" +
            "Total footprint         :           = 1178597 bytes\n" +
            "Average bucket size     :     0.188\n" +
            "Variance of bucket size :     0.188\n" +
            "Std. dev. of bucket size:     0.434\n" +
            "Maximum bucket size     :     3\n";

    @Test
    void parseOutput_bucketCount() {
        StringTableResult r = JdkStringTableProvider.parseOutput(SAMPLE_OUTPUT);
        assertEquals(65536, r.bucketCount());
    }

    @Test
    void parseOutput_bucketBytes() {
        StringTableResult r = JdkStringTableProvider.parseOutput(SAMPLE_OUTPUT);
        assertEquals(524288, r.bucketBytes());
    }

    @Test
    void parseOutput_entryCount() {
        StringTableResult r = JdkStringTableProvider.parseOutput(SAMPLE_OUTPUT);
        assertEquals(12345, r.entryCount());
    }

    @Test
    void parseOutput_literalCount() {
        StringTableResult r = JdkStringTableProvider.parseOutput(SAMPLE_OUTPUT);
        assertEquals(12345, r.literalCount());
        assertEquals(456789, r.literalBytes());
    }

    @Test
    void parseOutput_totalFootprint() {
        StringTableResult r = JdkStringTableProvider.parseOutput(SAMPLE_OUTPUT);
        assertEquals(1178597, r.totalBytes());
    }

    @Test
    void parseOutput_avgLiteralSize() {
        StringTableResult r = JdkStringTableProvider.parseOutput(SAMPLE_OUTPUT);
        assertEquals(37.0, r.avgLiteralSize(), 0.001);
    }

    @Test
    void parseOutput_emptyInput() {
        StringTableResult r = JdkStringTableProvider.parseOutput("");
        assertEquals(0, r.bucketCount());
        assertEquals(0, r.totalBytes());
    }
}
