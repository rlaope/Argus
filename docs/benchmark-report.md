# Argus Overhead Benchmark Report

## Overview

This document presents the performance overhead measurements of Argus v1.1.0. It covers three
measurement areas:

1. **Agent overhead** — cost of attaching `argus-agent` to a running JVM (JFR streaming).
2. **Profiling overhead** — cost of `argus profile <PID>` (async-profiler in-process).
3. **CI gate overhead** — cost of `argus profile-gate` (snapshot diff, one-shot, no attach).

> **Note on agent + server numbers:** The numbers in the "Benchmark Results" section were captured
> at commit `1da0347` (2026-01-20) against an earlier build. The benchmark harness
> (`samples/benchmark/run-benchmark.sh`) was not re-executed in the current environment because it
> requires a running JVM target and network connectivity for the argus-server mode. Methodology is
> unchanged; re-run locally to get fresh numbers for your hardware.

## Test Environment (agent benchmark)

| Item | Value |
|------|-------|
| JVM | Eclipse Adoptium 21.0.9 |
| Heap Size | 512 MB (-Xms512m -Xmx512m) |
| Virtual Threads per Iteration | 10,000 |
| Warmup Iterations | 3 |
| Benchmark Iterations | 5 |
| Workload | CPU-bound (Math.sin/cos/tan calculations) |

## Benchmark Results

### Throughput

| Mode | Throughput (tasks/sec) | Std Dev |
|------|------------------------|---------|
| Baseline (No Argus) | 182,130 | 24,154 |
| Argus Agent + Server | 165,114 | 11,851 |

**Throughput Overhead: 9.3%** (17,016 tasks/sec reduction)

### Latency

| Mode | p50 | p95 | p99 | Max |
|------|-----|-----|-----|-----|
| Baseline | 0.034 ms | 0.084 ms | 0.163 ms | 9.820 ms |
| Argus | 0.033 ms | 0.085 ms | 0.132 ms | 3.861 ms |

**Latency Overhead: Negligible** (within measurement variance)

### Memory

| Mode | Heap Delta per Iteration |
|------|--------------------------|
| Baseline | 4.40 MB |
| Argus | 8.00 MB |

**Memory Overhead: +3.6 MB per 10,000 virtual threads** (0.7% of total heap)

### Garbage Collection

| Mode | GC Count | GC Time |
|------|----------|---------|
| Baseline | 0 | 0 ms |
| Argus | 0 | 0 ms |

**GC Overhead: None observed**

## Summary (Agent Monitoring)

| Metric | Overhead |
|--------|----------|
| Throughput | -9.3% |
| Memory | +3.6 MB per 10K threads |
| Latency | No significant impact |
| GC | No additional GC pressure |

## Profiling Overhead

### `argus profile <PID>` (async-profiler)

`argus profile` attaches async-profiler to the target JVM and collects a stack-trace snapshot for
the requested duration (default: 30 seconds). It does not use JFR streaming.

| Property | Value |
|----------|-------|
| Mechanism | async-profiler JVMTI attach |
| Default event | `cpu` (POSIX signal-based sampling) |
| Default duration | 30 s |
| Default sampling rate | async-profiler default (100 Hz for CPU mode) |
| Overhead | Negligible at default rate (<1% CPU, no allocation pressure) |
| Effect on target | Profiler attaches and detaches cleanly; no persistent agent left behind |

Other supported events: `alloc`, `lock`, `wall`, `itimer`, or any PMU counter. Higher-frequency
events (`alloc` at small thresholds, dense PMU counters) increase overhead proportionally — the
same trade-offs that apply to async-profiler standalone apply here.

```bash
# 30-second CPU profile (default)
argus profile <PID>

# 10-second allocation profile, save snapshot for later diff
argus profile <PID> --type=alloc --duration=10 --save=before.json

# Lock contention profile, ASCII flame graph to stdout
argus profile <PID> --type=lock --output-format=ascii

# HTML flame graph written to file
argus profile <PID> --flame
```

### `argus profile-gate` (CI regression detection)

`argus profile-gate` compares two saved profile snapshots and exits non-zero if any method's
CPU share increases beyond a threshold. It does **not** attach to a running JVM; overhead is
limited to loading and diffing two JSON files.

| Property | Value |
|----------|-------|
| Mechanism | JSON snapshot diff (no JVM attach) |
| Overhead | Negligible (file I/O + in-memory sort) |
| Default threshold | 10 percentage-points (pp) |
| Exit codes | 0 = pass, 1 = regression(s) detected, 2 = usage/IO error |

```bash
# Capture baseline before a change
argus profile <PID> --save=before.json

# Capture after the change
argus profile <PID> --save=after.json

# Compare — exits 1 if any method grows >= 10pp
argus profile-gate before.json after.json

# Tighter gate: fail if any method grows >= 5pp with at least 50 sample delta
argus profile-gate before.json after.json --threshold=5 --threshold-samples=50

# GitHub Actions: emit ::error:: annotations and JSON report
argus profile-gate before.json after.json --annotate=github --format=json
```

## Advanced Profiling Features (Agent Mode)

The following agent-mode features are **disabled by default** due to higher overhead:

### Feature Overhead Comparison

| Feature | Event Frequency | Overhead Level | Default |
|---------|----------------|----------------|---------|
| GC Monitoring | Low (few per min) | **Very Low** | `true` |
| CPU Monitoring | Low (1/sec) | **Very Low** | `true` |
| Metaspace Monitoring | Low (at GC) | **Very Low** | `true` |
| Allocation Tracking | High (millions/sec) | **High** | `false` |
| Method Profiling | Medium (50/sec) | **Medium-High** | `false` |
| Lock Contention | Variable | **Variable** | `false` |

### Why High-Overhead Features are Disabled

1. **Allocation Tracking** (`argus.allocation.enabled`)
   - JFR events: `jdk.ObjectAllocationInNewTLAB`, `jdk.ObjectAllocationOutsideTLAB`
   - Problem: Millions of objects allocated per second → millions of events
   - Mitigation: Use threshold ≥ 1MB to track only large allocations

2. **Method Profiling** (`argus.profiling.enabled`)
   - JFR event: `jdk.ExecutionSample`
   - Problem: Periodic stack trace capture of all threads at safepoints
   - Mitigation: Increase interval (e.g., 50-100ms)

3. **Lock Contention** (`argus.contention.enabled`)
   - JFR events: `jdk.JavaMonitorEnter`, `jdk.JavaMonitorWait`
   - Problem: High-concurrency apps may generate many contention events
   - Mitigation: Use threshold ≥ 50ms to track only significant contention

### Recommended Configurations

**Production (safe defaults):**
```bash
-Dargus.gc.enabled=true
-Dargus.cpu.enabled=true
-Dargus.metaspace.enabled=true
```

**Development/Testing (full profiling):**
```bash
-Dargus.allocation.enabled=true
-Dargus.allocation.threshold=1048576
-Dargus.profiling.enabled=true
-Dargus.profiling.interval=50
-Dargus.contention.enabled=true
-Dargus.contention.threshold=20
```

## When to Use Each Mode

| Symptom / Goal | Recommended command |
|----------------|---------------------|
| High CPU, want a flame graph | `argus profile <PID> --flame` |
| Identify hot methods without a browser | `argus profile <PID> --output-format=ascii` |
| Lock contention slowing virtual threads | `argus pool <PID>` |
| Prevent CPU regressions in CI | `argus profile-gate before.json after.json` |
| Continuous CPU sampling every N seconds | `argus profile continuous <PID> --interval=N` |
| Long-term visibility across many JVMs | `argus cluster scan --file=targets.txt` |
| Persistent agent + WebSocket dashboard | `argus-agent` attach + `argus-server` |

**Rule of thumb:**
- Use `argus profile` for ad-hoc investigation of a single live JVM.
- Use `argus profile-gate` in CI pipelines to catch regressions before they ship.
- Use `argus pool` when the symptom is lock or thread-pool contention specifically.
- Use the agent + server combination when you need long-term trending across a fleet.

## Conclusion

Argus introduces approximately **9% throughput overhead** when the agent is attached and streaming
JFR events. Memory overhead is minimal at **3.6 MB additional heap usage per 10,000 virtual
threads**, representing less than 1% of the allocated heap. Latency and GC are unaffected.

`argus profile` (async-profiler mode) adds negligible overhead at the default 100 Hz CPU sampling
rate and leaves no persistent state in the target JVM after the session ends.

`argus profile-gate` has no runtime overhead on the target JVM — it only reads and diffs two
snapshot files.

## How to Run the Agent Benchmark

```bash
# Build the project
./gradlew build

# Run baseline benchmark (no Argus)
./gradlew :samples:benchmark:runBaseline

# Run with Argus agent
./gradlew :samples:benchmark:runWithArgusAgent

# Run with Argus agent + WebSocket server
./gradlew :samples:benchmark:runWithArgusServer

# Run all three modes and print a comparison table
./samples/benchmark/run-benchmark.sh
```

## Benchmark Configuration

The benchmark can be customized by editing
`samples/benchmark/src/main/java/io/argus/benchmark/OverheadBenchmark.java`:

```java
private static final int WARMUP_ITERATIONS = 3;
private static final int BENCHMARK_ITERATIONS = 5;
private static final int THREADS_PER_ITERATION = 10_000;
private static final int WORK_UNITS = 1000;
```
