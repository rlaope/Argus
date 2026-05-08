# Argus Harness

`argus harness <pid>` runs a **continuous, trend-aware health watch** on a target JVM. It samples on a fixed interval, runs the existing 12 doctor health rules plus four trend rules on every tick, and emits a single severity-ranked session report at the end.

The harness is **observation-only**. It does not mutate the JVM. Findings produce recommendations and JVM-flag suggestions; the user decides whether to act.

## When to use it vs. `argus doctor`

| | `argus doctor` | `argus harness` |
|---|---|---|
| Single snapshot | ✓ | – |
| Time-window analysis | – | ✓ |
| Catches leaks (sustained heap growth) | partial | ✓ |
| Catches GC regression vs. earlier in window | – | ✓ |
| Catches thread-pool leaks | partial (high count only) | ✓ |
| Exit-code semantics | 0/1/2 | 0/1/2 |
| Output format | rich box / JSON | rich box / JSON / file |

If the user is asking "is my JVM healthy *right now*?", run doctor. If they're asking "what is happening to my JVM *over time*?", run harness.

## Usage

```bash
argus harness <pid>                                # default: profile=deep, duration=30m
argus harness <pid> --profile=quick                # 2s tick, 1m default duration
argus harness <pid> --profile=deep --duration=15m
argus harness <pid> --interval=5s --duration=2m    # explicit, overrides profile
argus harness <pid> --out=/tmp/session.json        # also write JSON to file
argus harness <pid> --format=json                  # emit JSON only (no live ticks)
```

Subcommand `--help` prints a usage card.

## Profiles

| Profile | Interval | Default duration | Use case |
|---|---|---|---|
| `quick` | 2 s | 1 min | Demos, fast spot checks, CI smoke. |
| `deep`  | 10 s | 30 min | Real investigation; default. |

`--interval` and `--duration` always win over the profile defaults.

## Trend rules

The harness adds four rules on top of the doctor set. Each takes the entire retained sample window and returns zero or more `Finding`s.

### `HeapGrowthLeakRule` (CRITICAL)

Linear regression on `heapUsed` over time. Fires when:

- ≥ 5 samples,
- R² ≥ 0.85 (samples are well-explained by a straight line), and
- slope ≥ 1 MB/min.

This is the canonical leak signature. A typical warm-up that levels off won't trigger it because R² drops fast once the curve plateaus.

### `GcOverheadTrendRule` (WARNING)

Splits the window in half and compares average `gcOverheadPercent`. Fires when:

- ≥ 6 samples,
- second half ≥ 2× first half, and
- second half ≥ 2 % absolute.

Catches GC overhead trending up before the doctor's static threshold trips.

### `ThreadGrowthRule` (WARNING)

Compares `threadCount` between first and last sample, and fits a line for confirmation. Fires when:

- ≥ 5 samples,
- `last − first ≥ 50` threads, and
- regression slope > 0 (sustained, not noise).

Almost always means an unbounded executor or connection pool.

### `PauseTrendRule` (WARNING)

Same shape as `GcOverheadTrendRule` but on `maxRecentPauseMs`. Fires when:

- ≥ 6 samples,
- second half ≥ 2× first half, and
- second half ≥ 50 ms absolute.

Surfaces pause-time SLO drift the static `MaxPauseRule` only sees instantaneously. Skipped on the remote-jcmd path when pause data is not populated.

## Output

### Rich (default)

Header banner with PID / profile / duration / tick count, severity counters (Nx critical, Nx warning, Nx info), then one block per *deduplicated* finding with the rule's detail line, recommendations, and a "(fired N ticks)" suffix when it triggered repeatedly. A trailing "Suggested JVM Flags" panel collects every `suggestedFlags` value across findings.

### JSON (`--format=json` or programmatic via `--out=<path>`)

Stable schema:

```json
{
  "pid": 39113,
  "profile": "deep",
  "startTimeMs": 1715000000000,
  "endTimeMs": 1715001800000,
  "durationMs": 1800000,
  "sampleCount": 180,
  "exitCode": 1,
  "counts": { "critical": 0, "warning": 2, "info": 0 },
  "findings": [
    {
      "severity": "WARNING",
      "category": "GC",
      "title": "GC overhead is trending up",
      "detail": "Average GC overhead rose from 0.40% to 1.10% (2.7x) over the harness window",
      "hits": 12,
      "recommendations": [
        "Capture allocation profile: argus profile <pid> --event=alloc --duration=30",
        "Inspect recent GC log if available: argus gclog <gc.log>",
        "Run argus gcwhy <pid> for a per-event explanation of the worst recent pause"
      ],
      "suggestedFlags": []
    }
  ]
}
```

`hits` is the per-rule fire count across the session — a leak rule that triggered every tick still appears once but with a high `hits` value.

### Live ticks

Without `--format=json`, the harness prints one dim line per tick:

```
  tick   3  t+24s     heap  41.8%  gc 0.42%  thr   29  -> 0C 1W 0I
```

That is `(tick, elapsed, heap%, GC overhead, thread count, → critical/warning/info from this tick)`.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | No findings — harness window was healthy. |
| `1` | One or more `WARNING` findings. |
| `2` | One or more `CRITICAL` findings. |

Useful for CI and runbooks: `argus harness <pid> --duration=2m || alert "JVM not healthy"`.

## Out of scope

The harness intentionally does **not**:

- mutate the JVM (no `vmset`, `gcrun`, `heapdump` triggers — those remain the user's call);
- store data on disk beyond the optional `--out=<file>` snapshot (in-memory rolling window only);
- monitor more than one PID at a time;
- call into LLMs to generate explanations (it uses pattern-matching rules, not free-form text).

## Related commands

- `argus doctor <pid>` — single-snapshot equivalent.
- `argus gcwhy <pid>` — explain the worst recent pause in plain language.
- `argus gcscore <gc.log>` — A–F GC health grade from a log file.
- `argus heapdump <pid>` — capture a heap dump for offline analysis.
