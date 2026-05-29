package io.argus.server.metrics;

import io.argus.core.config.AgentConfig;
import io.argus.core.event.EventType;
import io.argus.core.event.GCEvent;
import io.argus.server.analysis.CPUAnalyzer;
import io.argus.server.analysis.CarrierThreadAnalyzer;
import io.argus.server.analysis.GCAnalyzer;
import io.argus.server.analysis.MetaspaceAnalyzer;
import io.argus.server.analysis.PinningAnalyzer;
import io.argus.server.state.ActiveThreadsRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates OTel JVM semantic-convention compliance (workstream W1): that the
 * Prometheus exporter dual-emits the standard {@code jvm_*} series alongside the
 * legacy {@code argus_*} series, that the legacy series are gated behind
 * {@code argus.metrics.legacyNames}, that Argus-unique metrics keep the
 * {@code argus.*} namespace regardless of the flag, and that the
 * {@link SemconvMetrics} mapping table is the single source of truth.
 */
class SemconvMetricsComplianceTest {

    private PrometheusMetricsCollector collector(AgentConfig config) {
        GCAnalyzer gc = new GCAnalyzer();
        for (double s : new double[]{0.008, 0.04, 0.2, 1.5}) {
            gc.recordGCEvent(new GCEvent(
                    EventType.GC_PAUSE, Instant.now(),
                    (long) (s * 1_000_000_000L), "G1 Young Generation", "G1 Evacuation Pause",
                    200L * 1024 * 1024, 80L * 1024 * 1024, 512L * 1024 * 1024));
        }
        return new PrometheusMetricsCollector(
                config,
                new ServerMetrics(),
                new ActiveThreadsRegistry(),
                new PinningAnalyzer(),
                new CarrierThreadAnalyzer(),
                gc,
                new CPUAnalyzer(),
                null,
                new MetaspaceAnalyzer(),
                null, null);
    }

    /** Extracts the distinct metric series names (the token before '{' or whitespace). */
    private Set<String> seriesNames(String exposition) {
        Set<String> names = new HashSet<>();
        for (String line : exposition.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int cut = line.length();
            int brace = line.indexOf('{');
            int space = line.indexOf(' ');
            if (brace >= 0) cut = Math.min(cut, brace);
            if (space >= 0) cut = Math.min(cut, space);
            names.add(line.substring(0, cut));
        }
        return names;
    }

    // --- Criterion 4 & 5: dual-emit, mapping-table-driven names ---

    @Test
    void emits_standard_jvm_series_alongside_legacy_by_default() {
        String out = collector(AgentConfig.defaults()).collectMetrics(false);
        Set<String> names = seriesNames(out);

        // Standard jvm_* series present.
        assertTrue(names.contains("jvm_memory_used_bytes"), "jvm_memory_used_bytes missing");
        assertTrue(names.contains("jvm_memory_committed_bytes"), "jvm_memory_committed_bytes missing");
        assertTrue(names.contains("jvm_memory_used_after_last_gc_bytes"),
                "jvm_memory_used_after_last_gc_bytes missing");
        assertTrue(names.contains("jvm_thread_count"), "jvm_thread_count missing");
        assertTrue(names.contains("jvm_class_count"), "jvm_class_count missing");
        assertTrue(names.contains("jvm_cpu_recent_utilization_ratio"),
                "jvm_cpu_recent_utilization_ratio missing");
        assertTrue(out.contains("# TYPE jvm_gc_duration_seconds histogram"),
                "jvm_gc_duration_seconds histogram missing");

        // Legacy argus_* series still present by default.
        assertTrue(names.contains("argus_heap_used_bytes"), "legacy argus_heap_used_bytes missing");
        assertTrue(names.contains("argus_virtual_threads_active"),
                "legacy argus_virtual_threads_active missing");
        assertTrue(out.contains("# TYPE argus_gc_pause_seconds histogram"),
                "legacy argus_gc_pause_seconds histogram missing");
    }

    @Test
    void emitted_jvm_names_match_the_mapping_table() {
        String out = collector(AgentConfig.defaults()).collectMetrics(false);
        Set<String> names = seriesNames(out);

        for (String name : names) {
            if (!name.startsWith("jvm_")) continue;
            // A name matches if it is a table entry directly, or is a histogram
            // component line (_bucket/_sum/_count) of a table entry.
            boolean known = SemconvMetrics.ALL.stream().anyMatch(m -> {
                String pn = m.prometheusName();
                if (m.type() == SemconvMetrics.Type.HISTOGRAM) {
                    return name.equals(pn) || name.equals(pn + "_bucket")
                            || name.equals(pn + "_sum") || name.equals(pn + "_count");
                }
                return name.equals(pn);
            });
            assertTrue(known, "emitted jvm_* series not in mapping table: " + name);
        }
    }

    @Test
    void disabling_legacy_names_drops_argus_duplicates_but_keeps_jvm_and_unique() {
        AgentConfig noLegacy = AgentConfig.builder().legacyMetricNames(false).build();
        String out = collector(noLegacy).collectMetrics(false);
        Set<String> names = seriesNames(out);

        // jvm_* still present.
        assertTrue(names.contains("jvm_memory_used_bytes"));
        assertTrue(names.contains("jvm_thread_count"));
        assertTrue(out.contains("# TYPE jvm_gc_duration_seconds histogram"));

        // Legacy duplicates gone.
        assertFalse(names.contains("argus_heap_used_bytes"),
                "argus_heap_used_bytes must be gated off");
        assertFalse(names.contains("argus_heap_committed_bytes"),
                "argus_heap_committed_bytes must be gated off");
        assertFalse(names.contains("argus_virtual_threads_active"),
                "argus_virtual_threads_active must be gated off");
        assertFalse(names.contains("argus_metaspace_used_bytes"),
                "argus_metaspace_used_bytes must be gated off");
        assertFalse(names.contains("argus_metaspace_classes_loaded"),
                "argus_metaspace_classes_loaded must be gated off");
        assertFalse(names.contains("argus_cpu_jvm_user_ratio"),
                "argus_cpu_jvm_user_ratio must be gated off");
        assertFalse(out.contains("# TYPE argus_gc_pause_seconds histogram"),
                "legacy argus_gc_pause_seconds histogram must be gated off");
    }

    // --- Criterion 6: Argus-unique metrics keep argus.* and are never gated ---

    @Test
    void argus_unique_metrics_emitted_regardless_of_legacy_flag() {
        String[] unique = {
                "argus_gc_leak_confidence",
                "argus_gc_leak_suspected",
                "argus_gc_overhead_ratio",
                "argus_gc_allocation_rate_kbps",
                "argus_carrier_threads_avg_per_carrier",
                "argus_virtual_threads_started_total",
                "argus_metaspace_reserved_bytes",
        };
        String withLegacy = collector(AgentConfig.defaults()).collectMetrics(false);
        String noLegacy = collector(AgentConfig.builder().legacyMetricNames(false).build())
                .collectMetrics(false);
        Set<String> withNames = seriesNames(withLegacy);
        Set<String> withoutNames = seriesNames(noLegacy);

        for (String u : unique) {
            assertTrue(withNames.contains(u), u + " missing with legacy names on");
            assertTrue(withoutNames.contains(u), u + " missing with legacy names off (must never be gated)");
        }
    }

    // --- Criterion 5: mapping table covers every exported standard metric, correct units ---

    @Test
    void mapping_table_units_are_correct() {
        assertEquals("s", SemconvMetrics.GC_DURATION.unit());
        assertEquals("By", SemconvMetrics.MEMORY_USED.unit());
        assertEquals("By", SemconvMetrics.MEMORY_COMMITTED.unit());
        assertEquals("By", SemconvMetrics.MEMORY_USED_AFTER_LAST_GC.unit());
        assertEquals("s", SemconvMetrics.CPU_TIME.unit());
        assertEquals("1", SemconvMetrics.CPU_RECENT_UTILIZATION.unit());

        // Prometheus names are the dotted OTel names with dots→underscores.
        for (SemconvMetrics.Metric m : SemconvMetrics.ALL) {
            assertFalse(m.prometheusName().contains("."),
                    "prometheus name must not contain dots: " + m.prometheusName());
            assertEquals(m.otelName().replace('.', '_'),
                    stripUnitSuffix(m),
                    "prometheus name must derive from otel name: " + m.otelName());
        }
    }

    @Test
    void mapping_table_declares_required_attributes() {
        assertTrue(SemconvMetrics.MEMORY_USED.attributes().contains(SemconvMetrics.ATTR_MEMORY_POOL_NAME));
        assertTrue(SemconvMetrics.MEMORY_COMMITTED.attributes().contains(SemconvMetrics.ATTR_MEMORY_POOL_NAME));
        assertTrue(SemconvMetrics.MEMORY_USED_AFTER_LAST_GC.attributes()
                .contains(SemconvMetrics.ATTR_MEMORY_POOL_NAME));
    }

    /**
     * The table must not declare attributes that are never actually emitted.
     * Argus only keeps an aggregate GC pause histogram, so {@code jvm.gc.duration}
     * cannot be split per {@code jvm.gc.name}/{@code jvm.gc.action}; the table must
     * therefore NOT declare those attributes (the per-collector breakdown lives in
     * the Argus-unique {@code argus_gc_pause_breakdown_*} series instead).
     */
    @Test
    void gc_duration_does_not_declare_unemitted_split_attributes() {
        assertFalse(SemconvMetrics.GC_DURATION.attributes().contains(SemconvMetrics.ATTR_GC_NAME),
                "jvm.gc.duration must not declare jvm.gc.name: no per-collector histogram is emitted");
        assertFalse(SemconvMetrics.GC_DURATION.attributes().contains(SemconvMetrics.ATTR_GC_ACTION),
                "jvm.gc.duration must not declare jvm.gc.action: no per-action histogram is emitted");
    }

    /**
     * Every {@code jvm_gc_duration_seconds} histogram line emitted on the Prometheus
     * path must carry no per-collector/per-action labels beyond the K8s base set and
     * the bucket {@code le} label — proving the declared (now empty) attribute set
     * matches what is actually exported.
     */
    @Test
    void prometheus_gc_duration_carries_no_split_labels() {
        String out = collector(AgentConfig.defaults()).collectMetrics(false);
        for (String line : out.split("\n")) {
            if (line.startsWith("jvm_gc_duration_seconds")) {
                assertFalse(line.contains("jvm_gc_name") || line.contains("gc_name"),
                        "jvm_gc_duration_seconds must not be split by gc name: " + line);
                assertFalse(line.contains("jvm_gc_action") || line.contains("gc_action"),
                        "jvm_gc_duration_seconds must not be split by gc action: " + line);
            }
        }
    }

    /** Strips the trailing unit token from a prometheus name to compare with the otel base. */
    private String stripUnitSuffix(SemconvMetrics.Metric m) {
        String p = m.prometheusName();
        String otelUnderscore = m.otelName().replace('.', '_');
        if (p.startsWith(otelUnderscore)) {
            return otelUnderscore;
        }
        return p;
    }

    // --- Criterion 4: /prometheus stays valid (every series has HELP/TYPE, no dup) ---

    @Test
    void every_series_has_help_and_type_and_no_duplicate_series() {
        String out = collector(AgentConfig.defaults()).collectMetrics(false);
        Set<String> declaredHelp = new HashSet<>();
        Set<String> declaredType = new HashSet<>();
        List<String> seriesLines = new ArrayList<>();

        for (String line : out.split("\n")) {
            if (line.startsWith("# HELP ")) {
                declaredHelp.add(line.substring(7).split(" ", 2)[0]);
            } else if (line.startsWith("# TYPE ")) {
                declaredType.add(line.substring(7).split(" ", 2)[0]);
            } else if (!line.isBlank() && !line.startsWith("#")) {
                seriesLines.add(line);
            }
        }

        Pattern seriesName = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)");
        Set<String> seenSeries = new HashSet<>();
        for (String line : seriesLines) {
            Matcher mm = seriesName.matcher(line);
            assertTrue(mm.find(), "unparseable series line: " + line);
            String name = mm.group(1);
            // Histogram component lines (_bucket/_sum/_count) inherit the base name's HELP/TYPE.
            String base = name;
            for (String suffix : new String[]{"_bucket", "_sum", "_count"}) {
                if (base.endsWith(suffix)) {
                    base = base.substring(0, base.length() - suffix.length());
                    break;
                }
            }
            assertTrue(declaredHelp.contains(base) || declaredHelp.contains(name),
                    "series without HELP: " + name);
            assertTrue(declaredType.contains(base) || declaredType.contains(name),
                    "series without TYPE: " + name);

            // No exact-duplicate series (same name + labels). Strip the value.
            String key = line.substring(0, Math.max(line.lastIndexOf(' '), 0));
            assertTrue(seenSeries.add(key), "duplicate series line: " + key);
        }
    }
}
