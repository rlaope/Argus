# Argus Project Guidelines

## Quick Commands

```bash
./gradlew :argus-cli:fatJar         # build CLI JAR
./gradlew :argus-cli:test           # run tests
cp argus-cli/build/libs/argus-cli-*-all.jar ~/.argus/argus-cli.jar
```

- Java 21 bytecode. CLI diagnoses JVM 11+.

## Where to Look

| If you need to… | Read |
|---|---|
| Add a command, bump a version, or commit | [`docs/contributing.md`](docs/contributing.md) |
| Understand the SPI / Provider / CommandGroup architecture | [`docs/architecture.md`](docs/architecture.md) |
| Find existing CLI commands and their flags | [`docs/cli-commands.md`](docs/cli-commands.md) |
| Tune JVM options / configuration knobs | [`docs/configuration.md`](docs/configuration.md) |
| Fix a runtime error | [`docs/troubleshooting.md`](docs/troubleshooting.md) |

## Hard Rules

- Commit `-s`, prefix `feat:`/`fix:`/`docs:`/`refactor:`/`test:`/`chore:`. No `Co-Authored-By: Claude`.
- i18n strings use printf `%s`, never MessageFormat `{0}`. All 4 locale files must stay in parity.
- Version comes from `gradle.properties` → `argusVersion`. Read at runtime from JAR manifest `Implementation-Version`.
- Branch + PR by default. Direct master commits only when the user explicitly authorizes it for a scope.

Full rationale and procedures: [`docs/contributing.md`](docs/contributing.md).

## Follow-up Queue

(Currently empty. Harness MVP work + the explicit follow-ups the user asked for have all landed. Decisions:)

- **Spring Boot Starter diagnostics layer**: **shipped** — `argus.mode=full|diagnostics|off`, programmatic `DoctorService` / `GcLogAnalyzerService` / `GcScoreService` beans, `/actuator/argus-doctor`, `/actuator/argus-gc`, and a scheduled doctor with structured slf4j logging. The framework-agnostic `argus-diagnostics` module was extracted from `argus-cli` so non-Spring JVM apps can embed it too. See [`docs/usage.md`](docs/usage.md) and [`docs/kubernetes.md`](docs/kubernetes.md) for the public surface; tracking issue #216.
- ~~argus-server REST/WS + frontend Harness panel~~: **dropped.** The harness is intended for Claude Code use only, not for in-production infrastructure self-monitoring. Add it back here only if that intent changes.
- ~~i18n drift backfill~~: **false alarm.** All four locale files have 532 keys (parity); the original 15-line discrepancy is just comments and blank lines.
- ~~Apply forrestchang/andrej-karpathy-skills~~: **done** — vendored to `.claude/skills/karpathy-guidelines/SKILL.md` (MIT, attribution preserved).
- ~~install.sh `--run` flag~~: **done** — `install.sh` and `install.ps1` accept `[--run|-Run] <argus-subcommand> [args…]` and exec into argus after install.
- **JVM-OSS parity roadmap (`.omc/plans/jvm-oss-parity-roadmap.md`)**: **all 4 phases shipped** — P1 #243 (Prometheus/OpenMetrics + continuous-profiling foundation), P2 #244 (MAT-class heap dominators/leak-suspects + profile query/diff/UI), P3 #245 (OTel GC-pause span correlation + anomaly detection), P4 #246 (`argus-instrument` opt-in live instrumentation). The six sub-8 code dimensions were the target.
- **argus-instrument deferred follow-ups** (P4 #246, intentionally not half-built): (1) `argus instrument decompile` — needs a vendored decompiler (CFR/Vineflower), separate dep/licensing call; (2) relocate `net.bytebuddy` via the Shadow plugin so the agent JAR can't clash with a target app that embeds ByteBuddy; (3) publish the agent JAR as a release asset + an `install.sh` flag to drop it at `~/.argus/lib/argus-instrument.jar` (today: `./gradlew :argus-instrument:jar` then copy). The module is opt-in/default-OFF; the CLI has zero compile dep on it.
