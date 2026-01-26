# Argus

> *"Argus Panoptes, the all-seeing giant with a hundred eyes, never slept - for when some of his eyes closed, others remained open, watching everything."*

Inspired by **Argus Panoptes** from Greek mythology - the giant with a hundred eyes who never slept and watched over everything - this project observes and analyzes all Virtual Threads in the JVM in real-time.

A next-generation real-time visualization profiler for JVM 21+ environments, focusing on Virtual Threads (Project Loom) monitoring and memory analysis.

## Features

- **Virtual Thread Monitoring**: Track creation, termination, and pinning of virtual threads
- **Memory & GC Monitoring**: Real-time garbage collection tracking with heap usage visualization
- **CPU Monitoring**: JVM and system CPU utilization tracking
- **JFR Streaming**: Low-overhead event collection using JDK Flight Recorder
- **Real-time Dashboard**: WebSocket-based streaming with interactive charts
- **Lock-free Architecture**: High-performance ring buffer for event collection

## Requirements

- Java 21+
- Gradle 8.4+ (only if building from source)

## Installation

### Option 1: Download via curl (Recommended)

```bash
# Download the latest agent JAR
curl -LO https://github.com/rlaope/argus/releases/latest/download/argus-agent.jar

# Or download a specific version
curl -LO https://github.com/rlaope/argus/releases/download/v0.1.0/argus-agent.jar
```

### Option 2: Build from Source

```bash
git clone https://github.com/rlaope/argus.git
cd argus
./gradlew build

# JARs are located at:
# argus-agent/build/libs/argus-agent-x.x.x-SNAPSHOT.jar
# argus-server/build/libs/argus-server-x.x.x-SNAPSHOT.jar
```

## Quick Start

### Run with Java Agent

```bash
java -javaagent:argus-agent.jar \
     --enable-preview \
     -jar your-application.jar
```

### With Built-in Dashboard Server

```bash
java -javaagent:argus-agent.jar \
     -Dargus.server.enabled=true \
     --enable-preview \
     -jar your-application.jar

# Open dashboard: http://localhost:9202/
# View metrics: curl http://localhost:9202/metrics
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

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   argus-agent   │───▶│   argus-core    │◀───│  argus-server   │
│  (JFR Stream)   │    │  (Ring Buffer)  │    │   (WebSocket)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                                              │
         │              JFR Events                      │
         ▼                                              ▼
┌─────────────────┐                          ┌─────────────────┐
│   Target JVM    │                          │    Frontend     │
│ (Virtual Threads)│                          │  (Visualization)│
└─────────────────┘                          └─────────────────┘
```

## Modules

- **argus-core**: Core event models, ring buffer, and serialization
- **argus-agent**: Java agent with JFR streaming engine
- **argus-server**: WebSocket server for event streaming

## JFR Events Captured

### Virtual Thread Events
- `jdk.VirtualThreadStart` - Thread creation
- `jdk.VirtualThreadEnd` - Thread termination
- `jdk.VirtualThreadPinned` - Pinning detection (critical for Loom performance)
- `jdk.VirtualThreadSubmitFailed` - Submit failures

### GC Events
- `jdk.GarbageCollection` - GC pause duration, cause, and type
- `jdk.GCHeapSummary` - Heap usage before and after GC

### CPU Events
- `jdk.CPULoad` - JVM and system CPU utilization

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
