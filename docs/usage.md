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
| `argus ps` | Running JVM processes (version, uptime) | Find the PID of your target app |
| `argus info` | JVM version, uptime, CPU%, flags | Verify target JVM details |
| `argus gc` | GC collector stats, pause times | Investigate GC pressure |
| `argus gcutil` | Memory pool utilization % | Check generation fill levels |
| `argus gcrun` | Trigger System.gc() remotely | Force GC before heap dump |
| `argus heap` | Heap used/committed/max | Spot memory leaks |
| `argus histo` | Top heap-consuming classes | Find what's eating memory |
| `argus buffers` | NIO direct/mapped buffer pools | Diagnose direct buffer leaks |
| `argus threads` | Thread count by state, daemon, peak | Detect thread leaks or deadlocks |
| `argus threaddump` | Full thread dump with stack traces | jstack replacement |
| `argus deadlock` | Deadlocked threads | Production hang diagnosis |
| `argus sc` | Search loaded classes by pattern | Classpath conflicts |
| `argus logger` | View/change log levels at runtime | Production debugging |
| `argus events` | VM internal event log | Safepoint/deopt analysis |
| `argus compilerqueue` | JIT compilation queue | Slow startup diagnosis |
| `argus jfranalyze` | Analyze JFR recording files | Post-mortem analysis |
| `argus metaspace` | Metaspace usage | ClassLoader leak detection |
| `argus nmt` | Native memory breakdown | Off-heap memory investigation |
| `argus profile` | CPU profiling (async-profiler) | Find hot code paths |
| `argus doctor` | Health diagnosis with tuning recs | "Why is my app slow?" |
| `argus gclog` | GC log analysis (GCEasy alternative) | GC tuning |
| `argus flame` | One-shot flame graph + browser open | CPU hotspot analysis |
| `argus watch` | Real-time terminal dashboard | Continuous monitoring |
| `argus suggest` | JVM flag optimization by workload | Configuration tuning |

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
implementation("io.argus:argus-spring-boot-starter:1.1.0")
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

---

## Diagnose a ZGC Outage

A step-by-step walkthrough for investigating a ZGC-related latency spike or OOM.

### Step 0 — Capture a healthy baseline (pre-incident)

During a known-good window, capture a baseline snapshot so you have something to diff against later:

```bash
argus zgc <PID> --save=baseline.txt
```

Store this file somewhere accessible (e.g. `/tmp/zgc-baseline.txt` or a shared volume). If you already have a baseline from a previous incident window, skip this step.

### Step 1 — Get a verdict in 30 seconds

```bash
argus zgc <PID>
```

`argus zgc` attaches to the JVM via JMX, starts a 30-second JFR recording with `settings=profile`, and prints a HEALTHY / WARNING / UNHEALTHY verdict with allocation stall counts, cycle overlap status, SoftMax breach detection, and STW pause averages. When stalls are present, the output also shows a **Top alloc sources during capture** block with the top-5 allocation call sites from the same JFR recording — no separate profile step needed in most cases.

If the target JVM is not using ZGC, the command exits immediately with a message showing the active collector. Confirm with `argus gc <PID>` and switch with `-XX:+UseZGC` (JDK 15+) or `-XX:+UseZGC -XX:+ZGenerational` (JDK 21–23).

### Step 2 — Run doctor for cross-cutting findings

If the verdict is WARNING or UNHEALTHY, run the full health check:

```bash
argus doctor <PID>
```

`argus doctor` fires all health rules, including the ZGC-specific `ZgcSoftMaxBreachRule` (WARNING) and `ZgcCycleOverlapRule` (CRITICAL), alongside general heap, CPU, and thread rules. Exit code `2` means critical findings require immediate action.

### Step 3 — Profile allocations if stalls are present

If Step 1 reported allocation stalls, review the **Top alloc sources** block first. If the hot site is your own code, investigate it directly. If you need a longer or more detailed profile:

```bash
argus profile <PID> --event=alloc --duration=30
```

This shows the top allocation call sites by stack frame. Address the top allocators first — reducing allocation rate is often more effective than raising `-Xmx` alone.

### Step 4 — Apply recommendations and confirm

After tuning (raise `-Xmx`, set `-XX:SoftMaxHeapSize`, raise `-XX:ConcGCThreads`, or fix hot allocation sites), confirm the verdict improved. If you captured a baseline in Step 0, use diff for a precise comparison:

```bash
argus zgc <PID> --diff=baseline.txt
```

A healthy post-tuning run shows no REGRESSION rows in the diff, and a standalone run returns `Verdict: HEALTHY  — ZGC is keeping up.` with no stalls and no overlap.

---

## CI/CD Integration

Add JVM health checks to your pipeline. Exit codes are machine-readable: `0=pass`, `1=warnings`, `2=critical`.

```yaml
- name: JVM Health Check
  uses: rlaope/Argus/action@master
  with:
    command: ci
    fail-on: critical
    format: github-annotations
```

```bash
argus ci --pid=auto --fail-on=critical --format=summary
```

For profile regression gates between builds:

```bash
argus profile <pid> --duration=30 --save=before.json
# ... deploy new build ...
argus profile <pid> --duration=30 --save=after.json
argus profile-gate before.json after.json --threshold=5 --annotate=github
```

---

## Monitoring Stack (Prometheus / OTLP / Docker)

Native Prometheus + Grafana integration. Deploy to Kubernetes with the included Helm chart.

```bash
# Prometheus scrape endpoint (no extra config needed)
curl http://localhost:9202/prometheus

# Export to OpenTelemetry Collector
java -javaagent:~/.argus/argus-agent.jar \
     -Dargus.otlp.enabled=true \
     -Dargus.otlp.endpoint=http://localhost:4318/v1/metrics \
     -jar your-app.jar

# Docker — diagnose any JVM on the host
docker run --pid=host ghcr.io/rlaope/argus doctor
docker run --pid=host ghcr.io/rlaope/argus watch
```

> Helm chart, Grafana dashboard JSON, and K8s setup: [docs/kubernetes.md](kubernetes.md)

---

## Continuous ZGC monitoring during a deploy

Use this workflow to catch ZGC regressions introduced by a new release before declaring the deploy stable.

1. **Before the deploy**, capture a pre-deploy baseline:
   ```bash
   argus zgc <PID> --save=pre-deploy.txt
   ```

2. **After the deploy** (allow 1–2 minutes for JVM warm-up), run 10 minutes of continuous monitoring at 60-second intervals:
   ```bash
   argus zgc <PID> --watch=10 --interval=60
   ```
   Each iteration prints a 1-line summary showing heap, cycles, stalls, and mark-end delta from the previous iteration. Every 5th iteration prints the full diagnosis table. Ctrl-C at any point stops the loop, cleans up the JFR recording, and prints a final summary.

3. **If any iteration shows ✘ stalls or ⚠ committed heap growth**, diff against the pre-deploy baseline before declaring success:
   ```bash
   argus zgc <PID> --diff=pre-deploy.txt
   ```
   Any REGRESSION row (✘) needs investigation. New stalls and softMax breaches are the most critical signals and should block the deploy from going fully live.
