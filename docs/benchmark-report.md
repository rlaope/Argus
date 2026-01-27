# Argus Overhead Benchmark Report

## Overview

This document presents the performance overhead measurements of Argus Virtual Thread Profiler. The benchmark quantifies the impact of attaching the Argus agent to a Java application.

## Test Environment

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

## Summary (Basic Monitoring)

| Metric | Overhead |
|--------|----------|
| Throughput | -9.3% |
| Memory | +3.6 MB per 10K threads |
| Latency | No significant impact |
| GC | No additional GC pressure |

## Advanced Profiling Features (Phase 3-5)

The following features are **disabled by default** due to higher overhead:

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

## Conclusion

Argus introduces approximately **9% throughput overhead** when profiling virtual thread events via JFR streaming. Memory overhead is minimal at **3.6 MB additional heap usage per 10,000 virtual threads**, representing less than 1% of the allocated heap. There is no measurable impact on latency or garbage collection behavior.

**Note:** Advanced profiling features (allocation tracking, method profiling, contention tracking) are disabled by default due to potentially high event volume. Enable them only when needed for debugging or optimization, preferably for short durations.

These overhead levels are acceptable for development and staging environments. For production use, consider the throughput trade-off based on your application's performance requirements.

## How to Run

```bash
# Build the project
./gradlew build

# Run baseline benchmark (no Argus)
./gradlew :samples:benchmark:runBaseline

# Run with Argus agent
./gradlew :samples:benchmark:runWithArgusAgent

# Run with Argus agent + WebSocket server
./gradlew :samples:benchmark:runWithArgusServer

# Run all benchmarks and compare
./samples/benchmark/run-benchmark.sh
```

## Benchmark Configuration

The benchmark can be customized by modifying `samples/benchmark/src/main/java/io/argus/benchmark/OverheadBenchmark.java`:

```java
private static final int WARMUP_ITERATIONS = 3;
private static final int BENCHMARK_ITERATIONS = 5;
private static final int THREADS_PER_ITERATION = 10_000;
private static final int WORK_UNITS = 1000;
```
