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
     --enable-preview \
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
     --enable-preview \
     -jar high-traffic-app.jar
```

## GC Monitoring Configuration

GC monitoring captures garbage collection events and heap usage.

```bash
# Enable/disable GC monitoring
java -javaagent:argus-agent.jar \
     -Dargus.gc.enabled=true \
     --enable-preview \
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
     --enable-preview \
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
     --enable-preview \
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
     --enable-preview \
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
     --enable-preview \
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
     --enable-preview \
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
     --enable-preview \
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
     --enable-preview \
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
    "--enable-preview", \
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
              value: "-javaagent:/opt/argus/argus-agent.jar --enable-preview"
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
     --enable-preview \
     -jar latency-sensitive-app.jar
```

### High Throughput Configuration

```bash
java -javaagent:argus-agent.jar \
     -Dargus.buffer.size=262144 \
     -XX:+UseParallelGC \
     --enable-preview \
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

## CLI Configuration

The CLI tool (`argus top`) connects to a running Argus server via HTTP.

```bash
# Default: localhost:9202, 1s refresh
argus

# Custom settings
argus --host 192.168.1.100 --port 9202 --interval 2

# Disable colors (for piping/logging)
argus --no-color
```

| Option | Default | Description |
|--------|---------|-------------|
| `--host`, `-h` | `localhost` | Argus server host |
| `--port`, `-p` | `9202` | Argus server port |
| `--interval`, `-i` | `1` | Refresh interval in seconds |
| `--no-color` | *(off)* | Disable ANSI colors |

## Next Steps

- [Architecture Overview](architecture.md) - Understand the internal design
- [Troubleshooting](troubleshooting.md) - Common issues and solutions
