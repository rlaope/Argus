# Changelog

All notable changes to Argus are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project tracks [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.1] - 2026-05-08

### Fixed
- `docker.yml` `build-agent` job builds the agent JAR with Gradle before invoking `docker buildx`. The previous workflow assumed a pre-built JAR existed at `argus-agent/build/libs/`, so v1.1.0 and v1.2.0 silently shipped without `ghcr.io/<owner>/argus-agent:<ver>`. Re-running this version's docker workflow now successfully publishes both `argus` and `argus-agent` images.
- i18n parity restored across all four locale files. Korean, Japanese, and Chinese were missing one to four keys (`cmd.gcprofile.desc`, `cmd.gclog.tenuring.desc`, `gcnew.age.title`, `gcnew.age.unavailable`); they are now all at 529 keys with identical sets.
- `install.sh` and `install.ps1` fallback versions (`v1.1.0`, `v0.4.0`) corrected. If the GitHub releases API was unreachable, users were silently downgraded; now they get `v1.2.1`.
- `site/index.html` Spring Boot Integration snippet pinned to `argus-spring-boot-starter:1.2.1` (was stuck at `0.8.0`).
- README, docs/cli-commands, docs/README, docs/getting-started, and site/index.html now uniformly say "66 commands" (matches actual source count). Previously claimed `65+`, `55`, or `50` in different places.

### Changed
- Netty `4.1.104.Final` → `4.1.115.Final` (CVE-2024-47535 patched).
- JUnit Jupiter `5.10.1` → `5.11.4`.
- Helm `Chart.yaml` adds `kubeVersion: ">=1.23.0-0"` so the chart fails fast on unsupported clusters.
- `deploy/docker-compose.yml` pins `prom/prometheus:v2.55.0` and `grafana/grafana:11.3.0` (was `:latest`, breaking reproducibility).
- `SECURITY.md` supported-versions table replaces stale `0.1.x` row with `1.2.x` / `1.1.x` supported and `<1.1` EOL.

### Added
- `.claude/skills/docs-sync` and `.claude/skills/release-sync` — project-scoped Claude Code skills that codify the pre-release audit. Future releases run `/docs-sync && /release-sync` to detect version drift, command-count drift, i18n parity gaps, floating image tags, deprecated K8s APIs, and outdated dependencies — and to verify post-release that the GitHub Release assets and GHCR images actually exist for the new tag.

## [1.2.0] - 2026-05-07

### Added
- `argus zgc` — ZGC live diagnosis with 30s JFR capture and HEALTHY/WARNING/UNHEALTHY verdict. Detects allocation stalls, cycle overlap, SoftMax breach, and supports Generational ZGC. `--save`, `--diff`, and `--watch` for trend tracking and baseline comparison.
- ZGC-aware tuning across `argus doctor`, `argus gcscore`, and `argus gclog` — collector-specific weights, ZGC SoftMax breach rule, ZGC cycle overlap rule, allocation pressure axis.
- ZGC trend tracking + allocation hotspot cross-reference between JFR ZGC events and method profiling.

### Fixed
- Seven live-JVM diagnostic gaps surfaced by an internal usability study (correctness + presentation across providers and rules).

## [1.1.0] - 2026-04-25

### Added
- `argus profile-gate` — CI/CD profile gate with exit codes for hot-method threshold, allocation budget, and contention budget.
- `argus flame` — one-shot flame graph (profile + HTML + browser open) with ASCII renderer for headless terminals.
- async-profiler 4.4 session model with `--save` / `--diff` for continuous profiling and multi-PID support.
- `argus gcscore` — A–F GC Health Score Card with six KPI axes and improvement hints.
- `argus gcwhy` — narrate why the worst recent GC pause happened, with live JFR capture.
- `argus gcprofile` — GC-aware allocation profiling with `--by=class` / `--fold` aggregation.
- `argus classleak` — class loader leak detection with snapshot/diff.
- `argus trace` — method execution tracing with timing.
- `argus alert` — metric monitoring with webhook notifications.
- `argus spring`, `argus benchmark` — Spring Boot introspection and microbenchmark commands.
- `argus nmt --save` / `--diff` — native memory baseline tracking.
- Doctor `MaxPauseRule` for individual STW pause detection.

### Fixed
- GC log parser, doctor snapshot, and provider correctness across G1, ZGC, Shenandoah, Parallel, and Serial.

## [1.0.0] - 2026-04-08

First major release — production-ready JVM diagnostic CLI.

### Added
- `argus tui` — k9s-style interactive terminal UI with JLine3.
- `argus ci` — CI/CD health gate with exit codes.
- `argus compare` — side-by-side JVM diff.
- `argus slowlog` — real-time slow method detection via JFR.
- Dockerfile + GitHub Actions for ghcr.io image publishing.
- `argus-action` — GitHub Action for CI/CD JVM health checks.

### Fixed
- Critical UX issues across `argus watch` (terminal shutdown hook, Windows safety), `argus suggest` (classpath-based framework detection), and `GcLogParser` (streaming + ZGC/Shenandoah/decorated timestamps).

---

[Unreleased]: https://github.com/rlaope/Argus/compare/v1.2.1...HEAD
[1.2.1]: https://github.com/rlaope/Argus/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/rlaope/Argus/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/rlaope/Argus/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/rlaope/Argus/releases/tag/v1.0.0
