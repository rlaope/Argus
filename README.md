<p align="center">
  <img src="assets/argus_logo.png" alt="Argus Logo" width="240">
</p>

<h1 align="center">Argus</h1>

<p align="center">
  <a href="https://github.com/rlaope/Argus/actions/workflows/ci.yml"><img src="https://github.com/rlaope/Argus/actions/workflows/ci.yml/badge.svg" alt="Build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/rlaope/Argus" alt="License"></a>
  <a href="https://openjdk.org"><img src="https://img.shields.io/badge/Java-11%2B-blue" alt="Java"></a>
  <a href="https://github.com/rlaope/Argus/stargazers"><img src="https://img.shields.io/github/stars/rlaope/Argus" alt="GitHub stars"></a>
</p>

> **One CLI for all JVM diagnostics.** 71 commands, zero agent required, works on Java 11+.
> The free alternative to GCEasy + jcmd + VisualVM + Arthas + Eclipse MAT combined — GC analysis, health diagnosis, flame graphs, async-profiler integration, MAT-class heap leak analysis, continuous profiling, distributed-tracing correlation, opt-in live method instrumentation, ZGC live monitoring, and CI/CD profile gates.

---

## Quick Start

```bash
# Install (macOS / Linux)
curl -fsSL https://raw.githubusercontent.com/rlaope/argus/master/install.sh | bash

# Windows (PowerShell)
irm https://raw.githubusercontent.com/rlaope/argus/master/install.ps1 | iex
```

```bash
argus doctor <pid>              # One-click JVM health diagnosis
argus zgc <pid>                 # ZGC live diagnosis (30s JFR capture + verdict)
argus profile <pid> --duration=30  # CPU/allocation profiling via async-profiler
```

Shell completions (bash, zsh, fish, PowerShell) are installed automatically.

---

## JVM Harness

Continuous JVM monitoring + optimization + troubleshooting on top of Argus, available as a Claude Code plugin.

```
/plugin marketplace add https://github.com/rlaope/Argus
/plugin install argus-jvm-harness
```

CLI equivalent (no Claude Code required):

```bash
argus harness <pid>                          # 30-min trend-aware health watch
argus harness <pid> --profile=quick --duration=1m
argus harness <pid> --out=session.json --format=json
```

The harness samples on a fixed interval, runs the doctor rule set plus four trend rules (heap-leak regression, GC overhead trend, thread growth, pause-time regression), and produces a single session report with severity-ranked findings and JVM-flag suggestions.

Full reference: [docs/harness.md](docs/harness.md)

---

## Why Argus?

One CLI to diagnose a live JVM and watch a fleet — no agent, no restart, no `-javaagent`. It attaches externally over `jcmd`/JMX and reads the JVM right where it runs.

- **Zero-install live attach** — point it at a PID; the target needs no restart and no agent flag.
- **One-command health verdict** — `argus doctor` returns HEALTHY / WARN / CRITICAL with machine-readable exit codes for CI gates.
- **MAT-class heap analysis, offline** — dominator tree, retained sizes, and automated leak suspects on multi-GB dumps within a bounded heap.
- **Continuous profiling, no TSDB** — disk-backed per-pod CPU/alloc flamegraphs with time-window merges and differential views.
- **GC ↔ trace correlation** — see which traces a GC pause stalled, exported as OTel spans and Prometheus exemplars.
- **Opt-in live instrumentation** — watch/trace/monitor method args, returns, and timing via dynamic attach; default OFF, auto-detach, zero residual bytecode.
- **JVM right-sizing for FinOps** — `argus rightsize` recommends `-Xmx` and container memory from observed heap high-water-mark and GC headroom.
- **GC-aware verdicts & Loom** — `argus g1` / `argus zgc` flag allocation stalls, cycle overlap, and SoftMax breaches; virtual-thread pinning detection on Java 21+.
- **OpenTelemetry-native** — OpenMetrics `/prometheus`, OTLP export, and a shipped Grafana dashboard.

> 71 commands in total — full reference: [docs/cli-commands.md](docs/cli-commands.md)

---

## Roadmap At A Glance

<p align="center">
  <img src="assets/argus-roadmap.png" alt="Argus roadmap showing CLI and TUI, APM Core, Micrometer, Observability, Profiling, Heap and GC, Fleet and Kubernetes, and Automation lanes">
</p>

Argus keeps the fast single-PID JVM workflow at the center, then layers standard metrics, traces, profiles, dashboards, fleet views, and APM-grade service workflows around it.

---

## Example: `argus doctor <pid>`

```
$ argus doctor 39113

╭─ Health Diagnosis ── pid:39113 ─────────────────────────────────────────────╮
│                                                                              │
│  ✅  Heap usage normal       41M / 256M  (16%)                               │
│  ✅  GC overhead acceptable  0.3%                                            │
│  ✅  No deadlocks detected                                                   │
│  ✅  Thread count normal     29 threads                                      │
│  ⚠   Metaspace near limit   96.5% — consider -XX:MaxMetaspaceSize           │
│  ⚠   Old Gen elevated        41.8% — monitor for promotion pressure         │
│                                                                              │
│  Overall: WARN — 2 recommendations                                           │
│                                                                              │
│  Tuning Tips                                                                 │
│  → Add -XX:MaxMetaspaceSize=256m to prevent unlimited growth                 │
│  → Run argus gclog <file> to analyze GC pressure over time                  │
│                                                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Exit codes are machine-readable: `0` = healthy, `1` = warnings, `2` = critical.

---

## Where to Learn More

| Topic | Doc |
|-------|-----|
| Installation, prerequisites, Java version compatibility | [docs/getting-started.md](docs/getting-started.md) |
| All commands with output examples | [docs/cli-commands.md](docs/cli-commands.md) |
| Usage walkthroughs (agent, Spring Boot, ZGC, CI/CD, monitoring stack) | [docs/usage.md](docs/usage.md) |
| Agent properties, flag tables, env vars | [docs/configuration.md](docs/configuration.md) |
| Module layout, SPI patterns, architecture diagrams | [docs/architecture.md](docs/architecture.md) |
| Build, commit, i18n, release procedures | [docs/contributing.md](docs/contributing.md) |
| Common errors and fixes | [docs/troubleshooting.md](docs/troubleshooting.md) |

---

## Building from Source

```bash
./gradlew :argus-cli:fatJar
```

See [docs/contributing.md](docs/contributing.md) for the full build, test, and release workflow.

---

## License & Contributing

MIT License — see [LICENSE](LICENSE).

Contributions welcome — bugs, features, docs, tests. See [docs/contributing.md](docs/contributing.md).

Maintainer: [@rlaope](https://github.com/rlaope)
