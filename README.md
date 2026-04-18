<p align="center">
  <img src="assets/argus_logo.png" alt="Argus Logo" width="240">
</p>

<h1 align="center">Argus</h1>

<p align="center">
  <a href="https://github.com/rlaope/Argus/actions/workflows/ci.yml"><img src="https://github.com/rlaope/Argus/actions/workflows/ci.yml/badge.svg" alt="Build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/rlaope/Argus" alt="License"></a>
  <a href="https://openjdk.org"><img src="https://img.shields.io/badge/Java-11%2B-blue" alt="Java"></a>
  <a href="https://github.com/rlaope/Argus/stargazers"><img src="https://img.shields.io/github/stars/rlaope/Argus" alt="GitHub stars"></a>
</p>

> **One CLI for all JVM diagnostics.** 55 commands, zero agent required, works on Java 11+.
> GC analysis, health diagnosis, flame graphs, interactive TUI — the free alternative to GCEasy + jcmd + VisualVM combined.

---

## Quick Start

```bash
# Install (macOS / Linux)
curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash

# Windows (PowerShell)
irm https://raw.githubusercontent.com/rlaope/argus/master/install.ps1 | iex

# Try it now
argus ps                    # List JVM processes
argus doctor <pid>          # One-click health diagnosis
argus tui                   # Interactive terminal UI
```

---

## Why Argus?

| | Argus | Arthas | VisualVM | jcmd | GCEasy |
|---|---|---|---|---|---|
| CLI diagnostic | ✅ 55 cmds | ✅ | ❌ GUI | ✅ limited | ❌ |
| GC log analysis | ✅ free | ❌ | ❌ | ❌ | 💰 paid |
| Health diagnosis | ✅ doctor | ❌ | ❌ | ❌ | ❌ |
| Interactive TUI | ✅ k9s-style | ❌ | ❌ | ❌ | ❌ |
| Virtual Threads | ✅ JFR-based | ❌ | ❌ | ❌ | ❌ |
| CI/CD gate | ✅ exit codes | ❌ | ❌ | ❌ | ❌ |
| Prometheus/Grafana | ✅ native | ❌ | ❌ | ❌ | ❌ |
| K8s Helm chart | ✅ | ❌ | ❌ | ❌ | ❌ |
| Web dashboard | ✅ real-time | tunnel | ✅ | ❌ | ✅ web |
| i18n | 🌏 en/ko/ja/zh | 🇨🇳 zh | 🇬🇧 en | 🇬🇧 en | 🇬🇧 en |
| Cost | Free | Free | Free | Free | Paid |

**Arthas = debugging (bytecode, tracing). Argus = observability (monitoring, diagnosis, metrics). They complement each other.**

---

## Key Features

### `argus doctor <pid>` — One-Click Health Diagnosis

```
$ argus doctor 39113

╭─ Health Diagnosis ── pid:39113 ─────────────────────────────────────────────╮
│                                                                              │
│  ✅  Heap usage normal       41M / 256M  (16%)                               │
│  ✅  GC overhead acceptable  0.3%                                            │
│  ✅  No deadlocks detected                                                   │
│  ✅  Thread count normal     29 threads                                      │
│  ⚠   Metaspace near limit   96.5% — consider -XX:MaxMetaspaceSize           │
│  ⚠   Old Gen elevated        41.8% — monitor for promotion pressure         │
│                                                                              │
│  Overall: WARN — 2 recommendations                                           │
│                                                                              │
│  Tuning Tips                                                                 │
│  → Add -XX:MaxMetaspaceSize=256m to prevent unlimited growth                 │
│  → Run argus gclog <file> to analyze GC pressure over time                  │
│                                                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

### `argus gclog gc.log` — GC Log Analysis (GCEasy Alternative, Free)

```
$ argus gclog gc.log

╭─ GC Log Analysis ── gc.log ─────────────────────────────────────────────────╮
│                                                                              │
│  Collector:   G1GC                                                           │
│  Duration:    3h 42m  (13,320 events)                                        │
│                                                                              │
│  Pause Distribution                                                          │
│  p50    12ms   ████░░░░░░                                                    │
│  p95    48ms   ████████░░                                                    │
│  p99   124ms   ██████████                                                    │
│  max   311ms   ██████████ ← Full GC detected                                 │
│                                                                              │
│  GC Overhead: 2.1% (healthy < 5%)                                            │
│  Allocation Rate: 142 MB/s                                                   │
│  Full GC Count: 3 (potential memory pressure)                                │
│                                                                              │
│  Recommendations                                                             │
│  → Increase -Xmx to reduce Full GC frequency                                │
│  → Consider -XX:G1HeapRegionSize=16m for large heap                         │
│                                                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

### `argus watch <pid>` — Real-Time Terminal Dashboard (htop for JVM)

```
$ argus watch 39113

╭─ JVM Watch ── pid:39113 ── OpenJDK 21.0.9 ── refreshing every 2s ───────────╮
│                                                                              │
│  Heap    [███░░░░░░░░░░░░░]  41M / 256M  (16%)   ↑ +2M/s                    │
│  Metaspace [███████████████░░░░]  247M / 256M  (96%)                         │
│                                                                              │
│  CPU     [██░░░░░░░░░░░░░░]  12%    GC overhead: 0.3%                        │
│                                                                              │
│  Threads: 29 total  (RUNNABLE:11  WAITING:13  TIMED_WAITING:5)              │
│  YGC: 18 (0.163s)    FGC: 2 (0.215s)    Uptime: 57m 5s                      │
│                                                                              │
│  Top Heap Objects                                                            │
│  1. byte[]               111.0K instances  16M                               │
│  2. java.lang.String     107.2K instances   2M                               │
│  3. java.lang.Class       18.9K instances   2M                               │
│                                                                              │
│  ⚠  Metaspace at 96% — near limit                                            │
│                                                                              │
│  [q]uit  [r]efresh  [h]eap  [t]hreads  [g]c                                 │
╰──────────────────────────────────────────────────────────────────────────────╯
```

### `argus tui` — Interactive Terminal UI (k9s-style)

```
$ argus tui

╭─ Argus TUI ─────────────────────────── [R]ead  [W]rite  [?]help  [q]quit ───╮
│                                                                              │
│  JVM Processes                                                               │
│  ──────────────────────────────────────────────────────────────────────      │
│  PID     Name                         Java     Heap        Uptime            │
│  39113 ▶ com.example.MyApp            21.0.9   41M/256M    57m               │
│  29286   org.gradle.launcher          17.0.8   88M/512M    12m               │
│  11042   io.confluent.kafka.Server    11.0.21  312M/1G     3h 14m            │
│                                                                              │
│  Commands                            │  Output                               │
│  ────────────────────────────────    │  ───────────────────────────────      │
│  doctor    Health diagnosis          │  ✅ Heap OK   ⚠ Metaspace 96%        │
│  heap      Memory breakdown          │  41M / 256M  (16%)                   │
│  gc        GC statistics             │  YGC:18  FGC:2  overhead:0.3%        │
│  threads   Thread summary            │  29 threads, 0 deadlocks             │
│  profile   CPU profiling             │                                       │
│  flame     Flame graph (HTML)        │                                       │
│                                                                              │
│  [↑↓] select process  [tab] switch panel  [enter] run command               │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Additional showcase:

```
$ argus report 39113

╭─ JVM Report ── pid:39113 ── source:auto ─────────────────────────────────────╮
│                                                                              │
│   ▸ JVM Info                                                                 │
│     39113: OpenJDK 64-Bit Server VM version 21.0.9    Uptime: 57m 5s         │
│                                                                              │
│   ▸ Memory                                                                   │
│     Heap    [███░░░░░░░░░░░░░]  41M / 256M  (16%)                            │
│     Free    215M                                                             │
│                                                                              │
│   ▸ GC                                                                       │
│     S0: 0%  S1: 0%  Eden: 0%  Old: 42%  Meta: 97%                            │
│                                                                              │
│   ▸ Threads                                                                  │
│     Total: 29    Virtual: 0    Platform: 29                                  │
│     TIMED_WAITING: 5  WAITING: 13  RUNNABLE: 11                              │
│                                                                              │
│   ▸ Top Heap Objects                                                         │
│     1. byte[]                111.0K instances   16M                          │
│     2. java.lang.String      107.2K instances   2M                           │
│     3. java.lang.Class       18.9K instances   2M                            │
│                                                                              │
│   ▸ Warnings                                                                 │
│     ⚠ Metaspace at 97% — near limit                                          │
│                                                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## Command Reference

All 55 commands. No agent needed. Works on any running JVM.

**Process & System**

| Command | Description |
|---------|-------------|
| `argus ps` | List running JVM processes (with Java version, uptime) |
| `argus info <pid>` | JVM information, flags, and CPU utilization |
| `argus env <pid>` | JVM launch environment |
| `argus sysprops <pid>` | System properties (`--filter` supported) |
| `argus vmflag <pid>` | VM flags (`--filter`, `--set` supported) |
| `argus vmset <pid> Flag=val` | Set VM flag at runtime |
| `argus vmlog <pid>` | JVM unified logging control |
| `argus jmx <pid> [cmd]` | JMX agent control |

**Memory & GC**

| Command | Description |
|---------|-------------|
| `argus heap <pid>` | Heap memory with detailed pool breakdown |
| `argus histo <pid>` | Heap object histogram |
| `argus gc <pid>` | GC statistics (collectors, pauses, overhead) |
| `argus gcutil <pid>` | GC generation utilization (jstat-style) |
| `argus gccause <pid>` | GC cause with utilization stats |
| `argus gcnew <pid>` | Young generation GC detail |
| `argus gcrun <pid>` | Trigger System.gc() remotely |
| `argus metaspace <pid>` | Detailed metaspace breakdown |
| `argus nmt <pid>` | Native memory tracking |
| `argus buffers <pid>` | NIO buffer pool statistics (direct, mapped) |
| `argus finalizer <pid>` | Finalizer queue status |
| `argus diff <pid> [interval]` | Heap snapshot diff (leak detection) |
| `argus heapdump <pid>` | Generate heap dump (with STW warning) |
| `argus gclog <file>` | GC log analysis — pause distribution, tuning tips (GCEasy alternative) |
| `argus gcscore <file>` | GC Health Score Card — A–F grade with improvement hints |
| `argus gcwhy <file>` | Narrate why the worst recent GC pause happened |
| `argus gcprofile <pid>` | Allocation profiling via JFR — by stack frame, by class (`--by=class`), or folded stacks for flamegraph.pl (`--fold=FILE`) |

**Threads**

| Command | Description |
|---------|-------------|
| `argus threads <pid>` | Thread dump summary (daemon, peak counts) |
| `argus threaddump <pid>` | Full thread dump with stack traces |
| `argus deadlock <pid>` | Detect Java-level deadlocks |
| `argus pool <pid>` | Thread pool analysis |

**Runtime & Class Loading**

| Command | Description |
|---------|-------------|
| `argus classloader <pid>` | Class loader hierarchy |
| `argus classstat <pid>` | Class loading statistics |
| `argus sc <pid> <pattern>` | Search loaded classes by glob pattern |
| `argus compiler <pid>` | JIT compiler and code cache stats |
| `argus compilerqueue <pid>` | JIT compilation queue |
| `argus stringtable <pid>` | Interned string table statistics |
| `argus symboltable <pid>` | Symbol table statistics |
| `argus dynlibs <pid>` | Loaded native libraries |

**Profiling & Diagnostics**

| Command | Description |
|---------|-------------|
| `argus profile <pid>` | CPU/allocation/lock profiling (async-profiler) |
| `argus jfr <pid> start\|stop\|check\|dump` | Flight Recorder control |
| `argus jfranalyze <file.jfr>` | Analyze JFR recording (GC, CPU, hot methods, I/O) |
| `argus logger <pid>` | View and change log levels at runtime |
| `argus events <pid>` | VM internal event log (safepoints, deopt, GC) |
| `argus report <pid>` | Comprehensive diagnostic report |
| `argus doctor <pid>` | One-click JVM health diagnosis with tuning recommendations |
| `argus flame <pid>` | One-shot flame graph — profiles, generates HTML, opens browser |
| `argus suggest <pid>` | JVM flag optimization based on workload analysis |
| `argus ci` | CI/CD health gate — exit codes + GitHub annotations |
| `argus compare <pid1> <pid2>` | Side-by-side JVM comparison (live or baseline) |
| `argus slowlog <pid>` | Real-time slow method detection via JFR streaming |

**Monitoring**

| Command | Description |
|---------|-------------|
| `argus top` | Real-time monitoring (agent required) |
| `argus watch <pid>` | Real-time terminal dashboard (htop for JVM) |
| `argus tui [pid]` | Interactive terminal UI — browse all commands (k9s-style) |
| `argus init` | First-time setup (language selection) |

**Global Options:** `--source=auto|agent|jdk` · `--lang=en|ko|ja|zh` · `--format=table|json` · `--no-color`

> Full output examples for all 55 commands: [docs/cli-commands.md](docs/cli-commands.md)

---

## Argus Agent & Dashboard

Attach to your JVM for real-time monitoring — WebSocket streaming, flame graphs, Prometheus export, OTLP push.

```bash
# Attach agent
java -javaagent:$(argus-agent --path) -jar your-app.jar

# Open dashboard
open http://localhost:9202/

# Enable profiling + flame graph
java -javaagent:~/.argus/argus-agent.jar \
     -Dargus.profiling.enabled=true \
     -Dargus.contention.enabled=true \
     -jar your-app.jar
```

```
[Argus] JVM Observability Platform v1.0.0
[Argus] JFR streaming started
[Argus] Dashboard: http://localhost:9202/
```

**Dashboard endpoints:** `/` UI · `/prometheus` metrics · `/flame-graph` · `/gc-analysis` · `/correlation` · `/export` (CSV/JSON/JSONL)

> See [Configuration Guide](docs/configuration.md) for all agent properties.

---

## Monitoring Stack

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

> Helm chart, Grafana dashboard JSON, and K8s setup: [docs/kubernetes.md](docs/kubernetes.md)

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

---

## Spring Boot Integration

Zero-configuration auto-setup for Spring Boot 3.2+ applications.

**Maven:**
```xml
<dependency>
  <groupId>io.argus</groupId>
  <artifactId>argus-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.argus:argus-spring-boot-starter:1.0.0'
```

```properties
# application.properties
argus.server.port=9202
argus.profiling.enabled=true
argus.contention.enabled=true
```

The `argus-micrometer` module also provides a `MeterBinder` exposing ~25 JVM metrics to any Micrometer-compatible registry (Prometheus, Datadog, InfluxDB, etc.).

---

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   argus-agent   │───▶│   argus-core    │◀───│  argus-server   │
│  (JFR Stream)   │    │ (Config/Buffer) │    │ (Netty/Analysis)│
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                     ▲                       │
         │                     │                       ▼
         ▼              ┌──────┴──────┐     ┌─────────────────┐
┌─────────────────┐     │  argus-cli  │     │ argus-frontend  │
│   Target JVM    │◀────│  (Unified   │     │  (Dashboard UI) │
└─────────────────┘     │  Diagnostic)│     └─────────────────┘
       ▲  jcmd          └─────────────┘            │
       │                   │        │         WebSocket +
       └───────────────────┘   HTTP Polling   Flame Graph
         Direct JDK Access    (Agent Mode)

┌──────────────────────────┐    ┌──────────────────────────────────┐
│    argus-micrometer      │    │   argus-spring-boot-starter      │
│  (MeterBinder, ~25       │    │  (Spring Boot 3.2+ auto-config)  │
│   metrics bridge)        │    │                                  │
└──────────────────────────┘    └──────────────────────────────────┘
```

| Module | Description |
|--------|-------------|
| **argus-core** | Shared config, event models, ring buffer |
| **argus-agent** | Java agent with JFR streaming engine |
| **argus-server** | Netty HTTP/WS server, 10 analyzers, Prometheus + OTLP |
| **argus-frontend** | Static dashboard with Chart.js and d3-flamegraph |
| **argus-cli** | 55 diagnostic commands, auto source detection, i18n |
| **argus-micrometer** | Micrometer MeterBinder exposing ~25 JVM metrics |
| **argus-spring-boot-starter** | Spring Boot 3.2+ auto-configuration for Argus agent |

---

## Java Version Compatibility

| Feature | Java 11+ | Java 17+ | Java 21+ |
|---------|:--------:|:--------:|:--------:|
| CLI (55 commands) | ✅ | ✅ | ✅ |
| Dashboard & Web UI | — | ✅ | ✅ |
| GC Analysis | CLI only | ✅ MXBean | ✅ JFR |
| Virtual Thread Monitoring | — | — | ✅ JFR |
| Flame Graph | — | — | ✅ JFR |
| Micrometer Metrics | — | ✅ | ✅ |
| Spring Boot Starter | — | ✅ | ✅ |

---

## Installation

```bash
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash

# Windows (PowerShell)
irm https://raw.githubusercontent.com/rlaope/argus/master/install.ps1 | iex

# Build from source
git clone https://github.com/rlaope/argus.git && cd argus
./gradlew :argus-cli:fatJar

# Uninstall
rm -rf ~/.argus
```

Shell completions (bash, zsh, fish, PowerShell) are installed automatically.

---

## Contributing & License

Contributions welcome — bugs, features, docs, tests.
See [CONTRIBUTING.md](CONTRIBUTING.md) · [Architecture](docs/architecture.md) · [CLI Reference](docs/cli-commands.md)

Maintainer: [@rlaope](https://github.com/rlaope)

MIT License — see [LICENSE](LICENSE) for details.
