# Argus Documentation

Welcome to the Project Argus documentation.

## Table of Contents

1. [Getting Started](getting-started.md) - Installation and quick start guide
2. [Usage Guide](usage.md) - CLI and Agent dashboard usage
3. [CLI Command Reference](cli-commands.md) - All 50 commands with usage and output examples
4. [Configuration](configuration.md) - Configuration options and tuning
5. [Architecture](architecture.md) - System architecture and design
6. [Troubleshooting](troubleshooting.md) - Common issues and solutions
7. [Benchmark Report](benchmark-report.md) - Performance benchmarks

## What is Argus?

Argus is a lightweight, zero-dependency JVM diagnostic toolkit. CLI works on Java 11+, Dashboard on Java 17+, full features on Java 21+. It provides:

- **42 CLI Commands** — process info, memory, GC, threads, profiling, class search, JFR analysis, log level control, and more
- **Real-time Dashboard** with interactive charts, flame graphs, and interactive console
- **No Agent Required** — diagnose any running JVM via `jcmd` (agent optional for richer data)
- **Java Version Adaptive** — MXBean polling on Java 17-20, JFR streaming on Java 21+
- **Multi-language** — English, Korean, Japanese, Chinese
- **Spring Boot Starter** — zero-config auto-configuration for Spring Boot 3.2+
- **Micrometer Integration** — standard metrics bridge for any Micrometer-compatible framework
- **Prometheus Endpoint** for metric scraping
- **OTLP Export** for pushing metrics to OpenTelemetry collectors

## Quick Links

- [GitHub Repository](https://github.com/rlaope/argus)
- [Documentation Site](https://rlaope.github.io/Argus/)
- [Releases](https://github.com/rlaope/argus/releases)
- [Issue Tracker](https://github.com/rlaope/argus/issues)
