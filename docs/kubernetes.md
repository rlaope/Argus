# Argus on Kubernetes

This guide covers deploying Argus in a Kubernetes cluster, collecting JVM metrics, and integrating with Prometheus and Grafana.

---

## Quick Start

Add Argus to an existing Kubernetes Deployment in three steps.

**Step 1 — Add the agent JAR to your image.**

```dockerfile
FROM eclipse-temurin:21-jre
COPY argus-cli-all.jar /argus/argus-cli.jar
COPY app.jar /app/app.jar
ENTRYPOINT ["java", "-javaagent:/argus/argus-cli.jar", "-jar", "/app/app.jar"]
```

**Step 2 — Expose the metrics port and add scrape annotations.**

```yaml
metadata:
  annotations:
    argus.io/scrape: "true"
    argus.io/port: "4040"
spec:
  containers:
    - ports:
        - containerPort: 4040
          name: argus-metrics
```

**Step 3 — Annotate the namespace so Prometheus finds the pods.**

```bash
kubectl annotate namespace my-app argus.io/monitored=true
```

Prometheus will now scrape `http://<pod-ip>:4040/prometheus` automatically.

---

## Deployment Methods

### Method A: `-javaagent` in Dockerfile (recommended)

Bundle the agent directly in your application image. This is the simplest approach and works with any JVM application.

```dockerfile
FROM eclipse-temurin:21-jre

# Copy agent and application
COPY --from=builder /build/argus-cli-all.jar /argus/argus-cli.jar
COPY --from=builder /build/app.jar /app/app.jar

# Agent starts automatically with the JVM
ENTRYPOINT ["java", \
  "-javaagent:/argus/argus-cli.jar", \
  "-Dargus.server.port=4040", \
  "-jar", "/app/app.jar"]
```

### Method B: Init container pattern

Use an init container to inject the agent JAR via a shared volume. This avoids modifying the application image.

```yaml
initContainers:
  - name: argus-init
    image: ghcr.io/rlaope/argus-init:latest
    command: ["cp", "/argus-cli.jar", "/argus-volume/argus-cli.jar"]
    volumeMounts:
      - name: argus-volume
        mountPath: /argus-volume

containers:
  - name: app
    image: my-app:latest
    env:
      - name: JAVA_TOOL_OPTIONS
        value: "-javaagent:/argus/argus-cli.jar"
    volumeMounts:
      - name: argus-volume
        mountPath: /argus

volumes:
  - name: argus-volume
    emptyDir: {}
```

### Method C: Spring Boot starter (no agent JAR needed)

Add the Argus Spring Boot starter to `pom.xml` or `build.gradle`. No `-javaagent` flag is required — the starter auto-configures the agent at startup.

**Maven:**
```xml
<dependency>
  <groupId>io.argus</groupId>
  <artifactId>argus-spring-boot-starter</artifactId>
  <version>0.9.0</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.argus:argus-spring-boot-starter:0.9.0'
```

The `/prometheus` and `/argus` endpoints are auto-registered alongside your application's existing endpoints.

---

## Prometheus Integration

### Annotation-based scraping

Add these annotations to your Pod or Deployment template. Prometheus scrapes any pod with `argus.io/scrape: "true"` automatically when the OTel Collector or a Prometheus Operator is configured with the matching relabel rules (see `deploy/otel-collector-config.yaml`).

```yaml
metadata:
  annotations:
    argus.io/scrape: "true"       # Enable scraping
    argus.io/port: "4040"         # Argus metrics port (default: 4040)
    argus.io/path: "/prometheus"  # Metrics path (default: /prometheus)
```

### ServiceMonitor (Prometheus Operator)

If you use the [Prometheus Operator](https://prometheus-operator.dev/), create a `ServiceMonitor` instead of relying on pod annotations.

First expose Argus as a `Service`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-app-argus
  labels:
    app: my-app
    argus: "true"
spec:
  selector:
    app: my-app
  ports:
    - name: argus-metrics
      port: 4040
      targetPort: 4040
```

Then create the `ServiceMonitor`:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: my-app-argus
  labels:
    release: kube-prometheus-stack  # match your Prometheus Operator label selector
spec:
  selector:
    matchLabels:
      argus: "true"
  endpoints:
    - port: argus-metrics
      path: /prometheus
      interval: 15s
```

---

## Helm Chart

A Helm chart is provided under `charts/argus` for deploying the Argus agent configuration alongside your application.

```bash
# Add the Argus Helm repository
helm repo add argus https://rlaope.github.io/argus/charts
helm repo update

# Install with default values
helm install argus argus/argus \
  --namespace monitoring \
  --create-namespace

# Install with custom values
helm install argus argus/argus \
  --namespace monitoring \
  --set agent.port=4040 \
  --set prometheus.serviceMonitor.enabled=true \
  --set grafana.dashboards.enabled=true
```

Key chart values:

| Value | Default | Description |
|---|---|---|
| `agent.port` | `4040` | Argus metrics server port |
| `agent.jvmArgs` | `""` | Additional JVM arguments |
| `prometheus.serviceMonitor.enabled` | `false` | Create a ServiceMonitor |
| `grafana.dashboards.enabled` | `false` | Auto-provision Grafana dashboard |
| `otelCollector.enabled` | `false` | Deploy OTel Collector as a sidecar |

---

## Grafana

When `grafana.dashboards.enabled=true` in the Helm chart, Argus auto-provisions a dashboard via a `ConfigMap` labelled `grafana_dashboard: "1"`. Grafana's sidecar picks this up automatically.

The dashboard includes:

- Virtual thread active count and start/end rate
- GC pause time (max, avg, total) and heap usage
- CPU usage (JVM user, JVM system, machine total)
- Metaspace usage and class count
- Lock contention top-10 hotspots
- Allocation rate and top-10 allocating classes
- Method profiling top-20 hot methods

To import the dashboard manually, load `deploy/grafana-dashboard.json` from the Argus repository into Grafana via **Dashboards → Import**.

---

## Example Deployment YAML

A complete Deployment with the Argus agent, Downward API environment variables, and a readiness probe:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
  namespace: default
spec:
  replicas: 2
  selector:
    matchLabels:
      app: my-app
  template:
    metadata:
      labels:
        app: my-app
      annotations:
        argus.io/scrape: "true"
        argus.io/port: "4040"
    spec:
      containers:
        - name: my-app
          image: my-app:latest
          ports:
            - containerPort: 8080
              name: http
            - containerPort: 4040
              name: argus-metrics
          env:
            - name: JAVA_TOOL_OPTIONS
              value: "-javaagent:/argus/argus-cli.jar -Dargus.server.port=4040"
            # Downward API: exposes K8s identity to Argus for metric labels
            - name: ARGUS_POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: ARGUS_NAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace
            - name: ARGUS_NODE_NAME
              valueFrom:
                fieldRef:
                  fieldPath: spec.nodeName
          readinessProbe:
            httpGet:
              path: /argus/health
              port: 4040
            initialDelaySeconds: 10
            periodSeconds: 15
          volumeMounts:
            - name: argus-volume
              mountPath: /argus
      initContainers:
        - name: argus-init
          image: ghcr.io/rlaope/argus-init:latest
          command: ["cp", "/argus-cli.jar", "/argus-volume/argus-cli.jar"]
          volumeMounts:
            - name: argus-volume
              mountPath: /argus-volume
      volumes:
        - name: argus-volume
          emptyDir: {}
```

### Downward API environment variables

Argus reads these environment variables to attach Kubernetes identity labels to every Prometheus metric:

| Variable | Downward API field | Label in metrics |
|---|---|---|
| `ARGUS_POD_NAME` | `metadata.name` | `pod` |
| `ARGUS_NAMESPACE` | `metadata.namespace` | `namespace` |
| `ARGUS_NODE_NAME` | `spec.nodeName` | `node` |

When running in Kubernetes, metrics will look like:

```
argus_virtual_threads_active{pod="my-app-7d9f",namespace="default",node="node-1"} 42
```

When running outside Kubernetes (local dev, bare metal), the label suffix is omitted:

```
argus_virtual_threads_active 42
```

---

## OTel Collector (OTLP Push Mode)

Instead of Prometheus scraping, you can configure Argus to push metrics to an OpenTelemetry Collector via OTLP.

**Configure the Argus agent:**

```bash
-Dargus.metrics.otlp.endpoint=http://otel-collector:4318/v1/metrics
```

**Deploy the collector** using the provided configuration:

```bash
kubectl create configmap otel-collector-config \
  --from-file=config.yaml=deploy/otel-collector-config.yaml \
  --namespace monitoring
```

The collector config at `deploy/otel-collector-config.yaml` supports two pipelines:

- **`metrics/otlp`** — receives OTLP push from Argus, enriches with K8s attributes via `k8sattributes` processor, and re-exports to Prometheus.
- **`metrics/scrape`** — scrapes the Argus `/prometheus` endpoint directly from pods (annotation-based discovery) and remote-writes to Prometheus.

**RBAC for the `k8sattributes` processor:**

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: otel-collector
rules:
  - apiGroups: [""]
    resources: ["pods", "namespaces", "nodes"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: otel-collector
subjects:
  - kind: ServiceAccount
    name: otel-collector
    namespace: monitoring
roleRef:
  kind: ClusterRole
  name: otel-collector
  apiGroup: rbac.authorization.k8s.io
```

---

## Troubleshooting

### Argus metrics port not reachable

**Symptom:** `curl http://<pod-ip>:4040/prometheus` times out or is refused.

**Causes and fixes:**
- The agent is not loaded. Verify `JAVA_TOOL_OPTIONS` or `-javaagent` is set and the JAR path is correct. Check pod logs for `Argus agent started`.
- The port is not declared in the container spec. Add `containerPort: 4040` to the container ports list.
- A network policy is blocking the port. Add an ingress rule allowing traffic on port 4040 from the Prometheus namespace.

### JFR not available — metrics are empty

**Symptom:** The `/prometheus` endpoint returns only `argus_build_info`.

**Cause:** The JVM does not have JFR access. JFR requires a commercial JDK or OpenJDK 11+.

**Fix:** Use `eclipse-temurin:21-jre` or another OpenJDK 11+ image. Add `-XX:+FlightRecorder` if running on JDK 11 where it is not enabled by default.

### Bind address conflict

**Symptom:** `Address already in use: 0.0.0.0:4040` in pod logs.

**Fix:** Change the port with `-Dargus.server.port=<free-port>` and update the pod annotations and `containerPort` accordingly.

### JFR permissions denied in container

**Symptom:** `java.lang.RuntimeException: JFR recording failed — permission denied`.

**Cause:** The container runs with a read-only filesystem or a restrictive seccomp profile.

**Fix:** Either allow write access to `/tmp` (JFR writes recording files there) or mount a writable `emptyDir` at `/tmp`:

```yaml
volumeMounts:
  - name: tmp
    mountPath: /tmp
volumes:
  - name: tmp
    emptyDir: {}
```

### K8s labels not appearing on metrics

**Symptom:** Metrics are emitted without `pod`, `namespace`, or `node` labels.

**Cause:** The Downward API environment variables are not set.

**Fix:** Add the three `env` entries shown in the [Example Deployment YAML](#example-deployment-yaml) section. Argus detects Kubernetes by the presence of `ARGUS_NAMESPACE` (or `POD_NAMESPACE`). Without at least one of these, no K8s labels are added.
