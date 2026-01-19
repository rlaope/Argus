# Project Argus

> *"Argus Panoptes, the all-seeing giant with a hundred eyes, never slept - for when some of his eyes closed, others remained open, watching everything."*

Inspired by **Argus Panoptes** from Greek mythology - the giant with a hundred eyes who never slept and watched over everything - this project observes and analyzes all Virtual Threads in the JVM in real-time.

A next-generation real-time visualization profiler for JVM 21+ environments, focusing on Virtual Threads (Project Loom) monitoring and memory analysis.

## Features

- **Virtual Thread Monitoring**: Track creation, termination, and pinning of virtual threads
- **JFR Streaming**: Low-overhead event collection using JDK Flight Recorder
- **Real-time Visualization**: WebSocket-based streaming to frontend
- **Lock-free Architecture**: High-performance ring buffer for event collection

## Requirements

- Java 21+
- Gradle 8.4+

## Quick Start

### Build

```bash
./gradlew build
```

### Run with Java Agent

```bash
java -javaagent:argus-agent/build/libs/argus-agent.jar \
     --enable-preview \
     -jar your-application.jar
```

### Configuration

The agent accepts the following system properties:

- `argus.server.port` - WebSocket server port (default: 8080)
- `argus.buffer.size` - Ring buffer size (default: 65536)

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

- `jdk.VirtualThreadStart` - Thread creation
- `jdk.VirtualThreadEnd` - Thread termination
- `jdk.VirtualThreadPinned` - Pinning detection (critical for Loom performance)
- `jdk.VirtualThreadSubmitFailed` - Submit failures

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
