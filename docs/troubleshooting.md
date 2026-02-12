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

2. **Missing `--enable-preview` flag**
   ```bash
   # Required for Java 21 preview features
   java -javaagent:argus-agent.jar --enable-preview -jar app.jar
   ```

3. **Java version too old**
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
        --enable-preview \
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

**Symptom**: Preview feature errors.

**Solution**: Ensure `--enable-preview` is set in build.gradle.kts:
```kotlin
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
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
     --enable-preview \
     -jar app.jar
```

**logging.properties**:
```properties
handlers=java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level=ALL
io.argus.level=FINE
```
