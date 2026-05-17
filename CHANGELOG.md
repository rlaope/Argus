# Changelog

All notable changes to Argus are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project tracks [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **`argus-diagnostics` module** — framework-agnostic JVM analysis library extracted from `argus-cli`. Contains the doctor engine (13 health rules), GC log parser + analyzer, GC scoring, ZGC diagnosis, and the jcmd-based snapshot collector. Java 11 bytecode, depends only on `argus-core`. Non-Spring JVM apps (Quarkus, Micronaut, IDE plugins, plain JARs) can now embed the same analysis primitives the CLI uses. CLI behavior unchanged.
- **`argus-spring-boot-starter`: `argus.mode=full|diagnostics|off` posture knob** — new property letting production apps opt into a lightweight analysis-only path without spinning up the JFR streaming engine or the embedded `ArgusServer:9202`. `mode=full` (default) preserves v1.4.0 behavior; `mode=diagnostics` keeps the analysis beans + actuator endpoints; `mode=off` is equivalent to `argus.enabled=false`.
- **`argus-spring-boot-starter`: programmatic API beans** — `DoctorService`, `GcLogAnalyzerService`, `GcScoreService` are now `@Autowired`-injectable. All three are `@ConditionalOnMissingBean` so applications can override with custom implementations.
- **`argus-spring-boot-starter`: actuator endpoints** — `/actuator/argus-doctor` (local + `/{pid}` remote variants) and `/actuator/argus-gc` (driven by `argus.doctor.gc-log-path`). Responses are explicit `Map<String,Object>` shapes for stable JSON contracts across Jackson versions. Opt-in via the standard `management.endpoints.web.exposure.include` gate.
- **`argus-spring-boot-starter`: scheduled doctor + structured slf4j logging** — opt-in via `argus.doctor.schedule.enabled=true`. Background `@Scheduled` bean runs `DoctorService.diagnoseLocal()` on a fixed interval (default 60 s) and emits one structured log line per finding (severity → log level mapping: CRITICAL → ERROR, WARNING → WARN, INFO → INFO). Format `key=value` is parseable by Loki / Datadog / Vector / Logstash without bespoke per-field regex.

### Changed
- `argus-spring-boot-starter` now `api`-depends on `argus-diagnostics`, exposing `Finding`, `JvmSnapshot`, `GcLogAnalysis`, `GcScoreResult` etc. to consumer apps via the transitive classpath.
- Documentation: `docs/usage.md`, `docs/kubernetes.md`, and `site/integrations.html` updated with the new posture knob, programmatic API, actuator endpoints, and a "diagnostics-only" K8s recipe (Method C.1).

## [1.4.0] - 2026-05-13

### Added
- `argus harness <pid>` — continuous JVM monitoring + optimization + troubleshooting. Samples on a fixed interval, runs the existing 12 doctor health rules plus four new trend rules (heap-leak regression, GC overhead trend, thread growth, GC pause regression) and produces a single severity-ranked session report with JVM-flag suggestions. Observation-only — no JVM mutation. Supports `--profile=quick|deep`, `--interval=<dur>`, `--duration=<dur>`, `--out=<file>`, and `--format=json`. Dedupes findings by category+title across the session and reports per-rule hit counts so a leak rule that fires 600 times shows up once with "(fired 600 ticks)".
- `.claude-plugin/marketplace.json` + `.claude-plugin/skills/argus-jvm-harness/SKILL.md` — Argus is now installable as a Claude Code plugin via `/plugin marketplace add https://github.com/rlaope/Argus` then `/plugin install argus-jvm-harness`. The skill wraps the CLI; if `argus` isn't on PATH it bootstraps via `install.sh` first.
- `docs/harness.md` — full reference for the harness, trend rules, output formats, and recommended usage patterns.
- **`argus profile` — 10 new async-profiler passthrough flags**: `--ttsp` (time-to-safepoint profiling), `--begin=<func>` / `--end=<func>` (profile boundary triggers), `--reverse` (call-tree direction), `--minwidth=<pct>` (collapse narrow frames), `--sched` (Linux scheduler events), `--clock=tsc|monotonic` (closed-enum validated), `--signal=<n>`, `--proc=<duration>` (per-process sampling interval), `--nofree` (suppress free events). `--cstack`, `--interval`, and `--clock` all parse-validate at the CLI boundary; `--sched` is Linux-gated and `--nofree` warns when the event isn't `nativemem`.
- **`argus doctor` — CodeCache pressure rule + DirectBuffer rule that works on JDK 16+**: `CodeCacheRule` flags WARNING at ≥80% used and CRITICAL at ≥95%, recommending `-XX:ReservedCodeCacheSize=<2×current>` (256m floor) and re-enabling `+UseCodeCacheFlushing` only when the user has disabled it. `DirectBufferRule` reads the JVM-reported buffer pools first, then consults NMT `Other` / `Internal` category committed bytes when buffer pools are empty (the case on JDK 16+ jcmd-only path). Finding text says "via NMT" so operators know the source. `JvmSnapshot` now carries `codeCacheUsedKb` / `codeCacheSizeKb` / `nmtCommittedKbByCategory`, populated once per doctor run via `JdkCompilerProvider` and `JdkNmtProvider`.
- **`argus compiler` — runtime deoptimization count**: reads `sun.ci.totalInvalidates` from `jcmd PerfCounter.print` and renders `Deoptimizations: N` under the existing code-cache block. JSON output gains `"deoptCount": N`. Stable across HotSpot JDK 8+.
- `install.sh` / `install.ps1` `--run` flag — `install.sh ... --run <subcommand>` (and `install.ps1 ... -Run <subcommand>`) installs and immediately execs into the freshly-installed `argus` in one shot.

### Changed
- All GitHub Actions in `.github/workflows/*.yml` are now pinned by commit SHA (`uses: <owner>/<repo>@<sha> # <tag>`) for supply-chain hardening.
- `setup-graalvm` pinned to `1.4.5` and Dependabot configured to ignore major/minor bumps for this action — `1.5.x` removed the `distribution: graalvm-community` input that `native-image.yml` depends on, so unattended bumps would break native-image builds. Patch bumps remain eligible. Lift only after `native-image.yml` is migrated to the new input shape and verified end-to-end on a tag.
- `argus-cli` commands now self-register via `ServiceLoader` rather than a hand-maintained list. Adding a new command is one file plus a `META-INF/services` line; the CLI dispatcher does not need to be touched.
- `JfrCaptureSession` consolidates the JFR start → stream → stop loop that was previously duplicated across `flame`, `gcprofile`, `profile`, and `slowlog`.

### Fixed
- `argus top` wiring: `ArgusTop` (a dead alternate command class) removed; `TopCommand` is now the single entry point for the `top` subcommand.

## [1.3.0] - 2026-05-08

### Added
- **Java 11 / 17 / 21 multi-runtime support** — the CLI now genuinely runs on Java 11+ as advertised. `argus-core` and `argus-cli` compile to Java 11 bytecode; `argus-agent`, `argus-server`, `argus-micrometer`, and `argus-spring-boot-starter` compile to Java 17 (Spring Boot 3 baseline). One command (`argus slowlog`) gates on `Runtime.version().feature() < 14` because it uses `jdk.jfr.consumer.RecordingStream`. Until 1.2.1 the CLI silently required Java 21, throwing `UnsupportedClassVersionError` on Java 11/17.
- CI `runtime-compat` matrix that builds the fat JAR on JDK 21 and smoke-tests it on JDK 11, 17, and 21 — so future regressions where Java-12+ APIs sneak in will be caught in CI rather than by users.
- Per-subcommand `--help`. `argus <cmd> --help` now prints the command's description and a Usage hint; previously the parser misread `--help` as a positional `<pid>` and errored out.
- `install.sh` and `install.ps1` SHA-256 verification. After downloading each JAR the installer fetches `checksums.txt` from the same release, looks up the entry by original filename, and aborts on mismatch. Releases that don't publish `checksums.txt` (anything before this version) get a clear "skipping integrity check" warning so the legacy path still works.
- `argus-spring-boot-starter` real Spring-context integration tests via `ApplicationContextRunner` — covers `@ConfigurationProperties` binding, the `argus.enabled=false` short-circuit, and the `matchIfMissing=true` default activation. Adds three tests alongside the existing two pure unit tests.
- `release.yml` writes `checksums.txt` next to the JARs and dispatches `native-image.yml` after the GitHub Release is created (the `release: published` event does not fire for releases created via `GITHUB_TOKEN`, which is why native-image runs were skipped on v1.1.0/v1.2.0/v1.2.1 unless dispatched manually).

### Fixed
- `argus info <pid>` no longer shows the literal "<pid>:" string for "VM Name". The bug came from `JdkInfoProvider` taking the first non-empty line of `jcmd VM.version` output, which is always `<pid>:`.
- `argus info <bad-pid>` now exits 1 with `PID <n> not found. Run \`argus ps\` to list running JVMs.` instead of silently rendering an empty info card and exiting 0. CI scripts can now detect the failure.
- All Java 19/21 APIs that had leaked into the Java-17-baseline modules: `Thread.ofPlatform()`, `Thread.threadId()`, `Thread.isVirtual()`, `RecordedThread.isVirtual()`, `List.removeFirst()`, `List.getFirst()` — replaced with traditional equivalents or, for virtual-thread checks, reflective helpers that return the real value on Java 21+ runtimes and `false` on 17.

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

[Unreleased]: https://github.com/rlaope/Argus/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/rlaope/Argus/compare/v1.2.1...v1.3.0
[1.2.1]: https://github.com/rlaope/Argus/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/rlaope/Argus/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/rlaope/Argus/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/rlaope/Argus/releases/tag/v1.0.0
