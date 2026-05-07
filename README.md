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

> **One CLI for all JVM diagnostics.** 65+ commands, zero agent required, works on Java 11+.
> The free alternative to GCEasy + jcmd + VisualVM combined — GC analysis, health diagnosis, flame graphs, async-profiler integration, ZGC live monitoring, and CI/CD profile gates.

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

## Why Argus?

- **65+ diagnostic commands** — heap, GC, threads, profiling, flame graphs, NMT, class loaders, and more. No agent required.
- **Live JVM attach** — attaches externally via `jcmd`/JMX; target JVM needs no restart and no `-javaagent` flag.
- **ZGC-aware** — `argus zgc` gives a HEALTHY/WARNING/UNHEALTHY verdict with allocation stall detection, cycle-overlap analysis, SoftMax breach detection, and diff-against-baseline in one command.
- **Virtual thread support** — JFR-based pinning detection, carrier-thread distribution, and virtual thread monitoring on Java 21+.

> Full command reference: [docs/cli-commands.md](docs/cli-commands.md)

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
