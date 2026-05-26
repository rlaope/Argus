---
name: docs-sync
description: Audit and update Argus user-facing docs (README, docs/**/*.md, site/*.html) against the code truth. Detects version drift, command-count drift, i18n parity violations, and stale CLI examples. Use before a release, or any time the user asks to "sync docs", "update docs", "fix docs drift", "verify docs".
argument-hint: "[--check] [--fix]"
---

# docs-sync

Keep Argus public-facing documentation aligned with the actual codebase.

## When to use

- Before tagging a release
- After adding/removing/renaming a CLI command
- After bumping `argusVersion` in `gradle.properties`
- After adding or modifying i18n keys in `messages_en.properties`
- User says: "sync docs", "update docs", "fix docs drift", "/docs-sync"

## Authoritative sources (the truth)

| Truth | Path |
|---|---|
| Project version | `gradle.properties` → `argusVersion` |
| CLI command set | `argus-cli/src/main/java/io/argus/cli/command/*Command.java` (one file per command, minus `Command.java` interface and any abstract base) |
| i18n keys (master) | `argus-cli/src/main/resources/messages_en.properties` |
| Locale parity | `messages_{ko,ja,zh}.properties` must contain every key from `messages_en.properties` |
| Java baseline | `build.gradle.kts` JavaLanguageVersion |

## Targets to update (the consumers)

| Target | What to check |
|---|---|
| `README.md` | Version badges/numbers, "N commands" claim, install snippets, feature highlights |
| `docs/README.md` | "all N commands" copy |
| `docs/cli-commands.md` | "N Argus CLI commands" header, A–Z command index completeness |
| `docs/getting-started.md` | Version refs, JAR filename, command-count cell in compatibility table |
| `docs/configuration.md` | Flag names match code, default values match code |
| `docs/architecture.md` | Module list matches `settings.gradle.kts` |
| `docs/usage.md` | Spring Boot starter version |
| `docs/kubernetes.md` | Container image tag |
| `docs/troubleshooting.md` | Error messages match `messages_en.properties` |
| `site/*.html` | Version badge, command-count badge + prose + headings on every page (index.html, commands.html, comparison.html, dashboard.html, scenarios.html, integrations.html, reference.html), code samples |

## Procedure

Run all checks; only edit when a drift is confirmed. Never invent content.

### 1. Resolve the truth

```bash
ARGUS_VERSION="$(awk -F= '/^argusVersion=/{print $2}' gradle.properties)"

# Real command count: every *Command.java in the command/ dir, minus the Command interface and any abstract bases
CMD_COUNT=$(ls argus-cli/src/main/java/io/argus/cli/command/*Command.java \
  | grep -v -E '/(Command|AbstractCommand|BaseCommand|CommandGroup)\.java$' \
  | wc -l | tr -d ' ')

# i18n key sets
for L in en ko ja zh; do
  grep -E '^[a-zA-Z][^=]*=' "argus-cli/src/main/resources/messages_${L}.properties" \
    | cut -d= -f1 | sort > "/tmp/keys_${L}.txt"
done
```

Print a single TRUTH block to the user before doing any writes:
```
TRUTH
  version       = $ARGUS_VERSION
  commands      = $CMD_COUNT
  locale-keys   = en:N ko:N ja:N zh:N
```

### 2. Drift scan (read-only)

For each target file, search for any line that mentions a version or command count and compare to truth. Build a list of `{file, line, was, should-be}` entries. Don't touch files yet.

Useful greps:
```bash
# Version drift
grep -rEn 'v?[0-9]+\.[0-9]+\.[0-9]+' README.md docs/ site/*.html \
  | grep -v -E '(java|jdk|jvm|netty|micrometer|junit|spring|node|graalvm|grafana|prometheus|3\.[0-9]|11\+|17\+|21\+)' \
  | head -50

# Command-count drift (per-file listing)
grep -rnE '[0-9]{2}\+? ?(commands|Commands)' README.md docs/ site/*.html

# Command-count internal consistency (catches the trap where the SAME README
# says "67 commands" on line 14 and "66 diagnostic commands" on line 64 — listing
# alone doesn't fail; collapse all occurrences plus the runtime help banner to a
# unique-value set and fail if there's more than one).
COUNT_OCCURRENCES=$(grep -rhEo '[0-9]{2,3} (diagnostic )?commands?' \
    README.md docs/ site/*.html 2>/dev/null \
  | sed -E 's/ diagnostic / /' \
  | awk '{print $1}' \
  | sort -u)
HELP_COUNT=""
if command -v argus &>/dev/null; then
  HELP_COUNT=$(argus --help 2>&1 | sed -E "s/\x1b\[[0-9;]*m//g" \
    | grep -oE '[0-9]{2,3} commands' | head -1 | awk '{print $1}')
fi
echo "docs occurrences: $(echo "$COUNT_OCCURRENCES" | tr '\n' ' ')"
echo "argus --help    : ${HELP_COUNT:-(argus not on PATH)}"
echo "truth (source)  : $CMD_COUNT"
ALL=$(printf '%s\n%s\n%s\n' "$COUNT_OCCURRENCES" "$HELP_COUNT" "$CMD_COUNT" \
  | grep -E '^[0-9]+$' | sort -u)
if [ "$(echo "$ALL" | wc -l | tr -d ' ')" -gt 1 ]; then
  echo "DRIFT — multiple command counts in circulation: $(echo "$ALL" | tr '\n' ' ')"
else
  echo "OK — single command count everywhere: $ALL"
fi

# i18n parity
diff <(cat /tmp/keys_en.txt) <(cat /tmp/keys_ko.txt)
diff <(cat /tmp/keys_en.txt) <(cat /tmp/keys_ja.txt)
diff <(cat /tmp/keys_en.txt) <(cat /tmp/keys_zh.txt)
```

### 3. Command-list drift

Compare the A–Z index in `docs/cli-commands.md` against the actual `*Command.java` filenames. Report any command in source but not in docs (or vice versa). Do not invent docs entries — escalate to the user with a list and the natural category to add it under.

### 4. CLI example drift (best effort)

For any code block in `README.md` or `site/*.html` of the form `argus <subcommand> ...`, verify `<subcommand>` exists in the command set computed in step 1. Flag unknowns.

### 5. Fix policy

- **Pure value swaps** (version number, command count, badge text): edit directly.
- **i18n parity gaps**: add the missing key in each locale file. Translate using the file's existing tone; never use machine-generated stubs in production locales without an English fallback. Use printf `%s` placeholders, never MessageFormat `{0}` (project rule).
- **Removed-command references in docs**: ask the user before deleting prose about a command — they may want to keep a deprecation note instead.
- **New commands missing from docs**: ask the user which category (`monitoring`, `memory-gc`, `threads`, `runtime-internals`, `profiling-tracing`) and a one-line synopsis.

### 6. Verify

After edits:
```bash
./gradlew :argus-cli:test --quiet
# Re-run the diff in step 2; expect parity diffs to be empty
```

## Output format

End with a structured report:

```
docs-sync report
================
Truth:    version=1.2.0  commands=66  i18n=529 keys × 4 locales
Checked:  N files
Drift fixed:
  - README.md:14            "65+ commands"  →  "66 commands"
  - site/index.html:970     "0.8.0"         →  "1.2.0"
  - messages_zh.properties  +4 keys (cmd.gcprofile.desc, ...)
Drift escalated:
  - docs/cli-commands.md does not list `argus newcmd` — please confirm category
Build verification: ./gradlew :argus-cli:test → exit 0
```

## Project rules to honor

- Public-facing text in English (per project memory).
- i18n strings use printf `%s`, never MessageFormat `{0}`.
- All four locale files must stay in parity; do not skip a locale.
- Never commit to master directly — finish on a branch and propose a PR.
- Don't edit `argusVersion` in `gradle.properties` from this skill; that belongs to the release flow.

## Out of scope

- External distribution surfaces (Helm chart, install.sh, Formula, docker-compose) → use `release-sync`.
- Translating English copy into KO/JA/ZH wholesale — only fill parity gaps.
- Generating new architecture diagrams or screenshots.
