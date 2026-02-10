<p align="center">
  <img src="assets/argus_logo.png" alt="Argus Logo" width="240">
</p>

# Argus

> *"Argus Panoptes, the all-seeing giant with a hundred eyes, never slept - for when some of his eyes closed, others remained open, watching everything."*

Inspired by **Argus Panoptes** from Greek mythology - the giant with a hundred eyes who never slept and watched over everything - this project observes and analyzes all Virtual Threads in the JVM in real-time.

A lightweight, zero-dependency JVM monitoring tool for Java 21+ environments. Real-time dashboard, terminal CLI, flame graphs, and OpenTelemetry export — all powered by JDK Flight Recorder.

## Features

### Real-time Dashboard
- **Interactive Charts**: WebSocket-based streaming with Chart.js visualizations
- **Flame Graph**: Continuous profiling visualization with d3-flamegraph (zoom, hover, export)
- **Dual Tabs**: Virtual Threads tab + JVM Overview tab

### CLI Monitor (`argus top`)
- **htop-style Terminal UI**: CPU, heap, GC, virtual threads at a glance
- **ANSI Color Coding**: Green/yellow/red thresholds for instant status
- **Zero Dependencies**: Standalone JAR, connects to any running Argus server

### Virtual Thread Monitoring
- **Thread Lifecycle**: Track creation, termination, and pinning of virtual threads
- **Pinning Detection**: Identify pinned threads with detailed stack traces
- **Carrier Thread Analysis**: Per-carrier virtual thread distribution

### Memory & GC Monitoring
- **GC Events**: Real-time garbage collection tracking with pause time analysis
- **Heap Usage**: Before/after heap visualization with trend analysis
- **Allocation Rate**: Track object allocation rate and top allocating classes
- **Metaspace Monitoring**: Monitor metaspace usage and growth rate

### CPU & Profiling
- **CPU Utilization**: JVM and system CPU tracking with 60s history
- **Method Profiling**: Hot method detection via execution sampling
- **Flame Graph**: Interactive flame graph from continuous profiling data
- **Lock Contention**: Monitor thread contention and lock wait times

### Observability Export
- **Prometheus**: `/prometheus` endpoint for scraping
- **OTLP Export**: Push metrics to OpenTelemetry collectors (hand-coded, no SDK)
- **Data Export**: Export events in CSV, JSON, or JSONL formats

### Core Architecture
- **JFR Streaming**: Low-overhead event collection using JDK Flight Recorder
- **Lock-free Ring Buffer**: High-performance event collection
- **Zero External Dependencies**: Only Netty for HTTP server (no Jackson, no Gson, no OTEL SDK)
- **Correlation Analysis**: Cross-metric correlation with automatic recommendations

## Requirements

- Java 21+
- Gradle 8.4+ (only if building from source)

## Installation

### Option 1: One-line Install (Recommended)

```bash
curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash
```

This downloads the agent + CLI, installs to `~/.argus/`, and adds the `argus` command to your PATH.

```bash
# Install a specific version
curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash -s -- v0.3.0
```

After installation, restart your terminal or run `source ~/.zshrc` (or `~/.bashrc`).

### Option 2: Manual Download

```bash
# Download JARs from GitHub Releases
curl -LO https://github.com/rlaope/argus/releases/latest/download/argus-agent-0.3.0.jar
curl -LO https://github.com/rlaope/argus/releases/latest/download/argus-cli-0.3.0-all.jar

# Run the CLI directly
java -jar argus-cli-0.3.0-all.jar
```

### Option 3: Build from Source

```bash
git clone https://github.com/rlaope/argus.git
cd argus
./gradlew build
./gradlew :argus-cli:fatJar

# JARs:
# argus-agent/build/libs/argus-agent-0.3.0.jar
# argus-cli/build/libs/argus-cli-0.3.0-all.jar
```

## Quick Start

### 1. Attach Argus to Your App

```bash
java -javaagent:$(argus-agent --path) \
     -jar your-application.jar

# Or with the JAR path directly
java -javaagent:~/.argus/argus-agent.jar \
     -jar your-application.jar
```

### 2. Open the Dashboard

```
http://localhost:9202/
```

### 3. Use the CLI Monitor

```bash
# Connect to local Argus server
argus

# Custom host/port and refresh interval
argus --host 192.168.1.100 --port 9202 --interval 2

# Disable colors (for piping/logging)
argus --no-color
```

### 4. Enable Profiling & Flame Graph

```bash
java -javaagent:~/.argus/argus-agent.jar \
     -Dargus.profiling.enabled=true \
     -Dargus.contention.enabled=true \
     -jar your-application.jar
```

### 5. Export Metrics to OpenTelemetry

```bash
java -javaagent:~/.argus/argus-agent.jar \
     -Dargus.otlp.enabled=true \
     -Dargus.otlp.endpoint=http://localhost:4318/v1/metrics \
     -jar your-application.jar
```

### Configuration

The agent accepts the following system properties:

| Property | Default | Description |
|----------|---------|-------------|
| `argus.server.enabled` | `false` | Enable built-in dashboard server |
| `argus.server.port` | `9202` | Dashboard/WebSocket server port |
| `argus.buffer.size` | `65536` | Ring buffer size for event collection |
| `argus.gc.enabled` | `true` | Enable GC monitoring |
| `argus.cpu.enabled` | `true` | Enable CPU monitoring |
| `argus.cpu.interval` | `1000` | CPU sampling interval in milliseconds |
| `argus.allocation.enabled` | `false` | Enable allocation tracking (high overhead) |
| `argus.allocation.threshold` | `1048576` | Minimum allocation size to track (1MB) |
| `argus.metaspace.enabled` | `true` | Enable metaspace monitoring |
| `argus.profiling.enabled` | `false` | Enable method profiling (high overhead) |
| `argus.profiling.interval` | `20` | Profiling sampling interval (ms) |
| `argus.contention.enabled` | `false` | Enable lock contention tracking |
| `argus.contention.threshold` | `50` | Minimum contention duration (ms) |
| `argus.correlation.enabled` | `true` | Enable correlation analysis |
| `argus.otlp.enabled` | `false` | Enable OTLP metrics export |
| `argus.otlp.endpoint` | `http://localhost:4318/v1/metrics` | OTLP collector endpoint |
| `argus.otlp.interval` | `15000` | OTLP push interval in milliseconds |
| `argus.otlp.headers` | *(empty)* | Auth headers (`key=val,key=val`) |
| `argus.otlp.service.name` | `argus` | OTLP resource service name |

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
│   Target JVM    │     │ (argus top) │     │  (Dashboard UI) │
└─────────────────┘     └─────────────┘     └─────────────────┘
                              │                       │
                         HTTP Polling            WebSocket +
                         (10 endpoints)          Flame Graph
```

## Modules

- **argus-core**: Shared config, event models, ring buffer
- **argus-agent**: Java agent entry point with JFR streaming engine
- **argus-server**: Netty HTTP/WebSocket server, 10 analyzers, Prometheus + OTLP export
- **argus-frontend**: Static HTML/JS dashboard with Chart.js and d3-flamegraph
- **argus-cli**: Standalone terminal monitor (`argus top`), zero external dependencies

## JFR Events Captured

### Virtual Thread Events
- `jdk.VirtualThreadStart` - Thread creation
- `jdk.VirtualThreadEnd` - Thread termination
- `jdk.VirtualThreadPinned` - Pinning detection (critical for Loom performance)
- `jdk.VirtualThreadSubmitFailed` - Submit failures

### GC & Memory Events
- `jdk.GarbageCollection` - GC pause duration, cause, and type
- `jdk.GCHeapSummary` - Heap usage before and after GC
- `jdk.ObjectAllocationInNewTLAB` - Object allocation tracking
- `jdk.MetaspaceSummary` - Metaspace usage monitoring

### CPU & Performance Events
- `jdk.CPULoad` - JVM and system CPU utilization
- `jdk.ExecutionSample` - Method execution sampling for CPU profiling
- `jdk.JavaMonitorEnter` - Lock acquisition contention
- `jdk.JavaMonitorWait` - Lock wait contention

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| `/` | Dashboard UI |
| `/health` | Health check |
| `/metrics` | Virtual thread metrics |
| `/gc-analysis` | GC statistics and recent events |
| `/cpu-metrics` | CPU utilization history |
| `/pinning-analysis` | Pinning hotspot analysis |
| `/export` | Export events (CSV, JSON, JSONL) |
| `/allocation-analysis` | Allocation rate and top allocating classes |
| `/metaspace-metrics` | Metaspace usage and growth |
| `/method-profiling` | Hot methods (Top 20) |
| `/contention-analysis` | Lock contention hotspots |
| `/correlation` | Correlation analysis and recommendations |
| `/flame-graph` | Flame graph data (JSON or `?format=collapsed`) |
| `/prometheus` | Prometheus metrics endpoint |
| `/carrier-threads` | Carrier thread distribution |
| `/active-threads` | Currently active virtual threads |

## Uninstall

```bash
rm -rf ~/.argus
# Then remove the PATH line from ~/.zshrc or ~/.bashrc
```

## Contributing

**Everyone is welcome!** Project Argus is an open-source project and we welcome all forms of contributions.

- Bug reports & feature requests
- Code contributions (bug fixes, new features)
- Documentation improvements
- Testing and feedback

See [CONTRIBUTING.md](CONTRIBUTING.md) for more details.

## Maintainer

- **[@rlaope](https://github.com/rlaope)** - Project Lead & Maintainer

For questions, suggestions, or collaboration inquiries, please open a GitHub Issue or contact [@rlaope](https://github.com/rlaope) directly.

## License

MIT License - see [LICENSE](LICENSE) for details.
