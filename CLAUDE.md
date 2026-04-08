# Argus Project Guidelines

## Quick Reference
- **Version**: `gradle.properties` → `argusVersion` (single source of truth, read by JAR manifest)
- **Java**: 21 required (bytecode target). Diagnoses any JVM 11+
- **Build**: `./gradlew :argus-cli:fatJar`
- **Test**: `./gradlew :argus-cli:test` (125+ tests)
- **Install locally**: `cp argus-cli/build/libs/argus-cli-*-all.jar ~/.argus/argus-cli.jar`

## Version Management
After ANY version bump: `grep -rn 'OLD_VERSION' --include='*.md' --include='*.html' .`

Update locations: `gradle.properties`, `README.md`, `docs/getting-started.md`, `docs/usage.md`, `site/index.html`, `install.sh`

## Adding a New CLI Command
1. Command class → `argus-cli/.../command/<Name>Command.java` (implements `Command`, override `group()`)
2. Register in `ArgusCli.java` → `register(commands, new <Name>Command())`
3. i18n → `cmd.<name>.desc=...` in all 4 `messages_*.properties`
4. Completions → `completions/argus.bash`, `.zsh`, `.fish`, `.ps1`
5. Test + docs update

For commands with provider pattern (jcmd-based): add Result model, Provider interface, JdkProvider, register in ProviderRegistry.

For SPI server commands: add `DiagnosticCommand` impl in `argus-server/.../impl/`, register in `META-INF/services`.

See [Architecture Guide](docs/architecture.md) for full module structure and code patterns.

## Commit Convention
- `feat:` / `fix:` / `docs:` / `refactor:` / `test:`
- Always use `-s` flag for DCO sign-off
- Do NOT include `Co-Authored-By: Claude` line

## Key Architecture Decisions
- **DiagnosticCommand SPI** — `argus-core` shared interface, ServiceLoader auto-discovery
- **CommandGroup** — shared enum for CLI help categorization + server API grouping
- **JvmSnapshotCollector** — local (MXBean) vs remote (jcmd parsing), routes by PID
- **Doctor rules** — pluggable `HealthRule` interface, each rule independent
- **TUI** — JLine3 + alt screen buffer, 3-phase flow (PS → CMD → OUT)
- **VERSION** — read from JAR manifest `Implementation-Version` (never hardcode)
