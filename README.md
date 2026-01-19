# Project Argus

> *"Argus Panoptes, the all-seeing giant with a hundred eyes, never slept - for when some of his eyes closed, others remained open, watching everything."*

**Project Argus**는 그리스 신화의 백 개의 눈을 가진 거인 **아르고스(Argus Panoptes)**에서 영감을 받아 시작되었습니다. 아르고스가 결코 잠들지 않고 모든 것을 감시했듯이, 이 프로젝트는 JVM의 모든 Virtual Thread를 실시간으로 관찰하고 분석합니다.

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

**누구나 환영합니다!** Project Argus는 오픈소스 프로젝트이며, 모든 형태의 기여를 환영합니다.

- Bug reports & feature requests
- Code contributions (bug fixes, new features)
- Documentation improvements
- Testing and feedback

자세한 내용은 [CONTRIBUTING.md](CONTRIBUTING.md)를 참고해주세요.

## Maintainer

- **[@rlaope](https://github.com/rlaope)** - Project Lead & Maintainer

질문, 제안, 또는 협업 문의는 GitHub Issues를 통해 연락해주시거나 [@rlaope](https://github.com/rlaope)에게 직접 연락해주세요.

## License

MIT License - see [LICENSE](LICENSE) for details.
