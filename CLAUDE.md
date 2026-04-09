# Argus Project Guidelines

## Quick Reference
- **Build**: `./gradlew :argus-cli:fatJar`
- **Test**: `./gradlew :argus-cli:test`
- **Install**: `cp argus-cli/build/libs/argus-cli-*-all.jar ~/.argus/argus-cli.jar`
- **Java**: 21 bytecode target. CLI diagnoses JVM 11+

## Rules
- Version source of truth: `gradle.properties` → `argusVersion`. After bump: `grep -rn 'OLD_VERSION' --include='*.md' --include='*.html' .`
- Commit: `feat:`/`fix:`/`docs:`/`refactor:`/`test:` with `-s` flag. No `Co-Authored-By: Claude`.
- VERSION: read from JAR manifest `Implementation-Version` (never hardcode)

## Adding Commands
See [docs/architecture.md](docs/architecture.md) for full patterns (SPI, Provider, CommandGroup, i18n, TUI).

Checklist: Command class → ArgusCli register → 4x messages_*.properties → completions → test
