# Getting Started

This guide will help you get up and running with Project Argus in minutes.

## Prerequisites

Before you begin, ensure you have the following installed:

- **Java 21 or higher** - Argus requires Java 21+ for Virtual Thread support
- **Gradle 8.4+** (optional) - Only needed if building from source

### Verify Java Version

```bash
java -version
# Should output: openjdk version "21.x.x" or higher
```

## Installation

### Option 1: One-line Install (Recommended)

```bash
curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash
```

This installs to `~/.argus/` and adds the `argus` command to your PATH.

```bash
# Install a specific version
curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash -s -- v0.4.0
```

After installation, restart your terminal or run `source ~/.zshrc` (or `~/.bashrc`).

### Option 2: Manual Download

```bash
# Download JARs from GitHub Releases
curl -LO https://github.com/rlaope/argus/releases/latest/download/argus-agent-0.4.0.jar
curl -LO https://github.com/rlaope/argus/releases/latest/download/argus-cli-0.4.0-all.jar
```

### Option 3: Build from Source

```bash
git clone https://github.com/rlaope/argus.git
cd argus
./gradlew build
./gradlew :argus-cli:fatJar

# JARs:
# argus-agent/build/libs/argus-agent-0.4.0.jar
# argus-cli/build/libs/argus-cli-0.4.0-all.jar
```

## Quick Start with Sample Project

The easiest way to see Argus in action is to run the included sample project.

### Step 1: Build Everything

```bash
./gradlew build
```

### Step 2: Run the Sample with Argus

```bash
# Basic virtual thread demo
./gradlew :samples:virtual-thread-demo:runWithArgus

# Metrics demo with GC/CPU activity (recommended for testing dashboard)
./gradlew :samples:virtual-thread-simulation:runMetricsDemo
```

### Step 3: Open the Dashboard

Open your browser and navigate to: **http://localhost:9202/**

The dashboard displays two tabs:
- **Virtual Threads**: Thread events, active threads, pinning analysis
- **JVM Overview**: CPU, GC, heap, flame graph, profiling, contention, correlation

### Step 4: Use the CLI Monitor

```bash
# If installed via install.sh
argus

# Or run directly
java -jar argus-cli/build/libs/argus-cli-0.4.0-all.jar
```

The CLI shows an htop-style terminal UI with CPU, heap, GC, virtual threads, hot methods, and contention data.

### Step 5: Enable Flame Graph

```bash
# Run with profiling enabled for flame graph data
./gradlew :samples:virtual-thread-simulation:runMetricsDemoFull
```

The flame graph appears in the dashboard under JVM Overview tab. It shows which methods consume the most CPU time.

### Step 6: View the Output

You'll see output like this:

```
   _____
  /  _  \_______  ____  __ __  ______
 /  /_\  \_  __ \/ ___\|  |  \/  ___/
/    |    \  | \/ /_/  >  |  /\___ \
\____|__  /__|  \___  /|____//____  >
        \/     /_____/            \/

Virtual Thread Profiler vdev
[Argus] Initializing JFR streaming engine...
[Argus] Agent initialized successfully
[Argus] Ring buffer size: 65536
[Argus] JFR streaming started
============================================================
  Virtual Thread Demo - Argus Monitoring Example
============================================================

[Demo 1] Basic Virtual Threads
----------------------------------------
  Task 0 started on VirtualThread[#31]/runnable@ForkJoinPool-1-worker-1
  Task 1 started on VirtualThread[#33]/runnable@ForkJoinPool-1-worker-7
  ...
  All basic tasks completed.

[Demo 3] Thread Pinning (synchronized blocks)
----------------------------------------
  WARNING: This demonstrates thread pinning - avoid in production!
  Task 0 acquired lock (PINNED to carrier thread)
  ...
[Argus] PINNED: thread=61, carrier=-1, duration=204333917ns
[Argus] PINNED: thread=63, carrier=-1, duration=201776666ns
[Argus] PINNED: thread=62, carrier=-1, duration=204995125ns
  ...

[Argus] Shutting down...
[Argus] JFR streaming stopped. Total events processed: 29
```

## Understanding the Output

### Event Types

#### Virtual Thread Events

| Event | Description | When it Occurs |
|-------|-------------|----------------|
| `VIRTUAL_THREAD_START` | Thread created | `Thread.startVirtualThread()` or executor submit |
| `VIRTUAL_THREAD_END` | Thread terminated | Virtual thread completes or is interrupted |
| `VIRTUAL_THREAD_PINNED` | Thread pinned to carrier | **Critical!** `synchronized` block or native call |
| `VIRTUAL_THREAD_SUBMIT_FAILED` | Submission failed | Executor rejected task |

#### GC Events

| Event | Description |
|-------|-------------|
| `GC_PAUSE` | Garbage collection occurred |
| `GC_HEAP_SUMMARY` | Heap usage snapshot |

#### CPU Events

| Event | Description |
|-------|-------------|
| `CPU_LOAD` | JVM and system CPU utilization |

### Reading PINNED Events

```
[Argus] PINNED: thread=61, carrier=-1, duration=204333917ns
         │             │           │              │
         │             │           │              └─ Duration pinned (~204ms)
         │             │           └─ Carrier thread ID
         │             └─ Virtual thread ID
         └─ Event type
```

**Why Pinning Matters:**

Pinning occurs when a virtual thread cannot be unmounted from its carrier thread. This defeats the purpose of virtual threads and can cause performance issues.

Common causes:
- `synchronized` blocks (use `ReentrantLock` instead)
- Native method calls
- JNI operations

## Using Argus with Your Application

### Basic Usage

```bash
java -javaagent:~/.argus/argus-agent.jar \
     -jar your-application.jar

# Or using the helper command
java -javaagent:$(argus-agent --path) \
     -jar your-application.jar
```

### With Full Profiling

```bash
java -javaagent:~/.argus/argus-agent.jar \
     -Dargus.profiling.enabled=true \
     -Dargus.contention.enabled=true \
     -jar your-application.jar
```

### With OTLP Export

```bash
java -javaagent:~/.argus/argus-agent.jar \
     -Dargus.otlp.enabled=true \
     -Dargus.otlp.endpoint=http://localhost:4318/v1/metrics \
     -jar your-application.jar
```

### Example: Spring Boot Application

```bash
java -javaagent:~/.argus/argus-agent.jar \
     -Dargus.profiling.enabled=true \
     -jar myapp.jar \
     --spring.threads.virtual.enabled=true
```

## Sample Project Details

The sample project ([samples/virtual-thread-demo](../samples/virtual-thread-demo)) demonstrates four scenarios:

### Demo 1: Basic Virtual Threads

```java
// Creates 5 virtual threads that sleep for 100ms
for (int i = 0; i < 5; i++) {
    Thread.startVirtualThread(() -> {
        Thread.sleep(100);
    });
}
```

**What to observe:** `VIRTUAL_THREAD_START` and `VIRTUAL_THREAD_END` events.

### Demo 2: Concurrent HTTP Requests

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    // Each HTTP request runs on its own virtual thread
    executor.submit(() -> httpClient.send(request, ...));
}
```

**What to observe:** Multiple virtual threads handling I/O concurrently.

### Demo 3: Thread Pinning

```java
synchronized (lock) {
    Thread.sleep(200);  // Pinning occurs here!
}
```

**What to observe:** `VIRTUAL_THREAD_PINNED` events with duration.

> **Warning:** Avoid `synchronized` with virtual threads. Use `ReentrantLock` instead:
> ```java
> lock.lock();
> try {
>     Thread.sleep(200);  // No pinning!
> } finally {
>     lock.unlock();
> }
> ```

### Demo 4: High Throughput

```java
// Creates 1000 virtual threads rapidly
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    IntStream.range(0, 1000).forEach(i ->
        executor.submit(() -> computeTask())
    );
}
```

**What to observe:** Argus handles high event rates efficiently.

## Interpreting Results

### Healthy Application

```
[Argus] JFR streaming stopped. Total events processed: 2000
```

- Many START/END events
- Zero or few PINNED events
- No SUBMIT_FAILED events

### Application with Issues

```
[Argus] PINNED: thread=100, carrier=5, duration=500000000ns
[Argus] PINNED: thread=101, carrier=5, duration=500000000ns
[Argus] PINNED: thread=102, carrier=5, duration=500000000ns
```

- Frequent PINNED events indicate synchronization issues
- Long durations (>100ms) suggest blocking operations in synchronized blocks
- Same carrier thread repeatedly pinned suggests a contention hotspot

### Actions to Take

| Observation | Action |
|-------------|--------|
| Frequent PINNED events | Replace `synchronized` with `ReentrantLock` |
| Long PINNED durations | Move blocking operations outside synchronized blocks |
| SUBMIT_FAILED events | Increase executor capacity or check for resource exhaustion |

## Next Steps

- [Configuration Guide](configuration.md) - Learn about all configuration options
- [Architecture Overview](architecture.md) - Understand how Argus works
- [Troubleshooting](troubleshooting.md) - Solutions for common issues
- [Sample Project](../samples/virtual-thread-demo) - Explore the demo code
