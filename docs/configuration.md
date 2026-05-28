# Configuration

This guide covers all configuration options available in Project Argus.

## Agent Configuration

The Argus agent is configured via Java system properties.

### Available Properties

#### Core Settings
| Property | Default | Description |
|----------|---------|-------------|
| `argus.server.enabled` | `false` | Enable built-in dashboard server |
| `argus.server.port` | `9202` | WebSocket server port |
| `argus.buffer.size` | `65536` | Ring buffer size for event collection |

#### GC & Memory Settings
| Property | Default | Description |
|----------|---------|-------------|
| `argus.gc.enabled` | `true` | Enable GC monitoring |
| `argus.allocation.enabled` | `false` | Enable allocation rate tracking (high overhead) |
| `argus.allocation.threshold` | `1048576` | Minimum allocation size to track (1MB) |
| `argus.metaspace.enabled` | `true` | Enable metaspace monitoring |

#### CPU & Performance Settings
| Property | Default | Description |
|----------|---------|-------------|
| `argus.cpu.enabled` | `true` | Enable CPU monitoring |
| `argus.cpu.interval` | `1000` | CPU sampling interval (ms) |
| `argus.profiling.enabled` | `false` | Enable method profiling (high overhead) |
| `argus.profiling.interval` | `20` | Method profiling sampling interval (ms) |
| `argus.contention.enabled` | `false` | Enable lock contention tracking |
| `argus.contention.threshold` | `50` | Minimum contention duration to track (ms) |

#### Analysis Settings
| Property | Default | Description |
|----------|---------|-------------|
| `argus.correlation.enabled` | `true` | Enable correlation analysis |

#### Metrics Export Settings
| Property | Default | Description |
|----------|---------|-------------|
| `argus.metrics.prometheus.enabled` | `true` | Enable `/prometheus` endpoint |
| `argus.otlp.enabled` | `false` | Enable OTLP metrics push export |
| `argus.otlp.endpoint` | `http://localhost:4318/v1/metrics` | OTLP collector endpoint |
| `argus.otlp.interval` | `15000` | OTLP push interval (ms) |
| `argus.otlp.headers` | *(empty)* | Auth headers (`key=val,key=val`) |
| `argus.otlp.service.name` | `argus` | OTLP resource service name |

### Setting Properties

```bash
java -javaagent:argus-agent.jar \
     -Dargus.server.port=9090 \
     -Dargus.buffer.size=131072 \

     -jar your-application.jar
```

## Buffer Size Tuning

The ring buffer size affects memory usage and event handling capacity.

### Guidelines

| Application Type | Recommended Size | Memory Usage |
|------------------|------------------|--------------|
| Development/Testing | `16384` | ~1 MB |
| Standard Production | `65536` (default) | ~4 MB |
| High-throughput | `131072` | ~8 MB |
| Extreme throughput | `262144` | ~16 MB |

### Considerations

- Buffer size must be a power of 2
- Larger buffers reduce the chance of event loss under high load
- Smaller buffers use less memory

```bash
# High-throughput configuration
java -javaagent:argus-agent.jar \
     -Dargus.buffer.size=262144 \

     -jar high-traffic-app.jar
```

## GC Monitoring Configuration

GC monitoring captures garbage collection events and heap usage.

```bash
# Enable/disable GC monitoring
java -javaagent:argus-agent.jar \
     -Dargus.gc.enabled=true \

     -jar your-application.jar
```

### GC Events Captured

| Event | Description |
|-------|-------------|
| `jdk.GarbageCollection` | GC pause duration, cause, collector name |
| `jdk.GCHeapSummary` | Heap used/committed before and after GC |

### GC Metrics Available

- Total GC events count
- Total/Average/Max pause time
- GC cause distribution
- Recent GC history (last 20 events)
- Current heap usage

## CPU Monitoring Configuration

CPU monitoring tracks JVM and system CPU utilization.

```bash
# Configure CPU monitoring
java -javaagent:argus-agent.jar \
     -Dargus.cpu.enabled=true \
     -Dargus.cpu.interval=1000 \

     -jar your-application.jar
```

### CPU Metrics Available

- JVM CPU (user + system)
- System CPU
- Historical data (60-second rolling window)
- Peak CPU values

### Adjusting CPU Sampling Interval

```bash
# Sample every 500ms for more granular data
-Dargus.cpu.interval=500

# Sample every 2 seconds for lower overhead
-Dargus.cpu.interval=2000
```

## Allocation Tracking Configuration

Allocation tracking monitors object allocation rate and identifies top allocating classes.

```bash
# Configure allocation tracking
java -javaagent:argus-agent.jar \
     -Dargus.allocation.enabled=true \
     -Dargus.allocation.threshold=1024 \

     -jar your-application.jar
```

### Allocation Metrics Available

- Total allocation count
- Total bytes allocated
- Allocation rate (MB/sec)
- Peak allocation rate
- Top 10 allocating classes

### Tuning Allocation Threshold

```bash
# Track all allocations >= 512 bytes
-Dargus.allocation.threshold=512

# Track only large allocations >= 8KB
-Dargus.allocation.threshold=8192
```

## Metaspace Monitoring Configuration

Metaspace monitoring tracks class metadata memory usage.

```bash
# Enable/disable metaspace monitoring
java -javaagent:argus-agent.jar \
     -Dargus.metaspace.enabled=true \

     -jar your-application.jar
```

### Metaspace Metrics Available

- Current used/committed memory
- Peak usage
- Growth rate (MB/min)
- Class count

## Method Profiling Configuration

Method profiling identifies CPU-intensive methods using execution sampling.

**Warning**: Method profiling has higher overhead. Use with caution in production.

```bash
# Enable method profiling
java -javaagent:argus-agent.jar \
     -Dargus.profiling.enabled=true \
     -Dargus.profiling.interval=20 \

     -jar your-application.jar
```

### Method Profiling Metrics Available

- Total sample count
- Top 20 hot methods
- Method sample percentage

### Adjusting Profiling Interval

```bash
# More frequent sampling (higher accuracy, higher overhead)
-Dargus.profiling.interval=10

# Less frequent sampling (lower accuracy, lower overhead)
-Dargus.profiling.interval=50
```

## Lock Contention Configuration

Lock contention tracking monitors thread synchronization bottlenecks.

```bash
# Configure contention tracking
java -javaagent:argus-agent.jar \
     -Dargus.contention.enabled=true \
     -Dargus.contention.threshold=10 \

     -jar your-application.jar
```

### Contention Metrics Available

- Total contention events
- Total contention time
- Top 10 contention hotspots
- Per-thread contention time

### Tuning Contention Threshold

```bash
# Track contention >= 5ms
-Dargus.contention.threshold=5

# Track only severe contention >= 50ms
-Dargus.contention.threshold=50
```

## Correlation Analysis Configuration

Correlation analysis detects relationships between different metrics.

```bash
# Enable/disable correlation analysis
java -javaagent:argus-agent.jar \
     -Dargus.correlation.enabled=true \

     -jar your-application.jar
```

### Correlation Features

- **GC ↔ CPU Correlation**: Detects CPU spikes within 1 second of GC events
- **GC ↔ Pinning Correlation**: Identifies pinning increases during GC
- **Automatic Recommendations**: Provides actionable insights:
  - GC overhead warnings (> 10%)
  - Memory leak detection (sustained heap growth)
  - Lock contention hotspot alerts
  - High allocation rate warnings
  - Metaspace growth warnings

## JFR Event Configuration

Argus captures the following JFR events by default:

### Virtual Thread Events

| Event | Description | Overhead |
|-------|-------------|----------|
| `jdk.VirtualThreadStart` | Thread creation | Low |
| `jdk.VirtualThreadEnd` | Thread termination | Low |
| `jdk.VirtualThreadPinned` | Thread pinning (with stack trace) | Medium |
| `jdk.VirtualThreadSubmitFailed` | Submit failures | Low |

### GC & Memory Events

| Event | Description | Overhead |
|-------|-------------|----------|
| `jdk.GarbageCollection` | GC pause events | Low |
| `jdk.GCHeapSummary` | Heap usage snapshots | Low |
| `jdk.ObjectAllocationInNewTLAB` | Object allocation in TLAB | Medium |
| `jdk.MetaspaceSummary` | Metaspace usage | Low |

### CPU & Performance Events

| Event | Description | Overhead |
|-------|-------------|----------|
| `jdk.CPULoad` | CPU utilization (periodic) | Low |
| `jdk.ExecutionSample` | Method execution sampling | Medium-High |
| `jdk.JavaMonitorEnter` | Lock acquisition contention | Low |
| `jdk.JavaMonitorWait` | Lock wait contention | Low |

### JFR Settings

The agent configures JFR with:

- **Max Age**: 10 seconds (sliding window)
- **Max Size**: 10 MB

## Server Configuration

### Port Configuration

```bash
# Start server on custom port
java -Dargus.server.port=9090 -jar argus-server.jar
```

### Production Deployment

For production environments, consider:

1. **Bind to localhost only** (for security):
   ```bash
   # Use a reverse proxy (nginx, etc.) for external access
   ```

2. **Enable TLS** via reverse proxy:
   ```nginx
   # nginx configuration example
   server {
       listen 443 ssl;
       server_name argus.example.com;

       ssl_certificate /path/to/cert.pem;
       ssl_certificate_key /path/to/key.pem;

       location /events {
           proxy_pass http://localhost:9202;
           proxy_http_version 1.1;
           proxy_set_header Upgrade $http_upgrade;
           proxy_set_header Connection "upgrade";
       }

       location /health {
           proxy_pass http://localhost:9202;
       }
   }
   ```

## Environment Variables

You can also use environment variables (converted to system properties):

```bash
export ARGUS_SERVER_PORT=9090
export ARGUS_BUFFER_SIZE=131072

java -javaagent:argus-agent.jar \
     -Dargus.server.port=${ARGUS_SERVER_PORT} \
     -Dargus.buffer.size=${ARGUS_BUFFER_SIZE} \

     -jar your-application.jar
```

## Docker Configuration

### Dockerfile Example

```dockerfile
FROM eclipse-temurin:21-jre

COPY argus-agent.jar /opt/argus/
COPY your-application.jar /app/

ENV ARGUS_PORT=9202
ENV ARGUS_BUFFER_SIZE=65536

EXPOSE ${ARGUS_PORT}

ENTRYPOINT ["java", \
    "-javaagent:/opt/argus/argus-agent.jar", \
    "-Dargus.server.port=${ARGUS_PORT}", \
    "-Dargus.buffer.size=${ARGUS_BUFFER_SIZE}", \
    "-jar", "/app/your-application.jar"]
```

### Docker Compose Example

```yaml
version: '3.8'

services:
  app:
    build: .
    ports:
      - "9202:9202"
    environment:
      - ARGUS_PORT=9202
      - ARGUS_BUFFER_SIZE=131072
```

## Kubernetes Configuration

### Deployment Example

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: your-app
spec:
  template:
    spec:
      containers:
        - name: app
          image: your-app:latest
          env:
            - name: JAVA_TOOL_OPTIONS
              value: "-javaagent:/opt/argus/argus-agent.jar"
            - name: ARGUS_SERVER_PORT
              value: "9202"
            - name: ARGUS_BUFFER_SIZE
              value: "65536"
          ports:
            - containerPort: 9202
              name: argus
          livenessProbe:
            httpGet:
              path: /health
              port: argus
            initialDelaySeconds: 10
            periodSeconds: 5
```

## Performance Tuning

### Low Latency Configuration

```bash
java -javaagent:argus-agent.jar \
     -Dargus.buffer.size=32768 \
     -XX:+UseZGC \
     -XX:+ZGenerational \

     -jar latency-sensitive-app.jar
```

### High Throughput Configuration

```bash
java -javaagent:argus-agent.jar \
     -Dargus.buffer.size=262144 \
     -XX:+UseParallelGC \

     -jar high-throughput-app.jar
```

## Prometheus Endpoint

Prometheus metrics are exposed at `/prometheus` by default.

```bash
# Scrape metrics
curl http://localhost:9202/prometheus
```

### Prometheus scrape config

```yaml
scrape_configs:
  - job_name: 'argus'
    scrape_interval: 15s
    static_configs:
      - targets: ['localhost:9202']
    metrics_path: '/prometheus'
```

## OTLP Export Configuration

OTLP export pushes metrics to an OpenTelemetry collector in OTLP JSON format. No OpenTelemetry SDK is required — Argus hand-codes the OTLP protocol.

```bash
java -javaagent:argus-agent.jar \
     -Dargus.otlp.enabled=true \
     -Dargus.otlp.endpoint=http://localhost:4318/v1/metrics \
     -Dargus.otlp.interval=15000 \
     -jar your-application.jar
```

### With Authentication

```bash
java -javaagent:argus-agent.jar \
     -Dargus.otlp.enabled=true \
     -Dargus.otlp.endpoint=https://otel.example.com/v1/metrics \
     -Dargus.otlp.headers=Authorization=Bearer\ mytoken \
     -Dargus.otlp.service.name=my-service \
     -jar your-application.jar
```

### Metrics Exported

All 30+ metrics available via Prometheus are also exported via OTLP:

| Type | Examples |
|------|----------|
| Gauge | `argus_virtual_threads_active`, `argus_cpu_jvm_percent`, `argus_heap_used_bytes` |
| Sum (monotonic) | `argus_virtual_threads_started_total`, `argus_gc_pause_time_seconds_total` |
| Histogram | `argus_gc_pause_seconds` (`_bucket`/`_sum`/`_count`) — GC pause-time distribution |
| Per-collector counter | `argus_gc_pause_breakdown_seconds_total{gc_name,gc_cause}`, `argus_gc_events_breakdown_total{gc_name,gc_cause}` |

### OpenMetrics exposition

The `/prometheus` endpoint negotiates format by `Accept` header:

- Default (`Accept: text/plain` or absent) → Prometheus 0.0.4 text exposition.
- `Accept: application/openmetrics-text` → OpenMetrics 1.0.0 (terminated with `# EOF`),
  with trace-id **exemplars** on the GC pause histogram when a trace context is
  active (exemplars are populated once tracing correlation is wired; the plumbing
  ships now and is a no-op until then).

The OpenMetrics output passes `promtool check metrics`.

## CLI Configuration

The Argus CLI provides unified JVM diagnostics with auto source detection.

### First-time Setup

```bash
argus init
```

This creates `~/.argus/config.properties` with your preferred language and defaults.

### Recent additions (v1.1.0+)

These flags were added in the current development cycle. Each appears in the relevant per-command section below as well.

| Command | Flag | Description |
|---------|------|-------------|
| `argus profile` | `--capabilities` | Lists supported profiling events for the host platform |
| `argus profile` | `--event=<name>` | Selects the profiling event (cpu, alloc, lock, wall, etc.) |
| `argus profile` | `--duration=Ns` | Sets the profiling window in seconds |
| `argus profile` | `--save=<path>` | Saves a profile snapshot to a JSON file for later diffing |
| `argus profile` | `--diff=<path>` | Diffs the current run against a saved snapshot |
| `argus profile-gate` | `--threshold=X` | Fails if any method's CPU share grows by more than X percentage points |
| `argus profile-gate` | `--threshold-samples=N` | Ignores changes with fewer than N sample delta |
| `argus profile-gate` | `--max-regressions=K` | Fails if more than K methods regress (any amount) |
| `argus profile-gate` | `--format=json` | Emits machine-readable JSON |
| `argus profile-gate` | `--annotate=github` | Emits `::error::` / `::warning::` GitHub Actions annotations |
| `argus flame` | `--duration=Ns` | Sets the one-shot flame graph capture window |
| `argus flame` | `--output=<path>` | Saves the flame graph HTML to the given path instead of a temp file |
| `argus nmt` | `--save <path>` | Saves an NMT baseline snapshot to a file |
| `argus nmt` | `--diff <path>` | Compares current NMT state against a saved baseline |
| `argus gclog` | `--top=N` | Shows the top N causes in the aggregated pause-summary table (default: 8) |
| `argus gclog` | `--all` | Shows all causes in the pause-summary table without truncation |
| `argus gcwhy` | `--duration=Ns` | Sets the live JFR capture window when a PID is given |
| `argus gcscore` | *(PID form)* | `argus gcscore <PID>` now accepts a live PID in addition to a log file |
| `argus gcscore` | `--duration=Ns` | Live JFR capture window (PID form only) |
| `argus doctor` | `--profile` | Adds a live profile capture to the health report |
| `argus doctor` | `--profile=<file>` | Adds profile findings from a saved snapshot file |
| `argus doctor` | `--profile-duration=N` | Sets the live capture duration for `--profile` (seconds) |
| `argus suggest` | `--profile` | Adds profile-driven flag recommendations to the output |
| `argus suggest` | `--profile=<file>` | Uses a saved snapshot instead of a live capture |
| `argus suggest` | `--advanced` | Includes advanced ZGC flags (e.g. `ZAllocationSpikeTolerance`) |
| `argus zgc` | `<PID>` | One-shot ZGC health verdict via 30s live JFR capture |
| `argus zgc` | `--duration=N` | Override the JFR capture window (5–120 seconds) |
| `argus zgc` | `--save=PATH` | Capture once and persist a baseline snapshot to PATH |
| `argus zgc` | `--diff=PATH` | Capture once and compare against baseline at PATH; renders diff table instead of normal verdict |
| `argus zgc` | `--watch[=N]` | Loop: unlimited (`--watch`) or N iterations (`--watch=N`); 1-line summary per iteration, full table every 5th |
| `argus zgc` | `--interval=N` | JFR capture duration and watch loop period in seconds (default: 30, range: 10–300) |

### ZGC logging setup

To enable `argus gclog` allocation stall detection and ZGC cycle visibility, start the target JVM with the canonical unified GC log configuration:

```bash
java -XX:+UseZGC \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -jar your-app.jar
```

This produces `Allocation Stall (thread) Xms` lines that `argus gclog` parses into the **Allocation Stalls (ZGC)** section. Without `gc*` (i.e. with just `gc`), stall lines are not emitted.

For Generational ZGC on JDK 21–23:

```bash
java -XX:+UseZGC -XX:+ZGenerational \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -jar your-app.jar
```

### ZGC tuning flags

| Flag | Purpose |
|------|---------|
| `-XX:SoftMaxHeapSize=<N>g` | Soft heap ceiling; ZGC keeps committed heap below this under normal load |
| `-XX:ZAllocationSpikeTolerance=5.0` | Tolerance for short-lived allocation spikes before proactive GC (default: 2.0) |
| `-XX:+ZUncommit` | Return idle heap pages to the OS |
| `-XX:ZUncommitDelay=300` | Wait N seconds before uncommitting idle memory (default: 300) |
| `-XX:ConcGCThreads=N` | Number of concurrent GC threads; raise when cycles overlap or stalls occur |
| `-XX:+ZGenerational` | Enable Generational ZGC (JDK 21–23; default in JDK 24+) |

**Examples:**

```bash
# List supported profiling events on this machine
argus profile --capabilities

# Profile for 30 s, save snapshot, then gate against it in CI
argus profile 12345 --duration=30 --save=before.json
argus profile 12345 --duration=30 --save=after.json
argus profile-gate before.json after.json --threshold=5 --annotate=github

# One-shot flame graph saved to a specific file
argus flame 12345 --duration=20 --output=./flame.html

# Capture NMT baseline, then diff after a load test
argus nmt 12345 --save baseline.json
argus nmt 12345 --diff baseline.json

# Show all GC causes instead of the default top 8
argus gclog /var/log/gc.log --all

# Diagnose a live JVM and include a CPU profile in the report
argus doctor 12345 --profile --profile-duration=10

# GC score from a live JVM (30 s JFR window)
argus gcscore 12345 --duration=30
```

### CLI Commands

```bash
argus ps                       # List running JVM processes
argus histo <pid>              # Heap object histogram
argus histo <pid> --top 50     # Top 50 entries
argus threads <pid>            # Thread dump summary
argus gc <pid>                 # GC statistics
argus gcutil <pid>             # GC generation utilization (jstat-style)
argus heap <pid>               # Heap memory usage
argus sysprops <pid>           # System properties (--filter supported)
argus vmflag <pid>             # VM flags (--filter, --set supported)
argus nmt <pid>                # Native memory tracking
argus classloader <pid>        # Class loader hierarchy
argus jfr <pid> start          # Start Flight Recorder
argus jfr <pid> check          # Check recording status
argus info <pid>               # JVM information
argus top                      # Real-time monitoring (requires agent)
```

### Global Options

| Option | Default | Description |
|--------|---------|-------------|
| `--source=auto\|agent\|jdk` | `auto` | Data source selection |
| `--no-color` | *(off)* | Disable ANSI colors |
| `--lang=en\|ko\|ja\|zh` | `en` | Output language |
| `--format=table\|json` | `table` | Output format |
| `--host HOST` | `localhost` | Agent host (for `top` and agent source) |
| `--port PORT` | `9202` | Agent port |
| `--help, -h` | | Print usage and exit |
| `--version, -v` | | Print version and exit |

### Source Auto-detection

When `--source=auto` (default), the CLI:
1. Tries connecting to the Argus agent via HTTP
2. Falls back to JDK tools (`jcmd`) if agent is unavailable

Use `--source=agent` or `--source=jdk` to force a specific source.

### JSON Output

All commands support `--format=json` for pipeline integration:

```bash
argus gc 12345 --format=json | jq '.heapUsed'
argus histo 12345 --format=json --top 10 > heap-snapshot.json
```

### Per-command flag reference

#### `argus profile`

| Flag | Default | Description |
|------|---------|-------------|
| `--capabilities` | | Print supported events for the host and exit |
| `--event=<name>` | `cpu` | Profiling event (cpu, alloc, lock, wall, or a JFR event name) |
| `--duration=N` | `10` | Capture duration in seconds |
| `--save=<path>` | | Persist snapshot as JSON for diffing |
| `--diff=<path>` | | Compare this run against a saved snapshot |
| `--diff=BEFORE:AFTER` | | Offline diff of two snapshot files (no PID required) |
| `--top=N` | `20` | Number of methods to display |
| `--format=json` | | Machine-readable output |

#### `argus profile-gate`

Compares two profile snapshots and exits non-zero on regression. Designed for CI pipelines.

| Flag | Default | Description |
|------|---------|-------------|
| `--threshold=PCT` | `10` | Fail if any method's share grows by ≥ PCT percentage points |
| `--threshold-samples=N` | `0` | Ignore changes with sample delta < N |
| `--top=N` | `20` | Show top N regressions in the report |
| `--format=json` | | Machine-readable JSON output |
| `--annotate=github` | | Emit `::error::` / `::warning::` GitHub Actions annotations |
| `--max-regressions=K` | *(unlimited)* | Fail if more than K methods regress (any amount) |
| `--baseline-only` | | Print report but always exit 0 |

Exit codes: `0` = pass, `1` = regression detected, `2` = usage / IO error.

```bash
argus profile-gate before.json after.json --threshold=5 --format=json
```

#### `argus flame`

One-shot flame graph. Profiles a JVM and opens the result in the browser.

| Flag | Default | Description |
|------|---------|-------------|
| `--duration=N` | `10` | Capture duration in seconds |
| `--type=EVENT` | `cpu` | Profiling event (aliases `--event`) |
| `--output=PATH` | *(temp file)* | Output file path |
| `--output-format=FMT` | `flamegraph` | Output format: `flamegraph`, `tree`, `jfr`, `collapsed`, `text` |
| `--no-open` | | Do not open browser after profiling |

#### `argus nmt`

| Flag | Default | Description |
|------|---------|-------------|
| `--save <path>` | | Save current NMT state as a baseline JSON file |
| `--diff <path>` | | Compare current state against a baseline file |
| `--watch[=N]` | `2` | Live delta view, refreshing every N seconds |
| `--format=json` | | Machine-readable output |

Requires the target JVM to start with `-XX:NativeMemoryTracking=summary`.

#### `argus gclog`

| Flag | Default | Description |
|------|---------|-------------|
| `--top=N` | `8` | Show top N causes in the pause-summary table |
| `--all` | | Show all causes without truncation |
| `--phases` | | Include GC sub-phase breakdown |
| `--tenuring` | | Tenuring age-table analysis (requires `-Xlog:gc+age=debug`) |
| `--follow, -f` | | Tail the log file and refresh every 2 s |
| `--suggest-flags` | | Print only the recommended JVM flags |
| `--export=PATH` | | Export analysis as a self-contained HTML file |
| `--format=json` | | Machine-readable output |

#### `argus gcwhy`

| Flag | Default | Description |
|------|---------|-------------|
| `--duration=N` | `30` | Live JFR capture window in seconds (PID form only) |
| `--last=WINDOW` | `5m` | Analyse only pauses in the last window (e.g. `30s`, `5m`, `2h`) |
| `--format=json` | | Machine-readable output |

Accepts either a GC log file path or a live PID as its first argument.

#### `argus gcscore`

| Flag | Default | Description |
|------|---------|-------------|
| `--duration=N` | `30` | Live JFR capture window in seconds (PID form only) |
| `--format=json` | | Machine-readable output |

Accepts either a GC log file path or a live PID as its first argument.

#### `argus doctor`

| Flag | Default | Description |
|------|---------|-------------|
| `--profile` | | Capture a live CPU profile and include findings in the report |
| `--profile=<file>` | | Load a saved snapshot and include profile findings |
| `--profile-duration=N` | `5` | Live capture duration for `--profile` (seconds) |
| `--pause-threshold-ms=N` | `200` | Flag STW pauses exceeding this value as a finding |
| `--format=json` | | Machine-readable output |
| `--export=PATH` | | Export report as a self-contained HTML file |

Exit codes: `0` = healthy, `1` = warnings, `2` = critical issues.

#### `argus suggest`

| Flag | Default | Description |
|------|---------|-------------|
| `--profile` | | Run a live CPU profile and add profile-driven recommendations |
| `--profile=<file>` | | Load a saved snapshot for profile-driven recommendations |
| `--profile=<workload>` | | Optimise for a named workload: `web`, `batch`, `microservice`, `streaming` |
| `--profile-duration=N` | `5` | Live capture duration when `--profile` is used without a file |
| `--format=json` | | Machine-readable output |

#### `argus zgc`

| Flag | Default | Description |
|------|---------|-------------|
| `--duration=N` | `30` | JFR capture window in seconds (clamped to 5–120) |
| `--save=PATH` | | Capture once, render normally, and persist baseline to PATH |
| `--diff=PATH` | | Capture once, compare against baseline at PATH, render diff table (suppresses normal verdict) |
| `--watch[=N]` | | Loop indefinitely (`--watch`) or for N iterations (`--watch=N`); 1-line summary per iteration, full table every 5th |
| `--interval=N` | `30` | JFR capture duration and watch loop period in seconds (clamped to 10–300) |

### Config File

Stored at `~/.argus/config.properties`:

```properties
lang=en
default.source=auto
color=true
format=table
default.port=9202
```

### Environment Variables

| Variable | Description |
|----------|-------------|
| `ARGUS_DEBUG` | Set to any non-empty value to print full stack traces on error (equivalent to `-Dargus.debug=true`) |
| `LC_ALL` | Auto-detected for output language. Overrides `LANG` when both are set. Example: `LC_ALL=ko_KR.UTF-8` selects Korean output |
| `LANG` | Fallback locale for output language when `LC_ALL` is unset. The CLI extracts the two-letter ISO 639-1 code (e.g. `ko` from `ko_KR.UTF-8`) |

`ARGUS_DEBUG` is checked in `ArgusCli` and in commands that perform live JFR captures (e.g. `gcwhy`, `gcscore`) to decide whether to print stack traces.

## Next Steps

- [Architecture Overview](architecture.md) - Understand the internal design
- [Troubleshooting](troubleshooting.md) - Common issues and solutions
