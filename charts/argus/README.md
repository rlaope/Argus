# Argus Helm Chart

Helm chart for [Argus](https://github.com/rlaope/Argus) — a JVM observability agent that provides Prometheus metrics, Grafana dashboards, and alerting for JVM applications.

> **Note**: Argus is a JVM agent — this chart does **not** deploy application pods. It installs the supporting Kubernetes resources (ConfigMap, ServiceMonitor, PrometheusRule, Grafana dashboard) that wire your existing JVM applications into your observability stack.

## Prerequisites

- Kubernetes 1.21+
- Helm 3.8+
- [Prometheus Operator](https://github.com/prometheus-operator/prometheus-operator) (for ServiceMonitor and PrometheusRule)
- [Grafana](https://grafana.com/docs/grafana/latest/setup-grafana/installation/helm/) with sidecar dashboard discovery (for auto-dashboard import)

## Installation

```bash
helm install argus ./charts/argus
```

With custom values:

```bash
helm install argus ./charts/argus \
  --set agent.port=9202 \
  --set serviceMonitor.enabled=true \
  --set prometheusRule.enabled=true \
  --set grafana.enabled=true
```

## Instrumenting Your Application

### Step 1 — Label your pods

Add the label that the ServiceMonitor uses for discovery:

```yaml
metadata:
  labels:
    argus.io/enabled: "true"
```

### Step 2 — Add the agent

**Option A: Java agent**

Mount the agent JAR and inject the ConfigMap opts:

```yaml
env:
  - name: JAVA_TOOL_OPTIONS
    value: "-javaagent:/opt/argus/argus-agent.jar $(ARGUS_JAVA_OPTS)"
  - name: ARGUS_JAVA_OPTS
    valueFrom:
      configMapKeyRef:
        name: argus-config
        key: ARGUS_JAVA_OPTS
```

**Option B: Spring Boot Starter**

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.rlaope</groupId>
  <artifactId>argus-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```groovy
// Gradle
implementation 'io.github.rlaope:argus-spring-boot-starter:1.0.0'
```

### Step 3 — Expose the metrics port

Your application's Service must expose the Argus port with the name `argus-metrics`:

```yaml
ports:
  - name: argus-metrics
    port: 9202
    targetPort: 9202
```

## Configuration

### Agent

| Parameter | Description | Default |
|-----------|-------------|---------|
| `agent.port` | Metrics server port | `9202` |
| `agent.bindAddress` | Bind address | `"0.0.0.0"` |
| `agent.gc.enabled` | Enable GC metrics | `true` |
| `agent.cpu.enabled` | Enable CPU metrics | `true` |
| `agent.cpu.intervalMs` | CPU sampling interval (ms) | `1000` |
| `agent.allocation.enabled` | Enable allocation tracking | `false` |
| `agent.allocation.threshold` | Allocation threshold (bytes) | `1048576` |
| `agent.metaspace.enabled` | Enable Metaspace metrics | `true` |
| `agent.profiling.enabled` | Enable profiling | `false` |
| `agent.profiling.intervalMs` | Profiling interval (ms) | `20` |
| `agent.contention.enabled` | Enable lock contention tracking | `false` |
| `agent.contention.thresholdMs` | Contention threshold (ms) | `50` |
| `agent.correlation.enabled` | Enable metric correlation | `true` |

### ServiceMonitor

| Parameter | Description | Default |
|-----------|-------------|---------|
| `serviceMonitor.enabled` | Create ServiceMonitor | `true` |
| `serviceMonitor.interval` | Scrape interval | `15s` |
| `serviceMonitor.scrapeTimeout` | Scrape timeout | `10s` |
| `serviceMonitor.path` | Metrics path | `/prometheus` |
| `serviceMonitor.port` | Port name on Service | `argus-metrics` |
| `serviceMonitor.selector` | Pod label selector | `argus.io/enabled: "true"` |
| `serviceMonitor.additionalLabels` | Extra labels on ServiceMonitor | `{}` |
| `serviceMonitor.namespaceSelector` | Namespace selector | `{}` |

### PrometheusRule

| Parameter | Description | Default |
|-----------|-------------|---------|
| `prometheusRule.enabled` | Create PrometheusRule | `true` |
| `prometheusRule.rules.gcOverhead.enabled` | Enable GC overhead alert | `true` |
| `prometheusRule.rules.gcOverhead.threshold` | GC overhead ratio threshold | `0.10` |
| `prometheusRule.rules.gcOverhead.duration` | Alert firing duration | `5m` |
| `prometheusRule.rules.gcOverhead.severity` | Alert severity | `warning` |
| `prometheusRule.rules.memoryLeak.enabled` | Enable memory leak alert | `true` |
| `prometheusRule.rules.memoryLeak.duration` | Alert firing duration | `10m` |
| `prometheusRule.rules.memoryLeak.severity` | Alert severity | `critical` |
| `prometheusRule.rules.highCpu.enabled` | Enable high CPU alert | `true` |
| `prometheusRule.rules.highCpu.threshold` | CPU ratio threshold | `0.80` |
| `prometheusRule.rules.highCpu.duration` | Alert firing duration | `5m` |
| `prometheusRule.rules.highCpu.severity` | Alert severity | `warning` |

### Grafana

| Parameter | Description | Default |
|-----------|-------------|---------|
| `grafana.enabled` | Create dashboard ConfigMap | `true` |
| `grafana.sidecarLabel` | Label key for Grafana sidecar | `grafana_dashboard` |
| `grafana.sidecarLabelValue` | Label value for Grafana sidecar | `"1"` |
| `grafana.folder` | Dashboard folder name | `"Argus"` |

### NetworkPolicy

| Parameter | Description | Default |
|-----------|-------------|---------|
| `networkPolicy.enabled` | Create NetworkPolicy | `false` |
| `networkPolicy.ingressFrom` | Ingress source selectors | Prometheus namespace |

## Example: Production Values

```yaml
agent:
  port: 9202
  gc:
    enabled: true
  cpu:
    enabled: true
    intervalMs: 500
  allocation:
    enabled: true
    threshold: 524288
  metaspace:
    enabled: true
  profiling:
    enabled: true
    intervalMs: 10
  contention:
    enabled: true
    thresholdMs: 25
  correlation:
    enabled: true

serviceMonitor:
  enabled: true
  interval: 10s
  additionalLabels:
    prometheus: kube-prometheus

prometheusRule:
  enabled: true
  additionalLabels:
    prometheus: kube-prometheus
  rules:
    gcOverhead:
      threshold: 0.05
      severity: critical

networkPolicy:
  enabled: true
```

## Alerts

| Alert | Condition | Severity |
|-------|-----------|----------|
| `ArgusGCOverhead` | `argus_gc_overhead_ratio > 0.10` for 5m | warning |
| `ArgusMemoryLeak` | `argus_gc_leak_suspected == 1` for 10m | critical |
| `ArgusHighCPU` | `argus_cpu_jvm_user_ratio + argus_cpu_jvm_system_ratio > 0.80` for 5m | warning |

## Uninstall

```bash
helm uninstall argus
```
