# Argus Project Guidelines

## Version Management
- **Single source of truth**: `gradle.properties` → `argusVersion=X.Y.Z`
- After ANY version bump, run: `grep -rn '0\.\d\+\.\d\+' --include='*.md' .` to find stale references
- **Version reference locations** (ALL must be updated on release):
  - `gradle.properties` — canonical version
  - `README.md` — banner text, Maven/Gradle dependency snippets
  - `docs/getting-started.md` — download URLs, JAR paths, CLI examples
  - `docs/usage.md` — Spring Boot starter version
  - `site/index.html` — if version is shown on docs site
  - `.github/ISSUE_TEMPLATE/*.md` — example version placeholders
- **Release checklist**: bump `gradle.properties` → `./gradlew publishToMavenLocal` → grep for old version → update all docs → tag

## Build & Test
- Java 21 (no --enable-preview needed since v0.8.0)
- Build: `./gradlew :argus-cli:build`
- Fat JAR: `./gradlew :argus-cli:fatJar`
- Run: `java -jar argus-cli/build/libs/argus-cli-*-all.jar <command>`
- Test with real JVM: use `argus ps` to find a PID, then run commands against it
- Spring Boot starter test: `./gradlew publishToMavenLocal` → separate project with `mavenLocal()` dependency

## Adding a New CLI Command (Checklist)
Every new command MUST include ALL of the following:
1. **Result model** — `argus-cli/src/main/java/io/argus/cli/model/<Name>Result.java`
2. **Provider interface** — `argus-cli/src/main/java/io/argus/cli/provider/<Name>Provider.java`
3. **JDK implementation** — `argus-cli/src/main/java/io/argus/cli/provider/jdk/Jdk<Name>Provider.java`
4. **Command class** — `argus-cli/src/main/java/io/argus/cli/command/<Name>Command.java`
5. **Register in ArgusCli.java** — import + `register(commands, new <Name>Command())`
6. **Register in ProviderRegistry.java** — provider list + finder method + JDK registration
7. **i18n messages** — ALL 4 property files: `messages_en.properties`, `messages_ko.properties`, `messages_ja.properties`, `messages_zh.properties`
8. **Shell completions** — `completions/argus.bash` and `completions/argus.zsh`
9. **Help text** — Add to `printUsage()` in ArgusCli.java
10. **Unit tests** — Parser test in `argus-cli/src/test/java/io/argus/cli/provider/jdk/`
11. **Documentation** — Update `README.md` (command table + count) and `docs/cli-commands.md`
12. **Real JVM test** — Build fat JAR and test against a live JVM process
13. **Sync verification** — After all changes, verify these 4 sources are in sync:
    - CLI help output (`argus` with no args) — all commands listed
    - `README.md` — command table and count updated
    - `docs/cli-commands.md` — command reference and count updated
    - `completions/argus.bash` + `argus.zsh` — command names included
    - `install.sh` — if install logic references specific commands, update it too

## Commit Convention
- `feat:` for new features, `fix:` for bug fixes, `docs:` for documentation
- Always use `-s` flag for DCO sign-off
- Do NOT include `Co-Authored-By: Claude` line

## Code Patterns
- Commands implement `Command` interface with `name()`, `description()`, `execute()`
- All commands support `--format=json` and `--source=auto|agent|jdk`
- Use `RichRenderer` for box drawing, progress bars, formatting
- Use `AnsiStyle` for colors (respect `useColor` flag)
- Result models are immutable final classes with accessor methods
