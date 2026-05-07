# Contributing to Argus

Conventions for adding code, bumping versions, and shipping releases.
This file is the canonical source — `CLAUDE.md` only points here.

---

## Build, Test, Install

```bash
./gradlew :argus-cli:fatJar         # build the standalone CLI JAR
./gradlew :argus-cli:test           # run the test suite
cp argus-cli/build/libs/argus-cli-*-all.jar ~/.argus/argus-cli.jar
```

- Java 21 bytecode target.
- The CLI itself diagnoses JVM 11+ (the target JVM does not need to be 21).

---

## Commit Conventions

- Prefix one of: `feat:` / `fix:` / `docs:` / `refactor:` / `test:` / `chore:`.
- Always commit with `-s` (DCO sign-off).
- **Do NOT** include `Co-Authored-By: Claude` lines.
- Keep the subject under 70 chars; put detail in the body.
- One logical change per commit. Don't squash unrelated work.

Example:

```
feat: ZGC live diagnosis (argus zgc)

Adds 30s JFR capture + verdict ...

Signed-off-by: Your Name <you@example.com>
```

---

## Adding a New CLI Command

Read [`docs/architecture.md`](architecture.md) for the full SPI / Provider / CommandGroup pattern. Quick checklist:

1. Create the command class under `argus-cli/src/main/java/io/argus/cli/command/<Name>Command.java` implementing `Command`.
2. Register it in `argus-cli/src/main/java/io/argus/cli/ArgusCli.java`.
3. Add user-facing strings to **all four** locale files in `argus-cli/src/main/resources/`:
   - `messages_en.properties`
   - `messages_ko.properties`
   - `messages_ja.properties`
   - `messages_zh.properties`
4. Add an entry to shell completions if the command exposes flags worth tab-completing.
5. Write at least one test under `argus-cli/src/test/java/io/argus/cli/command/<Name>CommandTest.java`.

### i18n format

`Messages.get(key, args)` uses **printf-style `%s`**, NOT MessageFormat `{0}`.

```properties
# Correct
cli.threaddump.summary=%s threads, %s daemon

# Wrong — will render literal "{0} threads"
cli.threaddump.summary={0} threads, {1} daemon
```

Properties files are mixed UTF-8 + `\uXXXX` escapes. `ResourceBundle.getBundle` on Java 9+ defaults to UTF-8 (JEP 226), so literal UTF-8 entries also work.

### Locale parity

Every i18n key must exist in all four locale files. Verify:

```bash
for f in argus-cli/src/main/resources/messages_*.properties; do
  echo "$f: $(grep -c '^cli\.your\.new\.prefix\.' "$f")"
done
```

All four counts must match.

---

## Releases

### Version source of truth

`gradle.properties` → `argusVersion` is the single source. After bumping, grep for stragglers in markdown and HTML:

```bash
grep -rn 'OLD_VERSION' --include='*.md' --include='*.html' .
```

Update every match. Common locations: `README.md`, `docs/kubernetes.md` (image tag), `site/index.html`, `docs/getting-started.md`.

### Runtime version reads

Code must read the version from the JAR manifest's `Implementation-Version` attribute. **Never hardcode** the version string in Java source.

```java
// Correct
String version = ArgusCli.class.getPackage().getImplementationVersion();

// Wrong
String version = "1.1.0";  // will rot
```

### Release flow

The repo has a release skill cache at `.omc/RELEASE_RULE.md`. Release steps:

1. Bump `argusVersion` in `gradle.properties`.
2. Run the grep above; update every stale reference.
3. Commit: `chore(release): bump to vX.Y.Z`.
4. Tag: `git tag -a vX.Y.Z -m "vX.Y.Z"`.
5. Push commit + tag.
6. CI workflow `.github/workflows/release.yml` (if present) handles publishing.

Releases are triggered by tag push. **Don't tag for trivial fixes** — accumulate meaningful changes.

---

## Testing Patterns

- Tests live under `argus-cli/src/test/java/...` mirroring `main/`.
- Use existing test fixtures (e.g., `JvmSnapshotBuilder` if present, otherwise construct records directly).
- Integration tests against real JVMs go in `argus-cli/src/test/java/io/argus/cli/integration/`.
- For the Spring Boot starter, **publish to Maven Local** + create a separate test project. Do **not** add it as a Gradle `include()` — that masks classpath/auto-config issues that bite real users.

---

## PR / Branch Workflow

- All work goes through a feature branch + PR. **Never commit directly to `master`** unless the user explicitly authorizes it for a specific scope.
- Branch naming: `feat/<short-name>`, `fix/<issue-N>`, `docs/<topic>`.
- All public-facing text (issue titles, PR descriptions, release notes, docs) is in **English**. Internal team chat can be Korean.

---

## See Also

- [`docs/architecture.md`](architecture.md) — module layout, SPI patterns, provider hierarchy.
- [`docs/cli-commands.md`](cli-commands.md) — full command reference.
- [`docs/configuration.md`](configuration.md) — flag tables, env vars, JVM options.
- [`docs/troubleshooting.md`](troubleshooting.md) — common errors and fixes.
