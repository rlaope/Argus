# Architecture

This document describes the internal architecture of Project Argus.

## Overview

Project Argus consists of five modules that work together to capture, analyze, and visualize JVM metrics.

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

### Module Dependency Direction (NEVER violate)

```
argus-agent → argus-server → argus-core
                           → argus-frontend
argus-cli → argus-core (standalone, no server dependency)
```

## Module Details

### argus-core

The core module contains shared components used by other modules.

#### Components

**EventType.java**
```
Enum defining event types:
├── VIRTUAL_THREAD_START (1)
├── VIRTUAL_THREAD_END (2)
├── VIRTUAL_THREAD_PINNED (3)
├── VIRTUAL_THREAD_SUBMIT_FAILED (4)
├── GC_PAUSE (10)
├── GC_HEAP_SUMMARY (11)
├── CPU_LOAD (20)
├── ALLOCATION (30)
├── METASPACE_SUMMARY (31)
├── EXECUTION_SAMPLE (40)
└── CONTENTION (41)
```

**VirtualThreadEvent.java**
```
Record containing event data:
├── eventType: EventType
├── threadId: long
├── threadName: String (nullable)
├── carrierThread: long (for pinned events)
├── timestamp: Instant
├── duration: long (nanoseconds)
└── stackTrace: String (for pinned events)
```

**RingBuffer.java**
```
Lock-free ring buffer implementation:
├── Capacity: Power of 2 (default 65536)
├── Operations: offer(), poll(), drain()
├── Thread-safe via AtomicLong sequences
└── Lossy mode: overwrites old events when full
```

### argus-agent

The agent module attaches to the target JVM and captures JFR events.

#### Entry Points

```
ArgusAgent.java
├── premain() - Static attachment (javaagent flag)
└── agentmain() - Dynamic attachment (Attach API)
```

#### JFR Streaming Engine

```
JfrStreamingEngine.java
├── Uses RecordingStream (JDK 14+)
├── Subscribes to JFR events:
│   ├── Virtual Thread Events:
│   │   ├── jdk.VirtualThreadStart
│   │   ├── jdk.VirtualThreadEnd
│   │   ├── jdk.VirtualThreadPinned (with stack trace)
│   │   └── jdk.VirtualThreadSubmitFailed
│   ├── GC & Memory Events:
│   │   ├── jdk.GarbageCollection
│   │   ├── jdk.GCHeapSummary
│   │   ├── jdk.ObjectAllocationInNewTLAB
│   │   └── jdk.MetaspaceSummary
│   └── CPU & Performance Events:
│       ├── jdk.CPULoad
│       ├── jdk.ExecutionSample
│       ├── jdk.JavaMonitorEnter
│       └── jdk.JavaMonitorWait
├── Event Extractors:
│   ├── VirtualThreadEventExtractor
│   ├── GCEventExtractor
│   ├── CPUEventExtractor
│   ├── AllocationEventExtractor
│   ├── MetaspaceEventExtractor
│   ├── ExecutionSampleExtractor
│   └── ContentionEventExtractor
├── Converts events to typed event records
└── Offers events to RingBuffer
```

### argus-server

The server module provides a WebSocket interface for event streaming and analysis.

#### Components

```
ArgusServer.java
├── Netty-based HTTP/WebSocket server
├── Endpoints:
│   ├── ws://host:port/events - WebSocket stream
│   ├── GET /health - Health check
│   ├── GET /metrics - Thread metrics
│   ├── GET /gc-analysis - GC statistics
│   ├── GET /cpu-metrics - CPU utilization
│   ├── GET /pinning-analysis - Pinning hotspots
│   ├── GET /carrier-threads - Carrier thread distribution
│   ├── GET /active-threads - Active virtual threads
│   ├── GET /allocation-analysis - Allocation metrics
│   ├── GET /metaspace-metrics - Metaspace usage
│   ├── GET /method-profiling - Hot methods
│   ├── GET /contention-analysis - Lock contention
│   ├── GET /correlation - Correlation & recommendations
│   ├── GET /flame-graph - Flame graph (JSON + collapsed)
│   ├── GET /prometheus - Prometheus metrics
│   ├── GET /export - Event export (CSV/JSON/JSONL)
│   └── GET /config - Server configuration status
├── Event broadcaster (10ms interval)
└── JSON serialization

Analyzers (10):
├── PinningAnalyzer - Hotspot detection, stack trace dedup
├── CarrierThreadAnalyzer - Per-carrier VT stats
├── GCAnalyzer - GC pause, heap, overhead analysis
├── CPUAnalyzer - JVM/system CPU, 60s history
├── AllocationAnalyzer - Allocation rate, top classes
├── MetaspaceAnalyzer - Metaspace usage tracking
├── MethodProfilingAnalyzer - Hot method detection
├── ContentionAnalyzer - Lock contention hotspots
├── CorrelationAnalyzer - Cross-metric correlation
└── FlameGraphAnalyzer - Stack trace tree, d3-flamegraph JSON

Metrics Export:
├── PrometheusMetricsCollector - /prometheus text format
├── OtlpMetricsExporter - Push to OTLP collector (background thread)
└── OtlpJsonBuilder - OTLP JSON format builder
```

### argus-frontend

The frontend module contains the static HTML/JS dashboard.

```
Frontend:
├── index.html - Dashboard with Chart.js + d3-flamegraph
├── css/style.css - Styling
└── js/app.js - WebSocket client, chart rendering, flame graph
```

### argus-cli

The CLI module provides a standalone terminal monitor.

```
ArgusTop.java - Main entry, argument parsing, refresh loop
ArgusClient.java - HTTP polling (10 endpoints in parallel)
TerminalRenderer.java - ANSI escape code rendering
MetricsSnapshot.java - Immutable data record for one poll cycle
```

## Data Flow

### 1. Event Capture

```
JVM Virtual Thread Activity
         │
         ▼
┌─────────────────────────┐
│   JDK Flight Recorder   │
│   (Built into JVM)      │
└─────────────────────────┘
         │
         │  JFR RecordingStream
         ▼
┌─────────────────────────┐
│  JfrStreamingEngine     │
│  - Parse JFR event      │
│  - Extract thread info  │
│  - Create event object  │
└─────────────────────────┘
```

### 2. Event Buffering

```
VirtualThreadEvent
         │
         ▼
┌─────────────────────────┐
│      RingBuffer         │
│  ┌───┬───┬───┬───┬───┐ │
│  │ E │ E │ E │   │   │ │  <- Lock-free writes
│  └───┴───┴───┴───┴───┘ │
│    ↑               ↑    │
│  write           read   │
└─────────────────────────┘
```

### 3. Event Streaming

```
┌─────────────────────────┐
│    Event Broadcaster    │
│    (10ms interval)      │
└─────────────────────────┘
         │
         │  drain()
         ▼
┌─────────────────────────┐
│   JSON Serialization    │
│   {                     │
│     "type": "...",      │
│     "threadId": 123,    │
│     "timestamp": "..."  │
│   }                     │
└─────────────────────────┘
         │
         │  WebSocket
         ▼
┌─────────────────────────┐
│   Connected Clients     │
│   ├── Client 1          │
│   ├── Client 2          │
│   └── Client N          │
└─────────────────────────┘
```

## Ring Buffer Design

The ring buffer uses a lock-free design for high performance.

### Structure

```
Capacity: 2^n (e.g., 65536)
Mask: capacity - 1 (for fast modulo via bitwise AND)

Sequences (AtomicLong):
├── writeSequence: Next write position
└── readSequence: Next read position

Index calculation:
  index = sequence & mask
```

### Operations

**offer() - Producer**
```
1. Get current write sequence
2. Calculate index: sequence & mask
3. Store element at index
4. Increment write sequence (lazySet)
```

**poll() - Consumer**
```
1. Get current read and write sequences
2. If read >= write, return null (empty)
3. Calculate index: read & mask
4. CAS increment read sequence
5. Return element
```

### Lossy Behavior

When the buffer is full, new events overwrite old ones:

```
Before: [E1][E2][E3][E4]  write=4, capacity=4
                    ↑
                  oldest

After offer(E5):
        [E5][E2][E3][E4]  write=5
         ↑
       newest (E1 lost)
```

## JFR Integration

### Why JFR?

- **Built into JVM**: No external dependencies
- **Low overhead**: Designed for production use
- **Streaming API**: Real-time event access (JDK 14+)
- **Virtual Thread events**: Native support in JDK 21+

### Event Details

**jdk.VirtualThreadStart**
```
Fields:
├── javaThreadId: long
└── eventThread.javaName: String
```

**jdk.VirtualThreadEnd**
```
Fields:
├── javaThreadId: long
└── eventThread.javaName: String
```

**jdk.VirtualThreadPinned** (Critical for performance)
```
Fields:
├── javaThreadId: long
├── eventThread.javaName: String
├── carrierThread.javaThreadId: long
├── duration: Duration
└── stackTrace: StackTrace (when enabled)
```

**jdk.VirtualThreadSubmitFailed**
```
Fields:
├── javaThreadId: long
└── eventThread.javaName: String
```

## Thread Safety

### Agent (Single Producer)

```
JfrStreamingEngine Thread
         │
         │ offer() - single writer
         ▼
    ┌──────────┐
    │RingBuffer│
    └──────────┘
```

### Server (Multiple Consumers)

```
    ┌──────────┐
    │RingBuffer│
    └──────────┘
         │
         │ drain() - single reader (scheduler thread)
         ▼
Event Broadcaster Thread
         │
         │ broadcast to all
         ▼
┌────┬────┬────┐
│ C1 │ C2 │ C3 │  WebSocket clients
└────┴────┴────┘
```

## Design Decisions

- **Zero external dependencies** (except Netty for HTTP): No Jackson, no Gson, no OpenTelemetry SDK
- **JSON**: Hand-built with StringBuilder
- **Prometheus**: Manual text format (no Micrometer)
- **OTLP**: Hand-coded JSON Protobuf encoding
- **Flame graph**: 60-second auto-reset window for fresh data
- **CLI**: Separate module polling via HTTP (not embedded in agent)

## Next Steps

- [Getting Started](getting-started.md) - Installation guide
- [Configuration](configuration.md) - Tuning options
- [Troubleshooting](troubleshooting.md) - Common issues
