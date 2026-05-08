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

Tracked work-units that did not land in the harness MVP and should be picked up in a follow-up PR. The boxes are unchecked so future agents/maintainers can see what's still owed.

- [ ] Apply [forrestchang/andrej-karpathy-skills](https://github.com/forrestchang/andrej-karpathy-skills) — review the skills repo and integrate the applicable behavioral guidelines into Argus' agent / contributor workflow (likely as a project-scoped skill under `.claude/skills/` or a referenced section in `docs/contributing.md`).
- [ ] **Harness — Spring Boot Starter integration.** `argus.harness.enabled=false` opt-in. Auto-config bean wires the `HarnessEngine` to start on `ApplicationReadyEvent` against the in-process PID with `argus.harness.{interval,duration,profile,out}` properties. Health indicator at `/actuator/health/argusHarness` mirrors the existing `ArgusHealthIndicator` pattern.
- [ ] **Harness — server + frontend integration.** `argus-server`: `GET /api/harness/sessions`, `GET /api/harness/sessions/{id}`, `WS /ws/harness` for live finding broadcast; `HarnessSessionStore` keeps an in-memory ring (≤ N sessions). `argus-frontend`: a "Harness" panel listing sessions, with timeline + GcScore for the selected session.
- [ ] **Harness — install.sh `--run` flag.** `install.sh` and `install.ps1` accept `--run <subcommand> [args…]` so `curl … | sh -s -- --run harness 12345` installs and runs in one shot.
- [ ] **i18n drift backfill.** `messages_en.properties` is 15 keys ahead of ko/ja/zh — independent of harness work. Identify the missing keys and translate them.
