# Architecture

This document describes the internal architecture of Project Argus.

## Overview

Project Argus consists of eleven primary modules that work together to capture, analyze, embed, and visualize JVM diagnostics.

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

```
┌──────────────────────────┐    ┌──────────────────────────────────┐
│    argus-micrometer      │    │   argus-spring-boot-starter      │
│  (MeterBinder, ~25       │    │  (Spring Boot 3.2+ auto-config)  │
│   metrics bridge)        │    │                                  │
└──────────────────────────┘    └──────────────────────────────────┘
```

Current module inventory:

| Module | Purpose |
|---|---|
| `argus-core` | Shared event, config, buffer, command, and model primitives |
| `argus-agent` | Java agent entry point and JFR streaming runtime |
| `argus-server` | Netty HTTP/WebSocket API, analysis endpoints, and Prometheus export |
| `argus-frontend` | Embedded dashboard UI assets |
| `argus-cli` | Standalone Java 11+ diagnostic CLI |
| `argus-diagnostics` | Framework-agnostic doctor, GC log, and GC score services |
| `argus-micrometer` | Micrometer bridge for Argus server metrics |
| `argus-spring-boot-starter` | Spring Boot auto-configuration, actuator endpoints, and scheduled doctor |
| `argus-aggregator` | Fleet scrape, alert, profile, and Prometheus aggregation service |
| `argus-operator` | Kubernetes controller and discovery integration |
| `argus-instrument` | Opt-in dynamic attach instrumentation agent |

Sample projects under `samples:*` exercise these modules but are intentionally outside the primary module count.

### Module Dependency Direction (NEVER violate)

```
argus-agent → argus-server
argus-server → argus-core, argus-cli, argus-frontend
argus-cli → argus-core, argus-diagnostics (standalone, no server dependency)
argus-diagnostics → argus-core
argus-micrometer → argus-server → argus-core
argus-spring-boot-starter → argus-core, argus-agent, argus-server, argus-micrometer, argus-diagnostics
argus-aggregator → argus-core
argus-operator → Kubernetes client APIs (no dependency on runtime modules)
argus-instrument → ByteBuddy only; loaded on demand, no compile dependency from the CLI/core path
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

The CLI module provides a unified JVM diagnostic tool with multiple data sources.

```
ArgusCli.java - Main entry, subcommand routing, global option parsing

command/
├── Command.java - Command interface
├── InitCommand.java - First-time setup wizard (language selection)
├── PsCommand.java - List JVM processes
├── HistoCommand.java - Heap object histogram
├── ThreadsCommand.java - Thread dump summary
├── GcCommand.java - GC statistics
├── GcUtilCommand.java - GC generation utilization (jstat-style)
├── HeapCommand.java - Heap memory usage
├── SysPropsCommand.java - System properties viewer (--filter)
├── VmFlagCommand.java - VM flag viewer/setter (--set)
├── NmtCommand.java - Native memory tracking
├── ClassLoaderCommand.java - Class loader hierarchy
├── JfrCommand.java - Flight Recorder control (start/stop/check/dump)
├── InfoCommand.java - JVM information
└── TopCommand.java - Real-time monitoring (wraps ArgusClient)

provider/
├── DiagnosticProvider.java - Base interface (isAvailable, priority, source)
├── ProviderRegistry.java - Auto-detection and source selection
├── jdk/ - JDK tool providers (jcmd, priority=10)
│   ├── JcmdExecutor.java - Shared jcmd process execution
│   ├── JdkHistoProvider.java - GC.class_histogram
│   ├── JdkThreadProvider.java - Thread.print
│   ├── JdkGcProvider.java - GC.heap_info + VM.info
│   ├── JdkHeapProvider.java - GC.heap_info
│   ├── JdkInfoProvider.java - VM.version + VM.flags + VM.uptime
│   ├── JdkProcessProvider.java - jcmd -l
│   ├── JdkGcUtilProvider.java - jstat -gcutil
│   ├── JdkSysPropsProvider.java - VM.system_properties
│   ├── JdkVmFlagProvider.java - VM.flags + VM.set_flag
│   ├── JdkNmtProvider.java - VM.native_memory summary
│   ├── JdkClassLoaderProvider.java - VM.classloaders
│   ├── JdkJfrProvider.java - JFR.start/stop/check/dump
│   └── JdkParseUtils.java - Shared parseLong/parseDouble
└── agent/ - Argus agent providers (HTTP, priority=100)
    ├── AgentClient.java - HTTP client for agent endpoints
    ├── AgentThreadProvider.java - /thread-dump
    ├── AgentGcProvider.java - /gc-analysis
    └── AgentHeapProvider.java - /gc-analysis (heap subset)

config/
├── CliConfig.java - ~/.argus/config.properties (lang, source, format)
└── Messages.java - i18n message loading (en, ko, ja, zh)

render/
├── AnsiStyle.java - ANSI escape code constants
└── RichRenderer.java - Box-drawing, tables, progress bars

Used by TopCommand:
├── ArgusClient.java - HTTP polling (10 endpoints in parallel)
├── TerminalRenderer.java - ANSI escape code rendering
└── MetricsSnapshot.java - Immutable data record for one poll cycle
```

### argus-micrometer

Provides a `MeterBinder` implementation that bridges Argus JVM metrics into any Micrometer-compatible registry.

```
ArgusMetrics.java (MeterBinder)
├── Registers ~25 gauges and counters:
│   ├── GC: pause count, pause time, heap used/committed
│   ├── Threads: virtual count, platform count, blocked count
│   ├── Allocation: allocation rate, top classes
│   ├── Metaspace: used, committed, reserved
│   ├── CPU: JVM load, system load
│   └── Contention: lock wait count, wait time
└── Reads live data from ArgusServer analyzers
```

### argus-spring-boot-starter

Provides Spring Boot 3.2+ auto-configuration for the Argus agent and Micrometer bridge.

```
ArgusAutoConfiguration.java
├── Condition: @ConditionalOnClass(ArgusAgent.class)
├── Auto-starts ArgusAgent on application context refresh
├── Binds argus.* properties from application.properties/yml
├── Registers ArgusMetrics MeterBinder when Micrometer is present
└── Exposes ArgusServer as a Spring-managed bean
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

- **Zero external dependencies in core** (except Netty for HTTP): No Jackson, no Gson, no OpenTelemetry SDK
- **JSON**: Hand-built with StringBuilder
- **Prometheus**: Manual text format in argus-server; Micrometer bridge available via argus-micrometer
- **OTLP**: Hand-coded JSON Protobuf encoding
- **Flame graph**: 60-second auto-reset window for fresh data
- **CLI**: Separate module polling via HTTP (not embedded in agent)

## v1.0.0 Architecture Additions

### DiagnosticCommand SPI (argus-core)

Pluggable command system shared between CLI and server:

```
argus-core/command/
├── DiagnosticCommand.java    — interface: id(), group(), execute(ctx)
├── CommandContext.java       — sealed: InProcess | External
├── CommandGroup.java         — enum: PROCESS, MEMORY, THREADS, RUNTIME, PROFILING, MONITORING
└── CommandRegistry.java      — ServiceLoader auto-discovery
```

Server commands register via `META-INF/services/io.argus.core.command.DiagnosticCommand`. Adding a new server command = 1 class + 1 line.

### Doctor Engine (argus-diagnostics, CLI adapters in argus-cli)

Health diagnosis with pluggable rules:

```
argus-diagnostics/doctor/
├── DoctorEngine.java         — runs all rules, sorts by severity
├── JvmSnapshot.java          — immutable snapshot of all JVM metrics
├── JvmSnapshotCollector.java — local (MXBean) or remote (jcmd) collection
├── HealthRule.java           — functional interface
├── Finding.java              — severity + title + recommendations + flags
├── Severity.java             — CRITICAL, WARNING, INFO
└── rules/
    ├── GC, heap, CPU, thread, direct-buffer, code-cache, and metaspace rules
    ├── ZGC soft-max and cycle-overlap rules
    └── G1 full-GC, region-size, IHOP, evacuation, mixed, and humongous rules
```

`argus-cli` owns command/rendering adapters such as `DoctorCommand` and profile-snapshot doctor rules; the reusable engine lives in `argus-diagnostics`.

### GC Log Analyzer (argus-diagnostics, CLI rendering in argus-cli)

```
argus-diagnostics/gclog/
├── GcLogParser.java    — streaming BufferedReader, G1/ZGC/Shenandoah/Legacy
├── GcEvent.java        — double pauseMs (sub-ms precision)
└── GcLogAnalyzer.java  — percentiles, throughput, 7 tuning rules
```

CLI-specific timeline rendering remains under `argus-cli/gclog/`.

### TUI (argus-cli)

JLine3-based full-screen interactive UI:

```
argus-cli/tui/
└── TuiApp.java — 3-phase flow:
    Phase 1: PS (process select + logo)
    Phase 2: CMD (command list, grouped by CommandGroup)
    Phase 3: OUT (command output with scroll)

Features: alt screen buffer, max 120 width centered,
          theme cycling (skyblue/green/gray),
          language select overlay, stderr capture
```

### HTTP Route Table (argus-server)

```
argus-server/handler/
└── RouteTable.java — declarative route registration
    .exact("/health", handler)
    .prefix("/api/", handler)
    .build()
```

Replaced 150-line if/else chain in ArgusChannelHandler.

## v1.1.0 Architecture Additions

### async-profiler integration depth (`argus profile`, `argus flame`)
- ProfileProvider extended with start/stop/dump/status session methods plus an AsProfOptions value object for advanced flag passthrough (--cstack, --interval, --jstackdepth, --threads, --alluser, --allkernel, --alloc, --live, --include, --exclude).
- New AsProfPermissionCheck performs Linux pre-flight (perf_event_paranoid, kptr_restrict, ptrace_scope, container detection) before invoking asprof.
- async-profiler version pin moved 3.0 → 4.4. Real SHA-256 verification for linux-x64 / linux-arm64 / macOS. Linux musl is upstream-unsupported post-2.9, retained as fail-closed token.
- ProfileSnapshot model: append-only JSON snapshot with diff() returning per-method DiffEntry list. Foundation for --save / --diff / profile-gate.
- New AsciiFlameRenderer renders top-N hot stacks inline in the terminal as `--output-format=ascii`.
- New event/format surface: PMU + hardware-counter events (cycles, cache-misses, ...) and method-trace events (`ClassName.methodName`) accepted by a relaxed validator. Output formats added: flat, traces, otlp.

### New commands
- `argus profile-gate <before> <after>`: CI/CD threshold gate (exit 0/1/2, --format=json, --annotate=github).
- `argus profile continuous <pid>`: start + periodic dump + diff loop.
- `argus profile <pid1>,<pid2>` / `--pids=...`: parallel fan-out across multiple JVMs.

### Cross-command integration
- DoctorCommand consumes ProfileSnapshot (via `--profile`) and runs five rules in `io.argus.cli.doctor.ProfileRules`.
- SuggestCommand consumes ProfileSnapshot and emits flag recommendations via six rules in `io.argus.cli.suggest.ProfileSuggestions`.

### i18n / locale resolution
- `CliConfig.resolveDefaultLang()` reads `$LC_ALL` then `$LANG`, falling back to `en`. The existing `--lang=` CLI flag overrides.
- 19 message keys per locale migrated from MessageFormat-style `{0}` to printf `%s` to match `Messages.get`'s actual implementation.

## Post-1.1.0 Additions (master)

### Live GC counter source (`JdkGcProvider`)

Before this change, `JdkGcProvider.getGcInfo()` returned hard-coded zeros for collection counts and cumulative pause time because `jcmd GC.heap_info` does not expose those fields. That meant `argus gc` and any live-JVM diagnostic path that consumed `GcResult` showed accurate heap sizes but always-zero event counts, making frequency-based rules and overhead calculations unreliable.

Two alternatives were considered: reading PerfData counters directly (`sun.gc.*`) and querying `GarbageCollectorMXBean` via JMX. PerfData requires mapping the `/tmp/hsperfdata_<user>/<pid>` file which is fragile across container runtimes, and JMX requires an open connector port. The smallest correct change was to run `jstat -gcutil <pid>` — the same tool `JvmSnapshotCollector` already relies on — and parse its output through the existing `JdkGcUtilProvider.parseOutput()` helper. Overhead is then computed as `GCT / VM.uptime`.

Source: `argus-cli/src/main/java/io/argus/cli/provider/jdk/JdkGcProvider.java`

---

### `GcPressureRule` — high-frequency GC doctor rule

`GcOverheadRule` fires only when cumulative GC overhead exceeds 5% of elapsed time. A workload with thousands of fast young collections — allocation churn that keeps each individual pause short — stays well below that threshold while constantly interrupting the application and inflating p99 latency. The rule never fires, giving a false-healthy signal.

`GcPressureRule` addresses this by looking at frequency instead of time-share. It computes young-generation GCs per minute since JVM start and applies two thresholds: WARNING at > 200/min (with an average pause of at least 0.5 ms, to filter pure JIT blips) and CRITICAL at > 500/min. The rule skips the first 60 seconds of uptime where rates are unreliable. When heap usage is below 60%, it recommends allocation profiling; when heap is already full, it recommends increasing `-Xmx` or enlarging young gen explicitly. For CRITICAL findings it also recommends ZGC.

Source: `argus-cli/src/main/java/io/argus/cli/doctor/rules/GcPressureRule.java`

---

### `NmtCommand --diff` rendering and NMT-not-enabled sentinel

`NmtBaseline.diff()` was introduced in v1.1.0 as a model-layer operation. This cycle wired it into a dedicated rendering path in `NmtCommand`. When `--diff=<baseline-file>` is passed, the command loads the saved baseline via `NmtBaseline.load()`, computes the delta list, and renders a sorted-by-committed-growth table inside a `RichRenderer` box. The banner line shows the wall-clock timestamp of the saved baseline and the signed totals for reserved and committed delta. Categories with a committed delta >= 5% of their original size, or that are newly appeared, are highlighted in red/bold to draw attention to likely leaks.

NMT-not-enabled detection was also tightened at the provider boundary. `JdkNmtProvider.getNativeMemory()` now detects the `"Native memory tracking is not enabled"` string in `jcmd VM.native_memory summary` output and returns `NmtResult.notEnabled()` — a private sentinel constructor that sets the `nmtNotEnabled` flag — instead of returning an empty result that was silently rendered as if NMT had zero categories. `NmtCommand` checks `result.isNmtNotEnabled()` early and throws `CommandExitException(1)` with a clear error message and the `-XX:NativeMemoryTracking=summary` hint.

Sources:
- `argus-cli/src/main/java/io/argus/cli/command/NmtCommand.java`
- `argus-cli/src/main/java/io/argus/cli/provider/jdk/JdkNmtProvider.java`
- `argus-cli/src/main/java/io/argus/cli/model/NmtResult.java`
- `argus-cli/src/main/java/io/argus/cli/model/NmtBaseline.java`

---

### `AsProfProvider` flame sample-count fix and `--event` routing

The previous `flameGraph()` implementation called asprof twice: once to produce the HTML flame graph and once to produce collapsed output for sample-count parsing. That doubled profiling time and, worse, produced two separate recordings whose sample counts could differ. The fix is a single JFR capture followed by two cheap `jfrconv` post-processing passes: `jfrconv -o html` writes the final HTML and `jfrconv -o collapsed` writes a temporary file that is parsed for `totalSamples` and `topMethods`, then deleted. The JFR file itself is also cleaned up in a `finally` block.

The `--event` routing was also corrected. Previously both `argus flame` and `argus profile` issued the event flag through different code paths and the profile command lost the event value for certain format combinations. Both paths now go through `buildExtraArgs()` for option passthrough and `resolveCollapsedFormat()` for output-format resolution. The `ascii` format is handled as a special case: it maps to `collapsed` on the asprof command line and then the collapsed output is passed to `AsciiFlameRenderer` for terminal rendering via `ProfileResult.okWithRaw()`.

Event and platform capability metadata lives in `AsProfCapabilities`, a static table keyed by OS and arch that `argus doctor` consumes to show which events are available on the current host without requiring a live asprof invocation.

Sources:
- `argus-cli/src/main/java/io/argus/cli/provider/jdk/AsProfProvider.java`
- `argus-cli/src/main/java/io/argus/cli/provider/jdk/AsProfCapabilities.java`

---

### `GcWhyJfrCollector` — `GCHeapSummary` correlation by `gcId`

`GcWhyJfrCollector` reads a JFR recording and correlates two event types: `jdk.GarbageCollection` (pause type, cause, duration, `gcId`) and `jdk.GCHeapSummary` (heap used/committed with a `when` label of `"Before GC"` or `"After GC"`). An earlier version matched heap summary events by position in the file, which broke when concurrent GC phases interleaved summaries. The fix joins both maps on `gcId` — `heapBefore` and `heapAfter` are both keyed by `gcId` — and the `when` field match uses `contains("before")` / `contains("after")` on the lowercased string to handle the JFR `GCWhen` enum label format reliably across JDK versions.

This collector is the shared data path for both `GcWhyCommand` (which uses it in its live 30-second JFR capture loop) and the `GcScoreCommand` live-PID branch described below.

Source: `argus-cli/src/main/java/io/argus/cli/gcwhy/GcWhyJfrCollector.java`

---

### `GcScoreCommand` live-PID branch

`GcScoreCommand` previously only accepted a GC log file path. The live-PID branch mirrors the capture loop that `GcWhyCommand` uses: it starts a JFR recording via `jcmd JFR.start`, sleeps for `--duration` seconds (default 30), dumps the recording to a temp file via `jcmd JFR.dump`, then parses it through `GcWhyJfrCollector.collect()`. The resulting `List<GcEvent>` is fed into the same `GcLogAnalyzer.analyze()` + `GcScoreCalculator.compute()` pipeline as the file path. The first argument is treated as a PID when it is a pure digit string with no path separator or dot characters.

Source: `argus-cli/src/main/java/io/argus/cli/command/GcScoreCommand.java`

---

### `GcLogCommand` aggregated-summary view

`GcLogCommand` gained a by-cause aggregation table. After the standard analysis pass, the cause breakdown from `GcLogAnalysis.causeBreakdown()` is rendered as a sortable table showing, per GC cause: event count, total pause time, average pause, p99 pause, and max pause. The default view shows the top 8 causes by count plus an `Other` rollup row. `--top=N` overrides the limit and `--all` suppresses the rollup entirely.

The p99 value is populated by the new `p99Ms` field on `GcLogAnalysis.CauseStats`. When a cause has fewer than 100 events, the sorted-tail proxy equals `maxMs` (the full constructor delegates to the existing max for backward compatibility via the legacy constructor). When the sample size is sufficient, `GcLogAnalyzer` sorts the per-cause pause list and picks the 99th-percentile entry.

Sources:
- `argus-cli/src/main/java/io/argus/cli/command/GcLogCommand.java`
- `argus-cli/src/main/java/io/argus/cli/gclog/GcLogAnalysis.java`

---

### ZGC command path (`ZgcCommand`)

`ZgcCommand` → JMX pre-check (`VirtualMachine.attach` + `GarbageCollectorMXBean` name scan) → `jcmd JFR.start settings=profile` (capture window, default 30s) → `jcmd JFR.dump` → `ZgcJfrCollector.collect()` → `ZgcDiagnosis.compute()` → verdict + recommendations.

`ZgcJfrCollector` subscribes to six JFR event types:

| Event | Purpose |
|-------|---------|
| `jdk.ZAllocationStall` | Per-thread allocation stalls (thread name, duration) |
| `jdk.ZGarbageCollection` | Non-generational ZGC cycles (duration, overlap detection) |
| `jdk.ZYoungGarbageCollection` | Generational minor cycles |
| `jdk.ZOldGarbageCollection` | Generational major cycles |
| `jdk.GarbageCollection` | STW phase durations (Pause Mark Start / End / Relocate Start) |
| `jdk.GCHeapSummary` | Committed heap samples for soft-max breach detection |

`ZgcDiagnosis.compute()` verdict logic: UNHEALTHY when stalls are present or cycles overlap; WARNING when soft-max is breached or Pause Mark End > 1.0 ms; HEALTHY otherwise.

Generational detection: `ZgcJfrCollector` sets `diagnosis.generational = true` when `jdk.ZYoungGarbageCollection` or `jdk.ZOldGarbageCollection` events are observed. `ZgcCommand.inspectTarget()` also sets this flag when MBean names contain `"ZGC Major Cycles"` or `"ZGC Minor Cycles"`.

Sources:
- `argus-cli/src/main/java/io/argus/cli/command/ZgcCommand.java`
- `argus-cli/src/main/java/io/argus/cli/zgc/ZgcJfrCollector.java`
- `argus-cli/src/main/java/io/argus/cli/zgc/ZgcDiagnosis.java`

---

### ZGC trend tracking — `ZgcBaseline`, `--save`, `--diff`, `--watch`, `--interval`

`ZgcBaseline` is a new plain-text persistence layer (key=value, one per line) that mirrors `NmtBaseline`'s format. It stores all `ZgcDiagnosis` fields as well as derived stall aggregates (count, totalMs, maxMs, maxThread) and the top allocation frame list encoded as `frame|pct` entries separated by semicolons.

`ZgcBaseline.save(path, diagnosis, pid)` serialises the current `ZgcDiagnosis` to a file. `ZgcBaseline.load(path)` deserialises it back. `ZgcBaseline.diff(baseline, current)` produces a `List<DiffRow>`, one row per tracked metric:

```
DiffRow(label, baselineValue, currentValue, delta, severity)
```

`severity` is drawn from `ZgcBaseline.Severity` which has three values: `INFO`, `WARN`, `REGRESSION`. REGRESSION conditions:

| Metric | REGRESSION trigger |
|--------|--------------------|
| `heapCommitted` | committed crossed above SoftMaxHeapSize (baseline was under, current is over) |
| `stallCount` | baseline=0 → current>0 (new stalls) |
| `majorCycles` | minor:major ratio worsened by >50% |
| `pauseMarkEnd` | grew by >50% relative to baseline |
| `softMaxBreached` | false → true |

`ZgcCommand` dispatch logic:

- `--save=PATH` — runs `captureOnce()`, renders the normal report, then calls `ZgcBaseline.save()`.
- `--diff=PATH` — runs `captureOnce()`, calls `ZgcBaseline.load()` + `ZgcBaseline.diff()`, renders the diff table (suppresses normal verdict).
- `--watch[=N]` — calls `runWatch()`, which loops `captureOnce()` for N iterations (0 = unlimited). Each iteration calls `printWatchLine()` for a 1-line delta summary; every 5th iteration calls `printReport()` for the full table.
- `--interval=N` — sets both the JFR capture duration inside `captureOnce()` and the watch loop period (clamped to 10–300 s, default 30).

`runWatch()` registers a JVM shutdown hook that stops any live JFR recording and prints a final summary line (total iterations, total stalls seen, total softMax breaches). The same cleanup runs in the `finally` block on normal loop exit.

Sources:
- `argus-cli/src/main/java/io/argus/cli/command/ZgcCommand.java`
- `argus-cli/src/main/java/io/argus/cli/zgc/ZgcBaseline.java`

---

### ZGC allocation hotspot cross-reference (`ZgcJfrCollector`, `ZgcDiagnosis`)

`ZgcJfrCollector` now subscribes to two additional JFR event types: `jdk.ObjectAllocationInNewTLAB` and `jdk.ObjectAllocationOutsideTLAB`. Both are emitted by the `profile.jfc` settings profile that `ZgcCommand` already uses, so no additional JFR configuration is required.

For each allocation event the collector calls `extractTopUserFrame()` to obtain a formatted stack-frame string and increments a `Map<String, Long> allocCounts` counter for that frame. After reading the full recording:

1. `d.totalAllocEvents` is set to the sum of all allocation event counts.
2. If `d.stalls` is non-empty **and** `totalAlloc > 0`, the top-5 frames by count are converted to `AllocHotspot(frame, count, pct)` records and stored in `d.stallAllocHotspots`. If either condition is false, the list remains empty.

`ZgcDiagnosis` carries two new fields:

```java
public final List<AllocHotspot> stallAllocHotspots = new ArrayList<>();
public long totalAllocEvents;
```

`AllocHotspot` is a record: `AllocHotspot(String frame, long count, double pct)`.

`extractTopUserFrame()` walks the event stack trace and returns the first frame whose class name does not start with `java.`, `jdk.`, `sun.`, or `com.sun.`. When all frames belong to JDK-internal packages, it falls back to the first frame. Returns `null` when no stack trace is present.

`ZgcCommand.printReport()` renders the hotspot list under the Allocation Stalls section when `stallAllocHotspots` is non-empty, or prints a dim "no alloc events recorded" note when stalls were detected but no allocation events were present.

Sources:
- `argus-cli/src/main/java/io/argus/cli/zgc/ZgcJfrCollector.java`
- `argus-cli/src/main/java/io/argus/cli/zgc/ZgcDiagnosis.java`
- `argus-cli/src/main/java/io/argus/cli/command/ZgcCommand.java`

---

### New Doctor rules — ZGC

Two new rules under `argus-cli/src/main/java/io/argus/cli/doctor/rules/`:

- **`ZgcSoftMaxBreachRule`** (WARNING): fires when `heapCommitted > SoftMaxHeapSize > 0`. Parses `-XX:SoftMaxHeapSize=N` from the `vmFlags` list (raw bytes). Skipped entirely for non-ZGC collectors (`gcAlgorithm().contains("ZGC")` guard).

- **`ZgcCycleOverlapRule`** (CRITICAL): fires when `avgDurationMs > intervalMs * 0.8` for any ZGC collector with more than 5 cycles (MIN_CYCLE_COUNT = 5, OVERLAP_THRESHOLD = 0.8). Uses `uptimeMs / count` as the proxy for average inter-cycle interval.

`GcAlgorithmRule` also gained a Generational ZGC hint: when `gcAlgorithm` equals `"ZGC"` (not `"ZGC (Generational)"`) and the JVM major version is 21–23, it emits an INFO finding recommending `-XX:+ZGenerational`. No hint is emitted on JDK 24+ where generational mode is the default.

---

### `JvmSnapshotCollector` — Generational ZGC detection

`JvmSnapshotCollector.canonicalGcAlgorithm()` returns `"ZGC (Generational)"` when the MBean name set contains `"ZGC Major Cycles"` or `"ZGC Minor Cycles"`. Downstream consumers (`ZgcSoftMaxBreachRule`, `ZgcCycleOverlapRule`, `GcAlgorithmRule`, `GcScoreCalculator`) use `algo.contains("ZGC")` for broad ZGC matching and `algo.equals("ZGC")` for the plain non-generational case.

---

### GC log Allocation Stall parsing

`GcLogPatterns.ALLOCATION_STALL` regex:

```
Allocation Stall \(([^)]+)\)\s+(\d+\.?\d*)ms
```

`GcLogParser` matches this pattern and accumulates `AllocationStallSummary` (count, totalMs, maxMs, topThread, topThreadMs). `GcLogAnalysis` exposes the summary via `allocationStalls()`. When `allocationStalls() != null`, `GcLogCommand` renders an **Allocation Stalls (ZGC)** section before the GC Pause Summary.

Sources:
- `argus-cli/src/main/java/io/argus/cli/gclog/GcLogPatterns.java`
- `argus-cli/src/main/java/io/argus/cli/gclog/GcLogAnalysis.java` (`AllocationStallSummary` inner class)

---

### `GcScoreCalculator` — ZGC weight branch

When `gcAlgorithm.toUpperCase().contains("ZGC")`:

- `scorePauseP99Zgc()` — PASS threshold tightened to 5 ms (vs 200 ms standard).
- `scorePauseTailZgc()` — PASS threshold tightened to 10 ms (vs 500 ms standard).
- `scoreZgcAllocationPressure()` — new axis keyed on cycle frequency (`totalEvents / durationSec`); PASS < 0.5/s, FAIL ≥ 1.0/s.
- `weightedOverall()` ZGC weight vector: `[0.12, 0.08, 0.30, 0.15, 0.10, 0.10, 0.15]` (p99, tail, throughput, fullGcFreq, allocRate, promoRatio, allocPressure).

Source: `argus-cli/src/main/java/io/argus/cli/gcscore/GcScoreCalculator.java`

---

## v1.2.0 Architecture Additions

### `argus zgc` command (`ZgcCommand`, `ZgcDiagnosis`, `ZgcJfrCollector`, `ZgcBaseline`)

- **`ZgcCommand`**: JMX pre-check confirms ZGC is active; starts a `jcmd JFR.start settings=profile` capture (default 30s), dumps to temp file, passes through `ZgcJfrCollector` → `ZgcDiagnosis.compute()` → verdict + recommendations. `--save`/`--diff`/`--watch`/`--interval` flags for trend tracking and live load-test monitoring.
- **`ZgcJfrCollector`**: Parses six event types (`jdk.ZAllocationStall`, `jdk.ZGarbageCollection`, `jdk.ZYoungGarbageCollection`, `jdk.ZOldGarbageCollection`, `jdk.GarbageCollection`, `jdk.GCHeapSummary`). Also subscribes to `jdk.ObjectAllocationInNewTLAB` and `jdk.ObjectAllocationOutsideTLAB` from the same `profile.jfc` capture; produces top-5 `AllocHotspot` records when stalls are present.
- **`ZgcDiagnosis`**: Verdict logic — UNHEALTHY (stalls present or cycles overlap), WARNING (soft-max breached or Pause Mark End > 1 ms), HEALTHY otherwise. Carries `stallAllocHotspots` list and `totalAllocEvents` counter.
- **`ZgcBaseline`**: Plain key=value persistence (mirrors `NmtBaseline` format). `save/load/diff` API. `diff()` emits `DiffRow(label, baseline, current, delta, severity)` with REGRESSION conditions on heap breach, new stalls, major-cycle ratio worsening, pause growth > 50%, and softMax transition.

### New Doctor rules — ZGC

- **`ZgcSoftMaxBreachRule`** (WARNING): fires when `heapCommitted > SoftMaxHeapSize > 0`; skipped for non-ZGC collectors.
- **`ZgcCycleOverlapRule`** (CRITICAL): fires when `avgDurationMs > intervalMs × 0.8` with > 5 cycles.
- **`GcAlgorithmRule`**: Generational ZGC hint — when `gcAlgorithm == "ZGC"` (not Generational) and JDK 21–23, emits INFO recommending `-XX:+ZGenerational`. No hint on JDK 24+ where generational is default.

### `JvmSnapshotCollector` — Generational ZGC detection

`canonicalGcAlgorithm()` returns `"ZGC (Generational)"` when MBean names include `"ZGC Major Cycles"` or `"ZGC Minor Cycles"`. All downstream consumers (`ZgcSoftMaxBreachRule`, `ZgcCycleOverlapRule`, `GcAlgorithmRule`, `GcScoreCalculator`) use `algo.contains("ZGC")` for broad matching and `algo.equals("ZGC")` for the plain non-generational case.

### GC log Allocation Stall parsing

- `GcLogPatterns.ALLOCATION_STALL` regex matches `Allocation Stall (<thread>)  <N>ms` lines.
- `GcLogParser` accumulates `AllocationStallSummary` (count, totalMs, maxMs, topThread, topThreadMs).
- `GcLogCommand` renders an **Allocation Stalls (ZGC)** section when stalls are present.

### `GcScoreCalculator` — ZGC weight branch

When `gcAlgorithm.toUpperCase().contains("ZGC")`: pause thresholds tightened (p99 PASS = 5 ms, tail PASS = 10 ms); new `scoreZgcAllocationPressure()` axis keyed on cycle frequency (PASS < 0.5/s, FAIL ≥ 1.0/s); ZGC weight vector `[0.12, 0.08, 0.30, 0.15, 0.10, 0.10, 0.15]`.

### `SuggestCommand` — `--advanced` flag and ZGC-only flag suggestions

`--advanced` unlocks a second tier of flag recommendations (experimental/non-default JVM options). ZGC-specific suggestions (`-XX:SoftMaxHeapSize`, `-XX:ConcGCThreads`, `-XX:+ZGenerational`) are emitted only when the detected collector is ZGC.

### 3 quality-of-life features (earlier in the cycle)

- **`AsProfCapabilities` matrix**: static event/platform table consumed by `argus doctor` to show available asprof events without a live invocation.
- **NMT-not-enabled detection**: `JdkNmtProvider` returns `NmtResult.notEnabled()` when `jcmd VM.native_memory summary` outputs the not-enabled sentinel; `NmtCommand` exits 1 with a clear hint rather than rendering an empty table.
- **`GcLogCommand` aggregated pause summary**: by-cause table (count, total, avg, p99, max per GC cause) with `--top=N` / `--all` controls.

### 7 live-JVM diagnostic fixes

- `JdkGcProvider`: replaces hard-coded zero counts with `jstat -gcutil` parsing; overhead computed as `GCT / VM.uptime`.
- `GcPressureRule`: new doctor rule firing on young-GC frequency (WARNING > 200/min, CRITICAL > 500/min) independent of overhead percentage.
- `NmtCommand --diff`: wires `NmtBaseline.diff()` into a rendered table with signed totals and highlighted growers.
- `AsProfProvider`: single JFR capture + two cheap `jfrconv` post-processing passes instead of two separate recordings; `--event` routing unified through `buildExtraArgs()`.
- `GcWhyJfrCollector`: heap summary correlation by `gcId` instead of file position; `when` field matched with `contains("before"/"after")` for cross-JDK compatibility.
- `GcScoreCommand`: live-PID branch using `jcmd JFR.start/dump` → `GcWhyJfrCollector` → `GcLogAnalyzer` pipeline; first argument treated as PID when pure digit string.
- `GcWhyCommand` / `GcScoreCommand` `--duration`: duration parameter now wired through to the JFR capture window.

### Documentation refactor

- README slimmed 469 → 107 lines; feature narrative moved to `docs/`.
- `docs/cli-commands.md` split into 5 category files under `docs/commands/`.
- New `docs/contributing.md` covering build, commit, i18n, and release procedures.
- `CLAUDE.md` slimmed with a pointer table to `docs/architecture.md` for full patterns.

## Next Steps

- [Getting Started](getting-started.md) - Installation guide
- [Configuration](configuration.md) - Tuning options
- [Troubleshooting](troubleshooting.md) - Common issues
