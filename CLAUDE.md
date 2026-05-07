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
