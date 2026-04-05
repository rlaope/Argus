# Usage Guide

Argus provides two independent tools: a CLI for quick JVM diagnostics and an Agent with a real-time web dashboard.

---

## Argus CLI

Standalone diagnostic commands that work on any running JVM process. No agent attachment or application restart required.

**How it works**: Argus CLI uses `jcmd`, `jstat`, and JDK management APIs to query target JVM processes externally. Works on Java 11+.

### Quick Start

```bash
# Install
curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash

# List JVM processes
argus ps

# Diagnose a process (replace <pid> with actual PID)
argus info <pid>
argus gc <pid>
argus threads <pid>
argus histo <pid>
argus heap <pid>
```

### Key Commands

| Command | What it shows | When to use |
|---------|---------------|-------------|
| `argus ps` | Running JVM processes | Find the PID of your target app |
| `argus info` | JVM version, uptime, PID | Verify target JVM details |
| `argus gc` | GC collector stats, pause times | Investigate GC pressure |
| `argus gcutil` | Memory pool utilization % | Check generation fill levels |
| `argus heap` | Heap used/committed/max | Spot memory leaks |
| `argus histo` | Top heap-consuming classes | Find what's eating memory |
| `argus threads` | Thread count by state | Detect thread leaks or deadlocks |
| `argus deadlock` | Deadlocked threads | Production hang diagnosis |
| `argus metaspace` | Metaspace usage | ClassLoader leak detection |
| `argus nmt` | Native memory breakdown | Off-heap memory investigation |
| `argus profile` | CPU profiling (async-profiler) | Find hot code paths |

All commands support `--format=json` for scripting and `--source=auto|agent|jdk` to choose the data source.

---

## Argus Agent (Dashboard)

Real-time JVM monitoring via a web dashboard. Attach as a Java agent to your application.

**How it works**: The agent uses JFR (Java Flight Recorder) streaming to capture GC, CPU, memory, thread, and virtual thread events with minimal overhead. Data is served via a built-in Netty web server.

### Quick Start

```bash
# Start your app with Argus agent
java -javaagent:argus-agent.jar \
  -Dargus.gc.enabled=true \
  -Dargus.cpu.enabled=true \
  -jar your-app.jar

# Open dashboard
open http://localhost:9202/
```

### Dashboard Sections

| Section | Description | Java Version |
|---------|-------------|:------------:|
| **JVM Health** | GC pause timeline, heap usage, GC overhead | 17+ |
| **CPU Utilization** | JVM and system CPU load over time | 17+ |
| **GC Cause Distribution** | Breakdown of why GC is triggered | 17+ |
| **Allocation & Metaspace** | Allocation rate, metaspace growth, top allocating classes | 21+ |
| **Carrier Thread Distribution** | Virtual thread load across carrier threads | 21+ |
| **Profiling & Contention** | Hot methods, lock contention hotspots | 21+ |
| **Flame Graph** | Interactive CPU flame graph from execution samples | 21+ |
| **Recommendations** | Auto-generated insights from cross-correlation analysis | 21+ |
| **Virtual Threads** | Thread events, pinning alerts, hotspot analysis | 21+ |

### Interactive Console

Access `/console.html` from the dashboard header to run diagnostic commands directly in the browser. Commands execute on the attached JVM using MXBeans — no separate CLI needed.

### Configuration

All settings are passed as `-D` system properties:

| Property | Default | Description |
|----------|---------|-------------|
| `argus.server.port` | `9202` | Dashboard port |
| `argus.gc.enabled` | `true` | GC event collection |
| `argus.cpu.enabled` | `true` | CPU sampling |
| `argus.cpu.interval` | `1000` | CPU sample interval (ms) |
| `argus.allocation.enabled` | `false` | Object allocation tracking (high overhead) |
| `argus.profiling.enabled` | `false` | Method profiling (high overhead) |
| `argus.contention.enabled` | `false` | Lock contention tracking |
| `argus.metrics.prometheus.enabled` | `true` | Prometheus `/prometheus` endpoint |

---

## Spring Boot Integration

Add `argus-spring-boot-starter` to your Spring Boot 3.2+ application for zero-config JVM monitoring:

```kotlin
implementation("io.argus:argus-spring-boot-starter:0.8.0")
```

Configure via `application.yml`:

```yaml
argus:
  server:
    port: 9202
  gc:
    enabled: true
  cpu:
    enabled: true
  allocation:
    enabled: true
```

The dashboard starts automatically. Health status available at `/actuator/health/argus`. Micrometer metrics auto-registered when on classpath.
