# Configuration

This guide covers all configuration options available in Project Argus.

## Agent Configuration

The Argus agent is configured via Java system properties.

### Available Properties

| Property | Default | Description |
|----------|---------|-------------|
| `argus.server.port` | `9202` | WebSocket server port |
| `argus.buffer.size` | `65536` | Ring buffer size for event collection |

### Setting Properties

```bash
java -javaagent:argus-agent.jar \
     -Dargus.server.port=9090 \
     -Dargus.buffer.size=131072 \
     --enable-preview \
     -jar your-application.jar
```

## Buffer Size Tuning

The ring buffer size affects memory usage and event handling capacity.

### Guidelines

| Application Type | Recommended Size | Memory Usage |
|------------------|------------------|--------------|
| Development/Testing | `16384` | ~1 MB |
| Standard Production | `65536` (default) | ~4 MB |
| High-throughput | `131072` | ~8 MB |
| Extreme throughput | `262144` | ~16 MB |

### Considerations

- Buffer size must be a power of 2
- Larger buffers reduce the chance of event loss under high load
- Smaller buffers use less memory

```bash
# High-throughput configuration
java -javaagent:argus-agent.jar \
     -Dargus.buffer.size=262144 \
     --enable-preview \
     -jar high-traffic-app.jar
```

## JFR Event Configuration

Argus captures the following JFR events by default:

| Event | Description | Overhead |
|-------|-------------|----------|
| `jdk.VirtualThreadStart` | Thread creation | Low |
| `jdk.VirtualThreadEnd` | Thread termination | Low |
| `jdk.VirtualThreadPinned` | Thread pinning (with stack trace) | Medium |
| `jdk.VirtualThreadSubmitFailed` | Submit failures | Low |

### JFR Settings

The agent configures JFR with:

- **Max Age**: 10 seconds (sliding window)
- **Max Size**: 10 MB

## Server Configuration

### Port Configuration

```bash
# Start server on custom port
java -Dargus.server.port=9090 -jar argus-server.jar
```

### Production Deployment

For production environments, consider:

1. **Bind to localhost only** (for security):
   ```bash
   # Use a reverse proxy (nginx, etc.) for external access
   ```

2. **Enable TLS** via reverse proxy:
   ```nginx
   # nginx configuration example
   server {
       listen 443 ssl;
       server_name argus.example.com;

       ssl_certificate /path/to/cert.pem;
       ssl_certificate_key /path/to/key.pem;

       location /events {
           proxy_pass http://localhost:9202;
           proxy_http_version 1.1;
           proxy_set_header Upgrade $http_upgrade;
           proxy_set_header Connection "upgrade";
       }

       location /health {
           proxy_pass http://localhost:9202;
       }
   }
   ```

## Environment Variables

You can also use environment variables (converted to system properties):

```bash
export ARGUS_SERVER_PORT=9090
export ARGUS_BUFFER_SIZE=131072

java -javaagent:argus-agent.jar \
     -Dargus.server.port=${ARGUS_SERVER_PORT} \
     -Dargus.buffer.size=${ARGUS_BUFFER_SIZE} \
     --enable-preview \
     -jar your-application.jar
```

## Docker Configuration

### Dockerfile Example

```dockerfile
FROM eclipse-temurin:21-jre

COPY argus-agent.jar /opt/argus/
COPY your-application.jar /app/

ENV ARGUS_PORT=9202
ENV ARGUS_BUFFER_SIZE=65536

EXPOSE ${ARGUS_PORT}

ENTRYPOINT ["java", \
    "-javaagent:/opt/argus/argus-agent.jar", \
    "-Dargus.server.port=${ARGUS_PORT}", \
    "-Dargus.buffer.size=${ARGUS_BUFFER_SIZE}", \
    "--enable-preview", \
    "-jar", "/app/your-application.jar"]
```

### Docker Compose Example

```yaml
version: '3.8'

services:
  app:
    build: .
    ports:
      - "9202:9202"
    environment:
      - ARGUS_PORT=9202
      - ARGUS_BUFFER_SIZE=131072
```

## Kubernetes Configuration

### Deployment Example

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: your-app
spec:
  template:
    spec:
      containers:
        - name: app
          image: your-app:latest
          env:
            - name: JAVA_TOOL_OPTIONS
              value: "-javaagent:/opt/argus/argus-agent.jar --enable-preview"
            - name: ARGUS_SERVER_PORT
              value: "9202"
            - name: ARGUS_BUFFER_SIZE
              value: "65536"
          ports:
            - containerPort: 9202
              name: argus
          livenessProbe:
            httpGet:
              path: /health
              port: argus
            initialDelaySeconds: 10
            periodSeconds: 5
```

## Performance Tuning

### Low Latency Configuration

```bash
java -javaagent:argus-agent.jar \
     -Dargus.buffer.size=32768 \
     -XX:+UseZGC \
     -XX:+ZGenerational \
     --enable-preview \
     -jar latency-sensitive-app.jar
```

### High Throughput Configuration

```bash
java -javaagent:argus-agent.jar \
     -Dargus.buffer.size=262144 \
     -XX:+UseParallelGC \
     --enable-preview \
     -jar high-throughput-app.jar
```

## Next Steps

- [Architecture Overview](architecture.md) - Understand the internal design
- [Troubleshooting](troubleshooting.md) - Common issues and solutions
