# Troubleshooting

This guide covers common issues and their solutions.

## Agent Issues

### Agent Not Starting

**Symptom**: No Argus banner appears when starting the application.

**Possible Causes**:

1. **Incorrect agent path**
   ```bash
   # Check if the JAR exists
   ls -la path/to/argus-agent.jar

   # Use absolute path
   java -javaagent:/absolute/path/to/argus-agent.jar ...
   ```

2. **Java version too old**
   ```bash
   # Verify Java version (must be 21+)
   java -version
   ```

### No Events Being Captured

**Symptom**: Agent starts but no events appear.

**Possible Causes**:

1. **Application not using virtual threads**
   ```java
   // Make sure you're using virtual threads
   Thread.startVirtualThread(() -> { ... });
   // or
   Executors.newVirtualThreadPerTaskExecutor();
   ```

2. **JFR not enabled**
   ```bash
   # JFR should be enabled automatically, but you can verify
   java -XX:+FlightRecorder -javaagent:argus-agent.jar ...
   ```

3. **Events happening too fast**
   - Increase buffer size: `-Dargus.buffer.size=131072`

### High Memory Usage

**Symptom**: Application memory increases significantly with Argus.

**Solutions**:

1. **Reduce buffer size**
   ```bash
   java -javaagent:argus-agent.jar \
        -Dargus.buffer.size=16384 \
        -jar app.jar
   ```

2. **Check for event storms**
   - If creating thousands of virtual threads per second, consider sampling

## Server Issues

### Server Won't Start

**Symptom**: `Address already in use` error.

**Solution**:
```bash
# Check what's using the port
lsof -i :9202

# Use a different port
java -Dargus.server.port=9090 -jar argus-server.jar
```

### WebSocket Connection Refused

**Symptom**: Cannot connect to WebSocket endpoint.

**Checklist**:

1. **Verify server is running**
   ```bash
   curl http://localhost:9202/health
   # Should return: {"status":"healthy","clients":0}
   ```

2. **Check firewall settings**
   ```bash
   # macOS
   sudo pfctl -sr | grep 9202

   # Linux
   sudo iptables -L -n | grep 9202
   ```

3. **Verify WebSocket URL**
   ```javascript
   // Correct
   const ws = new WebSocket('ws://localhost:9202/events');

   // Wrong
   const ws = new WebSocket('ws://localhost:9202/');  // Missing /events
   ```

### No Events in WebSocket

**Symptom**: Connected to WebSocket but no events received.

**Possible Causes**:

1. **Agent and server not sharing buffer**
   - In standalone mode, the server has its own empty buffer
   - Agent and server must run in the same JVM or use network transport

2. **Application idle**
   - Events only occur when virtual threads are created/destroyed

**Test with sample data**:
```bash
# Send test message
wscat -c ws://localhost:9202/events
> ping
< pong
```

## Build Issues

### Gradle Build Fails

**Symptom**: `Could not find a Java installation matching: {languageVersion=21}`

**Solution**:
```bash
# Install Java 21
# macOS with Homebrew
brew install openjdk@21

# Add to jenv (if using)
jenv add /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
jenv local 21

# Or set JAVA_HOME
export JAVA_HOME=/path/to/jdk-21
```

### Compilation Errors

**Symptom**: Compilation errors when building from source.

**Solution**: Ensure Java 21 toolchain is configured in build.gradle.kts:
```kotlin
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
```

## Performance Issues

### High CPU Usage

**Symptom**: CPU spikes when using Argus.

**Possible Causes**:

1. **Too many events**
   - Application creating/destroying many virtual threads
   - Consider reducing event frequency

2. **Small buffer causing contention**
   - Increase buffer size: `-Dargus.buffer.size=131072`

### Event Loss

**Symptom**: Missing events in the stream.

**Causes and Solutions**:

1. **Buffer overflow**
   ```bash
   # Increase buffer size
   -Dargus.buffer.size=262144
   ```

2. **Slow consumers**
   - Ensure WebSocket clients process events quickly
   - Consider adding client-side buffering

## Common Error Messages

### `java.lang.NoClassDefFoundError: jdk/jfr/consumer/RecordingStream`

**Cause**: JFR streaming API not available.

**Solution**: Use JDK 14+ (JDK 21+ recommended for virtual threads).

### `UnsupportedClassVersionError`

**Cause**: Compiled with newer Java version than runtime.

**Solution**: Ensure runtime Java version >= compile version.
```bash
# Check versions
java -version        # Runtime
./gradlew -version   # Compile (uses toolchain)
```

### `IllegalStateException: Server already running`

**Cause**: Attempting to start server twice.

**Solution**: Only call `server.start()` once, or stop before restarting.

## CLI Issues

### CLI Shows "CONNECTION FAILED"

**Symptom**: CLI displays "Cannot connect to Argus server".

**Solutions**:

1. **Verify server is running**
   ```bash
   curl http://localhost:9202/health
   ```

2. **Check host and port**
   ```bash
   argus --host 192.168.1.100 --port 9202
   ```

3. **Firewall blocking connection**
   - Ensure port 9202 is accessible from the CLI host

### CLI Shows All Zeros

**Symptom**: CLI connects but all metrics show 0.

**Cause**: The monitored application may be idle.

**Solution**: Generate some load on the application and metrics will appear.

## Flame Graph Issues

### Flame Graph Not Showing

**Symptom**: Dashboard shows "Enable profiling" placeholder.

**Solutions**:

1. **Enable profiling**
   ```bash
   java -javaagent:argus-agent.jar \
        -Dargus.profiling.enabled=true \
        -jar your-app.jar
   ```

2. **Wait for data** — flame graph needs a few seconds of sampling data

3. **Check CDN access** — flame graph requires loading d3-flamegraph from CDN (cdn.jsdelivr.net)

### Flame Graph Shows Old Data

**Symptom**: Flame graph reflects historical data, not current activity.

**Solution**: The flame graph auto-resets every 60 seconds. Use the **Reset** button to clear immediately and start fresh. Use the **Pause** button to freeze the current view.

## OTLP Export Issues

### Metrics Not Arriving at Collector

**Symptom**: OTLP enabled but collector receives nothing.

**Checklist**:

1. **Verify OTLP is enabled**
   ```bash
   curl http://localhost:9202/config
   # Should show "otlpEnabled": true
   ```

2. **Check endpoint URL** — must include `/v1/metrics` path
   ```bash
   -Dargus.otlp.endpoint=http://localhost:4318/v1/metrics
   ```

3. **Check collector is running**
   ```bash
   curl http://localhost:4318/v1/metrics
   ```

4. **Check auth headers** if using authenticated endpoint
   ```bash
   -Dargus.otlp.headers=Authorization=Bearer\ mytoken
   ```

## CLI Diagnostic Errors

### `❌ NMT not enabled on PID X.`

**Symptom**: Running `argus nmt`, `argus nmt --save`, `argus nmt --diff`, or `argus nmt --watch` against a live PID prints:

```
❌ NMT not enabled on PID 12345.
   Add -XX:NativeMemoryTracking=summary (or =detail) to the target JVM and restart.
```

Exit code: 1.

**Cause**: The target JVM was started without the `-XX:NativeMemoryTracking` flag. NMT cannot be enabled at runtime — the flag must be present at JVM startup.

**Fix**: Restart the target JVM with the flag:

```bash
java -XX:NativeMemoryTracking=summary -jar your-app.jar
# or, for full category breakdown:
java -XX:NativeMemoryTracking=detail -jar your-app.jar
```

Then re-run the `argus nmt` command against the new PID.

---

### `Event 'X' not supported by bundled async-profiler v4.4 on darwin-arm64.`

**Symptom**: Running `argus profile --event=<name>` on macOS Apple Silicon prints:

```
Event 'cycles' not supported by bundled async-profiler v4.4 on darwin-arm64.
Supported: cpu, alloc, lock, wall, nativemem, nativelock
```

**Cause**: The requested event is a PMU hardware counter. On macOS arm64 (Apple Silicon), async-profiler 4.4 cannot reach hardware counters via the perf backend. PMU events require Linux.

**Fix**: Use a supported event, or check what is available on your host:

```bash
argus profile --capabilities
```

Supported events on darwin-arm64: `cpu`, `alloc`, `lock`, `wall`, `nativemem`, `nativelock`.

---

### `async-profiler collected no samples (event=lock)`

**Symptom**: `argus profile` completes without error but reports:

```
async-profiler collected no samples (event=lock)
```

**Cause**: The profiling window ran to completion but captured zero events. Common reasons:
- The workload is CPU-bound, so there are no lock-contention events to sample.
- The `--duration` was too short for the event rate.
- The target JVM was idle during the window.

**Fix**:

```bash
# Lengthen the capture window
argus profile <pid> --event=lock --duration=30

# Or switch to a more appropriate event for the workload
argus profile <pid> --event=cpu --duration=10
```

---

### `gcscore expects a live PID or a path to a GC log file. Got: <arg>`

**Symptom**: Running `argus gcscore <arg>` prints:

```
gcscore expects a live PID or a path to a GC log file. Got: myapp
```

**Cause**: The argument is not a numeric PID and is not a path to an existing file.

**Fix**: Pass either a live PID or the path to a GC log file:

```bash
# Live PID
argus gcscore 12345

# GC log file
argus gcscore /var/log/app/gc.log
```

To find running JVM PIDs:

```bash
argus ps
```

---

### `com.sun.tools.attach.AttachNotSupportedException: state is not ready`

**Symptom**: Commands that attach to a live JVM (e.g. `argus threads`, `argus mbean`, `argus spring`) fail immediately after the target JVM starts:

```
com.sun.tools.attach.AttachNotSupportedException: state is not ready
```

**Cause**: The JVM attach socket is not yet bound. The OS-level UNIX socket that `VirtualMachine.attach()` uses is created asynchronously during JVM startup. Connecting within the first second or two of a new process races against this initialization.

**Fix**: Wait 2–5 seconds after the target JVM starts, then retry:

```bash
# Wait a moment, then attach
sleep 3 && argus threads <pid>
```

---

### `File not found: <PID>` (older builds)

**Symptom**: On a Argus build older than the gcscore/gcwhy live-PID fixes, passing a numeric PID to `argus gcscore` or `argus gcwhy` prints:

```
File not found: 12345
```

instead of attaching to the live JVM.

**Cause**: The build predates the live-PID path in `GcScoreCommand` and `GcWhyCommand`. Those older builds treat every argument as a file path.

**Fix**: Rebuild from master and reinstall:

```bash
./gradlew :argus-cli:fatJar
cp argus-cli/build/libs/argus-cli-*-all.jar ~/.argus/argus-cli.jar
```

Verify the installed version:

```bash
argus --version
```

---

## ZGC Pathologies

Common ZGC-specific symptoms surfaced by `argus zgc`, `argus doctor`, and `argus gclog`.

### "argus zgc reports allocation stalls"

**Symptom**: `argus zgc` shows one or more entries under **Allocation Stalls** and emits verdict UNHEALTHY.

**Cause**: Mutator (application) threads were blocked waiting for ZGC to free memory pages. This means ZGC could not reclaim memory fast enough to satisfy the allocation rate. Left unresolved, stalls escalate to OutOfMemoryError.

**Actions**:

1. Raise `-Xmx` — give ZGC more headroom (suggested value printed in the Recommend section).
2. Raise `-XX:ConcGCThreads` — more concurrent GC threads reclaim pages faster.
3. Profile allocations to find the hot allocation sites:
   ```bash
   argus profile <PID> --event=alloc --duration=30
   ```
4. Check whether `-XX:SoftMaxHeapSize` is set too low — if committed heap is already near the soft max, raise it toward `-Xmx`.

---

### "committed heap exceeds SoftMaxHeapSize"

**Symptom**: `argus doctor <PID>` fires a WARNING finding `ZGC heap committed exceeds SoftMaxHeapSize`, or `argus zgc` shows `✘` on the SoftMax line.

**Cause**: ZGC overrode its own soft ceiling under allocation pressure. `SoftMaxHeapSize` is a hint, not a hard limit — when the allocation rate bursts, the JVM allows committed heap to exceed it temporarily. Sustained breaches indicate the application's steady-state heap is too large for the configured soft limit.

**Actions**:

1. Raise `-XX:SoftMaxHeapSize` toward `-Xmx` (e.g. 80–90% of `-Xmx`).
2. If `-Xmx` itself is the bottleneck, increase it.
3. Reduce allocation rate — profile with `argus profile <PID> --event=alloc`.

---

### "ZGC cycle overlap detected"

**Symptom**: `argus zgc` shows `✘ consecutive cycles overlap` and/or `argus doctor` fires `ZgcCycleOverlapRule` CRITICAL.

**Cause**: A new ZGC cycle started before the previous one finished. ZGC is designed to be fully concurrent — when cycles overlap, the GC cannot process garbage fast enough to keep ahead of the mutator. This commonly precedes allocation stalls and OOM.

**Actions**:

1. Raise `-Xmx` — the most direct fix; a larger heap means longer intervals between cycles.
2. Raise `-XX:ConcGCThreads` — more threads shorten cycle duration.
3. Use `argus profile <PID> --event=alloc` to identify and reduce top allocation sites.

---

### "Allocation stalls without obvious code change"

**Symptom**: `argus zgc` shows allocation stalls (UNHEALTHY verdict) but no recent change in application code is apparent. You want to identify which code is causing the pressure.

**Cause**: The stall itself means ZGC could not reclaim pages fast enough to satisfy the allocation rate. Identifying the hot allocation site is the first step to resolution.

**Actions**:

`argus zgc` now surfaces the top allocation call sites directly in the output. When stalls are detected and `jdk.ObjectAllocationInNewTLAB` / `jdk.ObjectAllocationOutsideTLAB` events are present in the same JFR capture (enabled automatically by `profile.jfc`), a **Top alloc sources during capture** block appears under the Allocation Stalls section:

```
  Allocation Stalls
               ✘ 7 stalls in window (max 84.3ms in "http-nio-8080-exec-3")
               Top alloc sources during capture (n=84,321 events)
                1. com.example.service.OrderService.processOrder(OrderService.java:142)  38.2%
                2. java.util.HashMap.resize(HashMap.java:704)                            14.7%
                ...
```

Interpret the table:

- **Own code at the top** (e.g. `com.example.*`) → the method is allocating excessively. Consider object pooling, caching, or reducing intermediate object creation.
- **Library internals at the top** (e.g. `HashMap.resize`) → collections are growing past initial capacity. Pre-size them: `new HashMap<>(expectedSize * 2)`.
- **No table printed** → `profile.jfc` allocation events were not recorded in this JVM build or version; fall back to `argus profile <PID> --event=alloc --duration=30`.

---

### "How do I track ZGC health over a deploy"

**Symptom**: You want to know whether a new deploy made ZGC behaviour better or worse, not just whether it is healthy right now.

**Actions**:

1. Before the deploy, capture a baseline from a known-good window:
   ```bash
   argus zgc <PID> --save=/tmp/before.txt
   ```

2. After the deploy (allow the JVM a minute to warm up), diff against the baseline:
   ```bash
   argus zgc <PID> --diff=/tmp/before.txt
   ```

3. Read the diff table. REGRESSION rows (marked ✘) are the most actionable:
   - **New stalls** (baseline=0 → current>0) — allocation pressure introduced by the new code.
   - **SoftMax breach** (no → yes) — heap working set grew; raise `-XX:SoftMaxHeapSize` or investigate new leaks.
   - **Minor:major ratio worsened >50%** — generational balance degraded.
   - **Pause Mark End grew >50%** — marking load increased, often from more live objects.

   WARN rows (⚠) indicate degradation worth watching but not yet critical.

4. For continuous monitoring during a rolling deploy, use watch mode:
   ```bash
   argus zgc <PID> --watch=10 --interval=60
   ```
   This runs 10 iterations at 60-second intervals. Each line shows heap delta and stall count relative to the previous iteration. Ctrl-C at any point prints a final summary and cleans up the JFR recording.

---

### "argus zgc PID returns 'not using ZGC'"

**Symptom**: `argus zgc <PID>` prints:
```
Target JVM (PID 12345) is not using ZGC. Current GC: G1 Old Generation, G1 Young Generation.
Use 'argus gc 12345' instead.
```

**Cause**: The target JVM is running a different GC collector. `argus zgc` checks MBean names via JMX before starting the JFR capture and exits early when no ZGC MBean is found.

**Actions**:

1. Confirm the active collector:
   ```bash
   argus gc <PID>
   ```
2. To switch to ZGC, restart the target JVM with:
   ```bash
   # JDK 15+ (non-generational ZGC)
   java -XX:+UseZGC ...

   # JDK 21–23 (Generational ZGC — recommended)
   java -XX:+UseZGC -XX:+ZGenerational ...

   # JDK 24+ (Generational ZGC is default)
   java -XX:+UseZGC ...
   ```

---

## Getting Help

If you're still experiencing issues:

1. **Search existing issues**: [GitHub Issues](https://github.com/rlaope/argus/issues)

2. **Create a new issue** with:
   - Java version (`java -version`)
   - OS and version
   - Argus version
   - Steps to reproduce
   - Full error message/stack trace

3. **Contact maintainer**: [@rlaope](https://github.com/rlaope)

## Debug Mode

Enable verbose logging for debugging:

```bash
java -javaagent:argus-agent.jar \
     -Djava.util.logging.config.file=logging.properties \
     -jar app.jar
```

**logging.properties**:
```properties
handlers=java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level=ALL
io.argus.level=FINE
```
