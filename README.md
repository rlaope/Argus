<p align="center">
  <img src="assets/argus_logo.png" alt="Argus Logo" width="240">
</p>

# Argus

> Lightweight JVM diagnostic toolkit for Java 21+. No agent required for CLI diagnostics.

Two independent tools in one package:

- **`argus` CLI** — 17 diagnostic commands that work on any running JVM via `jcmd`/`jstat`
- **Argus Agent** — Real-time web dashboard with JFR streaming, flame graphs, and metric export

```bash
curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash
```

---

<br>

## Argus CLI

Diagnose any running JVM process directly from the terminal. No agent, no instrumentation, no restart needed.

### Commands

| Command | Description |
|---------|-------------|
| `argus ps` | List running JVM processes |
| `argus histo <pid>` | Heap object histogram |
| `argus threads <pid>` | Thread dump summary |
| `argus gc <pid>` | GC statistics |
| `argus gcutil <pid>` | GC generation utilization (jstat-style) |
| `argus heap <pid>` | Heap memory with detailed metrics |
| `argus sysprops <pid>` | System properties (`--filter` supported) |
| `argus vmflag <pid>` | VM flags (`--filter`, `--set` supported) |
| `argus nmt <pid>` | Native memory tracking |
| `argus classloader <pid>` | Class loader hierarchy |
| `argus jfr <pid> start\|stop\|check\|dump` | Flight Recorder control |
| `argus diff <pid> [interval]` | Heap snapshot diff (leak detection) |
| `argus report <pid>` | Comprehensive diagnostic report |
| `argus info <pid>` | JVM information and flags |
| `argus top` | Real-time monitoring (agent required) |
| `argus init` | First-time setup (language selection) |

### `argus report` — Comprehensive Diagnostic

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

### `argus histo` — Heap Object Histogram

```
$ argus histo 39113 --top 5

╭─ Heap Histogram ── pid:39113 ── source:auto ─────────────────────────────────╮
│                                                                              │
│    #  Class                                                  Count      Size │
│ ────  ────────────────────────────────────────────────  ──────────  ──────── │
│    1  byte[]                                               111,050       16M │
│    2  java.lang.String                                     107,249        2M │
│    3  java.lang.Class                                       18,914        2M │
│    4  ConcurrentHashMap.Node                                62,917        2M │
│    5  java.lang.Object[]                                    25,496        1M │
│                                                                              │
│ Total: 718.6K objects · 40M                                                  │
╰──────────────────────────────────────────────────────────────────────────────╯
```

### `argus gcutil` — GC Generation Utilization

```
$ argus gcutil 39113

╭─ GC Utilization ── pid:39113 ── source:auto ─────────────────────────────────╮
│                                                                              │
│ S0      S1      Eden    Old     Meta    CCS     YGC    FGC    GCT            │
│ ──────────────────────────────────────────────────────────────────────       │
│ 0.0%    0.0%    0.0%    41.8%   96.5%   87.1%   18     2      10.000         │
│                                                                              │
│   S0    [░░░░░░░░░░░░░░░░░░░░]    0.0%                                       │
│   S1    [░░░░░░░░░░░░░░░░░░░░]    0.0%                                       │
│   Eden  [░░░░░░░░░░░░░░░░░░░░]    0.0%                                       │
│   Old   [████████░░░░░░░░░░░░]   41.8%                                       │
│   Meta  [███████████████████░]   96.5%                                       │
│   CCS   [█████████████████░░░]   87.1%                                       │
│                                                                              │
│ YGC: 18 (0.163s)    FGC: 2 (0.215s)    Total: 10.000s                        │
╰──────────────────────────────────────────────────────────────────────────────╯
```

### Global Options

```
--source=auto|agent|jdk   Data source (default: auto)
--no-color                Disable colors
--lang=en|ko|ja|zh        Output language
--format=table|json       Output format (default: table)
--help, -h                Show help
--version, -v             Show version
```

> See [CLI Command Reference](docs/cli-commands.md) for all 17 commands with full output examples.

---

<br>

## Argus Agent (Dashboard)

Attach to your JVM for real-time monitoring with a web dashboard, flame graphs, and metric export.

### Features

- **Real-time Dashboard** — WebSocket streaming with Chart.js, dual tabs (Virtual Threads + JVM Overview)
- **Flame Graph** — Continuous profiling with d3-flamegraph (zoom, hover, export)
- **Virtual Thread Monitoring** — Lifecycle tracking, pinning detection, carrier thread analysis
- **Memory & GC** — Heap usage, GC pause analysis, allocation rate, metaspace monitoring
- **CPU & Profiling** — CPU tracking with 60s history, hot method detection, lock contention
- **Metric Export** — Prometheus `/prometheus` endpoint, OTLP push export, CSV/JSON/JSONL data export
- **Correlation Analysis** — Cross-metric correlation with automatic recommendations

### Quick Start

```bash
# 1. Attach agent to your app
java -javaagent:$(argus-agent --path) -jar your-app.jar

# 2. Open dashboard
open http://localhost:9202/

# 3. Enable profiling + flame graph
java -javaagent:~/.argus/argus-agent.jar \
     -Dargus.profiling.enabled=true \
     -Dargus.contention.enabled=true \
     -jar your-app.jar

# 4. Export to OpenTelemetry
java -javaagent:~/.argus/argus-agent.jar \
     -Dargus.otlp.enabled=true \
     -Dargus.otlp.endpoint=http://localhost:4318/v1/metrics \
     -jar your-app.jar
```

### API Endpoints

| Endpoint | Description |
|----------|-------------|
| `/` | Dashboard UI |
| `/health` | Health check |
| `/metrics` | Virtual thread metrics |
| `/gc-analysis` | GC statistics and recent events |
| `/cpu-metrics` | CPU utilization history |
| `/pinning-analysis` | Pinning hotspot analysis |
| `/allocation-analysis` | Allocation rate and top classes |
| `/metaspace-metrics` | Metaspace usage and growth |
| `/method-profiling` | Hot methods (Top 20) |
| `/contention-analysis` | Lock contention hotspots |
| `/correlation` | Correlation analysis and recommendations |
| `/flame-graph` | Flame graph data (JSON or `?format=collapsed`) |
| `/prometheus` | Prometheus metrics endpoint |
| `/carrier-threads` | Carrier thread distribution |
| `/active-threads` | Currently active virtual threads |
| `/export` | Export events (CSV, JSON, JSONL) |

### Agent Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `argus.server.port` | `9202` | Dashboard server port |
| `argus.gc.enabled` | `true` | GC monitoring |
| `argus.cpu.enabled` | `true` | CPU monitoring |
| `argus.profiling.enabled` | `false` | Method profiling (high overhead) |
| `argus.contention.enabled` | `false` | Lock contention tracking |
| `argus.allocation.enabled` | `false` | Allocation tracking (high overhead) |
| `argus.otlp.enabled` | `false` | OTLP push export |
| `argus.otlp.endpoint` | `http://localhost:4318/v1/metrics` | OTLP collector URL |

> See [Configuration Guide](docs/configuration.md) for all options.

---

<br>

## Installation

### One-line Install (Recommended)

```bash
curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash
```

Installs both CLI + Agent to `~/.argus/` and adds `argus` to PATH.

### Build from Source

```bash
git clone https://github.com/rlaope/argus.git
cd argus
./gradlew build
./gradlew :argus-cli:fatJar
```

### Requirements

- Java 21+
- Gradle 8.4+ (only if building from source)

### Uninstall

```bash
rm -rf ~/.argus
# Remove the PATH line from ~/.zshrc or ~/.bashrc
```

---

<br>

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
```

| Module | Description |
|--------|-------------|
| **argus-core** | Shared config, event models, ring buffer |
| **argus-agent** | Java agent with JFR streaming engine |
| **argus-server** | Netty HTTP/WS server, 10 analyzers, Prometheus + OTLP |
| **argus-frontend** | Static dashboard with Chart.js and d3-flamegraph |
| **argus-cli** | 17 diagnostic commands, auto source detection, i18n, 64 unit tests |

## Contributing

Contributions welcome — bug reports, features, docs, testing.
See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## Maintainer

[@rlaope](https://github.com/rlaope)

## License

MIT License - see [LICENSE](LICENSE) for details.
