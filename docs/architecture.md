# Architecture

This document describes the internal architecture of Project Argus.

## Overview

Project Argus consists of three main modules that work together to capture, buffer, and stream virtual thread events.

```
┌─────────────────────────────────────────────────────────────────┐
│                        Target JVM                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Your Application                      │   │
│  │                  (Virtual Threads)                       │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            │                                     │
│                     JFR Events                                   │
│                            ▼                                     │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    argus-agent                           │   │
│  │  ┌─────────────────┐    ┌─────────────────────────┐    │   │
│  │  │ JfrStreaming    │───▶│      RingBuffer         │    │   │
│  │  │    Engine       │    │    (argus-core)         │    │   │
│  │  └─────────────────┘    └─────────────────────────┘    │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                            │
                     WebSocket Stream
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      argus-server                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  WebSocket Handler                       │   │
│  │              (Netty-based server)                        │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                            │
                     JSON Events
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Frontend                                   │
│              (Visualization Dashboard)                          │
└─────────────────────────────────────────────────────────────────┘
```

## Module Details

### argus-core

The core module contains shared components used by other modules.

#### Components

**EventType.java**
```
Enum defining virtual thread event types:
├── VIRTUAL_THREAD_START (1)
├── VIRTUAL_THREAD_END (2)
├── VIRTUAL_THREAD_PINNED (3)
└── VIRTUAL_THREAD_SUBMIT_FAILED (4)
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
│   ├── jdk.VirtualThreadStart
│   ├── jdk.VirtualThreadEnd
│   ├── jdk.VirtualThreadPinned (with stack trace)
│   └── jdk.VirtualThreadSubmitFailed
├── Converts events to VirtualThreadEvent
└── Offers events to RingBuffer
```

### argus-server

The server module provides a WebSocket interface for event streaming.

#### Components

```
ArgusServer.java
├── Netty-based HTTP/WebSocket server
├── Endpoints:
│   ├── ws://host:port/events - WebSocket stream
│   └── GET /health - Health check
├── Event broadcaster (10ms interval)
└── JSON serialization
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

## Future Architecture (Planned)

### Phase 2: Storage Layer

```
RingBuffer ──▶ DuckDB ──▶ Time-series queries
                 │
                 └──▶ 30-minute sliding window
```

### Phase 3: Visualization

```
WebSocket ──▶ Svelte 5 + PixiJS ──▶ Real-time thread map
```

### Phase 4: Analysis

```
Events ──▶ AI Analysis ──▶ Memory leak detection
                     └──▶ Performance recommendations
```

## Next Steps

- [Getting Started](getting-started.md) - Installation guide
- [Configuration](configuration.md) - Tuning options
- [Troubleshooting](troubleshooting.md) - Common issues
