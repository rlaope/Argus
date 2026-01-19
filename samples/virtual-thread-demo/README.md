# Virtual Thread Demo

A sample application demonstrating Project Argus monitoring capabilities.

## Features Demonstrated

1. **Basic Virtual Threads** - Simple thread creation and lifecycle
2. **Concurrent HTTP Requests** - I/O-bound workloads with virtual threads
3. **Thread Pinning** - Detecting performance-critical pinning events
4. **High-throughput** - Creating 1000+ virtual threads

## Quick Start

### 1. Build Argus Agent

```bash
# From project root
cd ../..
./gradlew :argus-agent:build
```

### 2. Run Demo with Argus

```bash
# From project root
./gradlew :samples:virtual-thread-demo:runWithArgus
```

### 3. View Events

Watch the console output for Argus events, especially `PINNED` events.

## Expected Output

```
[Argus] JFR streaming started
============================================================
  Virtual Thread Demo - Argus Monitoring Example
============================================================

[Demo 1] Basic Virtual Threads
...
[Demo 3] Thread Pinning
[Argus] PINNED: thread=123, carrier=1, duration=200000000ns
...
[Argus] JFR streaming stopped. Total events processed: 2XXX
```

## Learn More

See the [full documentation](../../docs/getting-started.md) for detailed usage instructions.
