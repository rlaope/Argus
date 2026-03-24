# Argus Documentation

Welcome to the Project Argus documentation.

## Table of Contents

1. [Getting Started](getting-started.md) - Installation and quick start guide
2. [CLI Command Reference](cli-commands.md) - All 17 commands with usage and output examples
3. [Configuration](configuration.md) - Configuration options and tuning
4. [Architecture](architecture.md) - System architecture and design
5. [Troubleshooting](troubleshooting.md) - Common issues and solutions
6. [Benchmark Report](benchmark-report.md) - Performance benchmarks

## What is Argus?

Argus is a lightweight, zero-dependency JVM monitoring tool for Java 21+. It provides:

- **Real-time Dashboard** with interactive charts and flame graphs
- **Unified CLI** — 15 commands (`ps`, `histo`, `threads`, `gc`, `gcutil`, `heap`, `sysprops`, `vmflag`, `nmt`, `classloader`, `jfr`, `info`, `top`) with auto source detection
- **No Agent Required** — diagnose any running JVM via `jcmd` (agent optional for richer data)
- **Multi-language** — English, Korean, Japanese, Chinese
- **Prometheus Endpoint** for metric scraping
- **OTLP Export** for pushing metrics to OpenTelemetry collectors
- **Virtual Thread Monitoring** with pinning detection
- **CPU & Memory Profiling** via JDK Flight Recorder

## Quick Links

- [GitHub Repository](https://github.com/rlaope/argus)
- [Releases](https://github.com/rlaope/argus/releases)
- [Issue Tracker](https://github.com/rlaope/argus/issues)
