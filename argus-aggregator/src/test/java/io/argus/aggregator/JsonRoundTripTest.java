package io.argus.aggregator;

import io.argus.aggregator.http.JsonReader;
import io.argus.aggregator.http.JsonWriter;
import io.argus.aggregator.model.AlertEvent;
import io.argus.aggregator.model.FleetSummary;
import io.argus.aggregator.model.MetricSample;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.Tile;
import io.argus.aggregator.model.TileColor;
import io.argus.aggregator.model.TileMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonRoundTripTest {

    @Test
    void tileListProducesValidJson() {
        PodTarget target = new PodTarget(
                "prod/p-1", "prod", "p-1", "payment", "10.0.0.1", 7070,
                Instant.parse("2026-05-26T10:00:00Z"),
                Instant.parse("2026-05-26T10:00:30Z"),
                true);
        TileMetrics m = new TileMetrics(50.0, 1.5, 30.0, 100, false);
        Tile tile = new Tile("prod/p-1", TileColor.GREEN, target, m, 0, "/?pod=prod%2Fp-1");
        String json = JsonWriter.tileList(List.of(tile), 1, 1);
        assertTrue(json.contains("\"podId\":\"prod/p-1\""));
        assertTrue(json.contains("\"color\":\"green\""));
        assertTrue(json.contains("\"totalCount\":1"));
        assertTrue(json.contains("\"filteredCount\":1"));
        assertTrue(json.contains("\"scrapeUrl\":\"http://10.0.0.1:7070\""));
    }

    @Test
    void summaryEmptyFleet() {
        FleetSummary s = new FleetSummary(
                0, 0, 0, 0, 0, 0, 0, 0,
                FleetSummary.MinMaxAvg.empty(),
                FleetSummary.MinMaxAvg.empty(),
                FleetSummary.MinMaxAvg.empty(),
                0L, 0, null, null);
        String json = JsonWriter.summary(s);
        assertTrue(json.contains("\"totalTargets\":0"));
        assertTrue(json.contains("\"worstPodId\":null"));
        assertTrue(json.contains("\"min\":null"));
    }

    @Test
    void podDetailContainsAllSections() {
        PodTarget target = new PodTarget(
                "n/p", "n", "p", "d", "h", 7070,
                Instant.parse("2026-05-26T10:00:00Z"),
                Instant.parse("2026-05-26T10:00:30Z"),
                true);
        TileMetrics m = new TileMetrics(50.0, 1.5, 30.0, 100, false);
        Tile tile = new Tile("n/p", TileColor.GREEN, target, m, 0, "/?pod=n%2Fp");
        MetricSample sample = new MetricSample(
                Instant.parse("2026-05-26T10:00:30Z"), 50.0, 1.5, 30.0, 100);
        AlertEvent alert = new AlertEvent(
                "n/p/gc", "n/p", "gc", "m", 0.2, 0.1, ">", "warning",
                Instant.parse("2026-05-26T10:00:20Z"), true);
        String json = JsonWriter.podDetail(tile, List.of(alert), 3600, List.of(sample));
        assertTrue(json.contains("\"tile\":"));
        assertTrue(json.contains("\"alerts\":["));
        assertTrue(json.contains("\"history\":"));
        assertTrue(json.contains("\"windowSeconds\":3600"));
        assertTrue(json.contains("\"sampleCount\":1"));
    }

    @Test
    void errorEnvelope() {
        String json = JsonWriter.error(404, "not found");
        assertEquals("{\"error\":{\"code\":404,\"message\":\"not found\"}}", json);
    }

    @Test
    void readerParsesFlatRegistration() {
        String body = "{\"podId\":\"prod/p-1\",\"namespace\":\"prod\",\"podName\":\"p-1\","
                + "\"deployment\":\"payment\",\"host\":\"10.0.0.1\",\"port\":7070}";
        Map<String, String> parsed = JsonReader.parseFlatObject(body);
        assertNotNull(parsed);
        assertEquals("prod/p-1", parsed.get("podId"));
        assertEquals("10.0.0.1", parsed.get("host"));
        assertEquals("7070", parsed.get("port"));
    }

    @Test
    void readerRejectsNonObject() {
        assertNull(JsonReader.parseFlatObject("[]"));
        assertNull(JsonReader.parseFlatObject("not json"));
        assertNull(JsonReader.parseFlatObject(""));
    }

    @Test
    void readerHandlesUnicodeEscape() {
        Map<String, String> parsed = JsonReader.parseFlatObject("{\"k\":\"a\\u0041b\"}");
        assertNotNull(parsed);
        assertEquals("aAb", parsed.get("k"));
    }

    @Test
    void readerHandlesSlashAndStandardEscapes() {
        Map<String, String> parsed = JsonReader.parseFlatObject(
                "{\"path\":\"\\/foo\\/bar\",\"tab\":\"a\\tb\"}");
        assertNotNull(parsed);
        assertEquals("/foo/bar", parsed.get("path"));
        assertEquals("a\tb", parsed.get("tab"));
    }

    @Test
    void writerAvoidsScientificNotation() {
        FleetSummary s = new FleetSummary(
                0, 0, 0, 0, 0, 0, 0, 0,
                new FleetSummary.MinMaxAvg(0.00000001, 1000000.0, 0.5),
                FleetSummary.MinMaxAvg.empty(),
                FleetSummary.MinMaxAvg.empty(),
                0L, 0, null, null);
        String json = JsonWriter.summary(s);
        assertFalse(json.contains("E"), "scientific notation leaked: " + json);
        assertFalse(json.contains("e+"), "scientific notation leaked: " + json);
        assertFalse(json.contains("e-"), "scientific notation leaked: " + json);
    }
}
