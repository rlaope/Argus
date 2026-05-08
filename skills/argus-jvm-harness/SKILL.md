---
name: argus-jvm-harness
description: Continuous JVM monitoring + optimization + troubleshooting harness on top of the Argus CLI. Use when the user wants to watch a running JVM for trouble (GC regression, heap leak, thread growth, pause-time spikes) over a window of time and get a single severity-ranked report with recommended JVM flags. Bootstraps the Argus CLI via the official installer when it is not already on PATH.
license: MIT
---

# Argus JVM Harness

A thin Claude Code wrapper around `argus harness <pid>`. The CLI does the work; this skill picks the right invocation, bootstraps the CLI when needed, and summarises the resulting session in natural language.

## When to use

The user says any of: "monitor my JVM", "watch this process", "check why GC keeps spiking", "is there a leak in pid X", "tune the JVM at <pid>", "harness", or anything implying a *time-window* health check rather than a single-snapshot one. For one-shot questions ("how does my heap look right now?"), prefer `argus doctor <pid>` instead.

## Required input

A target PID. If the user hasn't given one, ask them or run `argus ps` first to list candidates.

## Flow

1. **Locate the CLI.** Run `command -v argus`. If absent, install it once with the official installer (no flags, public host):
   ```bash
   curl -fsSL https://raw.githubusercontent.com/rlaope/Argus/master/install.sh | bash
   ```
   then re-resolve PATH with `source ~/.zshrc` or use the absolute path `~/.argus/bin/argus`.

2. **Pick a profile.**
   - `--profile=quick` (2 s tick, default 1 min): use for fast spot-checks and demos.
   - `--profile=deep` (10 s tick, default 30 min): default for real investigations.
   The user's words usually decide: "quick look" → quick; "monitor for 10 minutes / for a while / overnight" → deep.

3. **Run with a JSON sink** so the model can read findings programmatically:
   ```bash
   argus harness <pid> --profile=deep --duration=<user-specified-or-30m> --out=/tmp/argus-harness.json --format=json
   ```
   Stream stdout to the user — the live tick line is informative — but use the JSON file as the source of truth for the summary.

4. **Summarise.** After the command exits, read `/tmp/argus-harness.json` and report:
   - Exit-code verdict (0 healthy / 1 warnings / 2 critical).
   - Each finding's `severity`, `title`, and `detail`, ordered as the JSON returns them (engine already sorted by severity).
   - The `hits` count for any finding that fired more than once — that is the harness's signal of a *persistent* problem versus a transient spike.
   - The deduplicated `suggestedFlags` across all findings, in a single block the user can paste into their JVM args.
   - Concrete next-step CLI commands the user can run (the JSON's `recommendations` array already contains them).

5. **Do not modify the JVM.** This skill is observation + recommendation only. Never invoke `argus vmset`, `argus gcrun`, `argus heapdump`, or `argus jfr start` automatically. If the recommendations suggest one of those, surface it as a suggestion and let the user run it.

## Constraints

- The harness command exits with code 1 (warnings) or 2 (critical). Treat that as a *signal*, not an error — a non-zero exit on a healthy harness run indicates the JVM had real problems, which is the whole point.
- Default `--duration` from the profile if the user did not specify one. Don't pick numbers out of thin air.
- The JSON document is stable across versions; rely on `findings[].severity`, `findings[].title`, `findings[].detail`, `findings[].recommendations[]`, `findings[].suggestedFlags[]`, and `counts.{critical,warning,info}`.

## Reference

- CLI usage: `argus harness --help`
- Full reference: <https://github.com/rlaope/Argus/blob/master/docs/harness.md>
- Underlying rule set: 12 doctor health rules + 4 trend rules (heap-leak regression, GC overhead trend, thread growth, GC pause regression).
