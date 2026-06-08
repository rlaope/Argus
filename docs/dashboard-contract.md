# Dashboard Metric Contract

This file is the shared contract for the Grafana dashboard and the local Argus
dashboard. Keep it in sync with `PrometheusMetricsCollector`, the local REST
handlers, and `docs/grafana-dashboard.json`.

## Surfaces

| Surface | Window | Source | Primary use |
|---|---:|---|---|
| Grafana | minutes to days | `/prometheus` or OpenMetrics scrape | Fleet-wide trends, alerting, and capacity review |
| Local dashboard | seconds to minutes | Netty REST endpoints plus WebSocket events | Live JVM triage and selected-pod drilldown |
| Fleet | latest scrape | Aggregator scrape cache | Find unhealthy pods and route to drilldowns |
| Profiles | selected window | `/profile/query` and `/profile/diff` | Explain CPU, allocation, lock, and wall-time hotspots |
| Console | point-in-time | `/api/commands` and `/api/exec` | Run JVM diagnostics against the selected process or pod |

## Labels

Argus metrics can carry Kubernetes identity labels when the process has the
corresponding environment variables. Grafana variables must keep an `All`
option so the same dashboard works for local scrapes with no Kubernetes labels.

| Label | Source | Notes |
|---|---|---|
| `namespace` | Kubernetes metadata | Optional; empty in local mode |
| `deployment` | Kubernetes metadata | Optional; may be absent for naked pods |
| `pod` | Kubernetes metadata | Optional; used for local drilldown links |
| `instance` | Prometheus scrape target | Available from Prometheus, not emitted by Argus itself |
| `version` | `argus_build_info` | Argus implementation version or `dev` |
| `jdk_version` | `argus_build_info` | Runtime JVM version |

## Metrics

| Domain | Prometheus metric | Labels | Local API equivalent | Owner |
|---|---|---|---|---|
| Build | `argus_build_info` | `version`, `jdk_version`, optional K8s labels | `/config` runtime block | `argus-server` |
| Scrape health | `argus_scrape_duration_seconds` | optional K8s labels | aggregator scrape status in `/fleet/list` | `argus-server` |
| JVM memory | `jvm_memory_used_bytes` | `jvm_memory_pool_name`, optional K8s labels | `/gc-analysis`, `/metaspace-metrics` | `argus-server` |
| JVM memory | `jvm_memory_committed_bytes` | `jvm_memory_pool_name`, optional K8s labels | `/gc-analysis`, `/metaspace-metrics` | `argus-server` |
| JVM memory | `jvm_memory_used_after_last_gc_bytes` | `jvm_memory_pool_name`, optional K8s labels | `/gc-analysis` | `argus-server` |
| JVM GC | `jvm_gc_duration_seconds` | histogram `le`, optional K8s labels | `/gc-analysis` pause histogram | `argus-server` |
| JVM CPU | `jvm_cpu_recent_utilization_ratio` | optional K8s labels | `/cpu-metrics` | `argus-server` |
| JVM threads | `jvm_thread_count` | optional K8s labels | `/metrics`, `/active-threads` | `argus-server` |
| JVM classes | `jvm_class_count` | optional K8s labels | `/metaspace-metrics` | `argus-server` |
| Legacy heap | `argus_heap_used_bytes` | optional K8s labels | `/gc-analysis` | `argus-server` |
| Legacy heap | `argus_heap_committed_bytes` | optional K8s labels | `/gc-analysis` | `argus-server` |
| Heap pressure | `argus_heap_usage_ratio` | optional K8s labels | `/gc-analysis` | `argus-server` |
| GC events | `argus_gc_events_total` | optional K8s labels | `/gc-analysis` | `argus-server` |
| GC pause | `argus_gc_pause_time_seconds_total` | optional K8s labels | `/gc-analysis` | `argus-server` |
| GC pause | `argus_gc_pause_time_seconds_max` | optional K8s labels | `/gc-analysis` | `argus-server` |
| GC pause | `argus_gc_pause_time_seconds_avg` | optional K8s labels | `/gc-analysis` | `argus-server` |
| GC pause histogram | `argus_gc_pause_seconds` | histogram `le`, optional K8s labels | `/gc-analysis` pause histogram | `argus-server` |
| GC pressure | `argus_gc_overhead_ratio` | optional K8s labels | `/gc-analysis` | `argus-server` |
| GC pressure | `argus_gc_overhead_warning` | optional K8s labels | `/gc-analysis` | `argus-server` |
| GC allocation | `argus_gc_allocation_rate_kbps` | optional K8s labels | `/gc-analysis` | `argus-server` |
| GC promotion | `argus_gc_promotion_rate_kbps` | optional K8s labels | `/gc-analysis` | `argus-server` |
| Leak detection | `argus_gc_leak_suspected` | optional K8s labels | `/gc-analysis` | `argus-server` |
| Leak detection | `argus_gc_leak_confidence` | optional K8s labels | `/gc-analysis` | `argus-server` |
| GC breakdown | `argus_gc_pause_breakdown_seconds_total` | `gc_name`, `gc_cause`, optional K8s labels | `/gc-analysis` collector breakdown | `argus-server` |
| GC breakdown | `argus_gc_events_breakdown_total` | `gc_name`, `gc_cause`, optional K8s labels | `/gc-analysis` collector breakdown | `argus-server` |
| Virtual threads | `argus_virtual_threads_started_total` | optional K8s labels | `/metrics`, WebSocket events | `argus-server` |
| Virtual threads | `argus_virtual_threads_ended_total` | optional K8s labels | `/metrics`, WebSocket events | `argus-server` |
| Virtual threads | `argus_virtual_threads_submit_failed_total` | optional K8s labels | `/metrics`, WebSocket events | `argus-server` |
| Virtual threads | `argus_virtual_threads_active` | optional K8s labels | `/metrics`, `/active-threads` | `argus-server` |
| Pinning | `argus_virtual_threads_pinned_total` | optional K8s labels | `/pinning-analysis` | `argus-server` |
| Pinning | `argus_virtual_threads_pinned_unique_stacks` | optional K8s labels | `/pinning-analysis` | `argus-server` |
| Carriers | `argus_carrier_threads` | optional K8s labels | `/carrier-threads` | `argus-server` |
| Carriers | `argus_carrier_threads_virtual_handled_total` | optional K8s labels | `/carrier-threads` | `argus-server` |
| Carriers | `argus_carrier_threads_avg_per_carrier` | optional K8s labels | `/carrier-threads` | `argus-server` |
| Legacy CPU | `argus_cpu_jvm_user_ratio` | optional K8s labels | `/cpu-metrics` | `argus-server` |
| Legacy CPU | `argus_cpu_jvm_system_ratio` | optional K8s labels | `/cpu-metrics` | `argus-server` |
| Legacy CPU | `argus_cpu_machine_total_ratio` | optional K8s labels | `/cpu-metrics` | `argus-server` |
| Metaspace | `argus_metaspace_reserved_bytes` | optional K8s labels | `/metaspace-metrics` | `argus-server` |
| Legacy metaspace | `argus_metaspace_used_bytes` | optional K8s labels | `/metaspace-metrics` | `argus-server` |
| Legacy metaspace | `argus_metaspace_committed_bytes` | optional K8s labels | `/metaspace-metrics` | `argus-server` |
| Legacy metaspace | `argus_metaspace_classes_loaded` | optional K8s labels | `/metaspace-metrics` | `argus-server` |
| Allocation | `argus_allocation_total` | optional K8s labels | `/allocation-analysis` | `argus-server` |
| Allocation | `argus_allocation_bytes_total` | optional K8s labels | `/allocation-analysis` | `argus-server` |
| Allocation | `argus_allocation_rate_bytes_per_second` | optional K8s labels | `/allocation-analysis` | `argus-server` |
| Allocation | `argus_allocation_class_bytes` | `class`, optional K8s labels | `/allocation-analysis` top classes | `argus-server` |
| Contention | `argus_contention_events_total` | optional K8s labels | `/contention-analysis` | `argus-server` |
| Contention | `argus_contention_time_seconds_total` | optional K8s labels | `/contention-analysis` | `argus-server` |
| Contention | `argus_contention_hotspot_events` | `monitor`, optional K8s labels | `/contention-analysis` hotspots | `argus-server` |
| Profiling | `argus_profiling_samples_total` | optional K8s labels | `/method-profiling`, `/flame-graph` | `argus-server` |
| Profiling | `argus_profiling_method_samples` | `class`, `method`, optional K8s labels | `/method-profiling` top methods | `argus-server` |

## Maintenance Rules

- Add a row here before adding a Grafana panel that references a new metric.
- Keep Grafana variables optional and `includeAll=true` for fleet labels.
- Apply `namespace`, `deployment`, `pod`, and `instance` variable matchers to
  every Grafana metric selector so fleet filters affect panel data and local
  unlabeled scrapes still match the `All` regex.
- Prefer semconv `jvm_*` names for standard JVM signals; keep `argus_*` for
  Argus-specific diagnostics and legacy compatibility.
- Drilldown links should preserve pod context with `pod`, `event`, and `range`
  query parameters where applicable.
