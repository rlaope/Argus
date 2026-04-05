package io.argus.spring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ArgusProperties} defaults and configuration mapping.
 */
class ArgusPropertiesTest {

    @Test
    void defaultsMatchAgentConfigDefaults() {
        ArgusProperties props = new ArgusProperties();

        assertTrue(props.isEnabled());
        assertEquals(65536, props.getBufferSize());
        assertTrue(props.getServer().isEnabled());
        assertEquals(9202, props.getServer().getPort());
        assertTrue(props.getGc().isEnabled());
        assertTrue(props.getCpu().isEnabled());
        assertEquals(1000, props.getCpu().getIntervalMs());
        assertFalse(props.getAllocation().isEnabled());
        assertEquals(1048576, props.getAllocation().getThreshold());
        assertTrue(props.getMetaspace().isEnabled());
        assertFalse(props.getProfiling().isEnabled());
        assertEquals(20, props.getProfiling().getIntervalMs());
        assertFalse(props.getContention().isEnabled());
        assertEquals(50, props.getContention().getThresholdMs());
        assertTrue(props.getCorrelation().isEnabled());
        assertTrue(props.getMetrics().getPrometheus().isEnabled());
        assertFalse(props.getMetrics().getOtlp().isEnabled());
        assertEquals("http://localhost:4318/v1/metrics", props.getMetrics().getOtlp().getEndpoint());
        assertEquals(15000, props.getMetrics().getOtlp().getIntervalMs());
        assertEquals("argus", props.getMetrics().getOtlp().getServiceName());
        assertTrue(props.getMetrics().getMicrometer().isEnabled());
    }

    @Test
    void settersWorkCorrectly() {
        ArgusProperties props = new ArgusProperties();

        props.setEnabled(false);
        assertFalse(props.isEnabled());

        props.setBufferSize(131072);
        assertEquals(131072, props.getBufferSize());

        props.getServer().setPort(8080);
        assertEquals(8080, props.getServer().getPort());

        props.getGc().setEnabled(false);
        assertFalse(props.getGc().isEnabled());

        props.getCpu().setIntervalMs(500);
        assertEquals(500, props.getCpu().getIntervalMs());

        props.getAllocation().setEnabled(true);
        assertTrue(props.getAllocation().isEnabled());

        props.getAllocation().setThreshold(524288);
        assertEquals(524288, props.getAllocation().getThreshold());
    }

    @Test
    void nestedObjectsAreNeverNull() {
        ArgusProperties props = new ArgusProperties();

        assertNotNull(props.getServer());
        assertNotNull(props.getGc());
        assertNotNull(props.getCpu());
        assertNotNull(props.getAllocation());
        assertNotNull(props.getMetaspace());
        assertNotNull(props.getProfiling());
        assertNotNull(props.getContention());
        assertNotNull(props.getCorrelation());
        assertNotNull(props.getMetrics());
        assertNotNull(props.getMetrics().getPrometheus());
        assertNotNull(props.getMetrics().getOtlp());
        assertNotNull(props.getMetrics().getMicrometer());
    }
}
