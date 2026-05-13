# Argus Commands — Profiling & Tracing

Commands for capturing and analyzing runtime performance data. This category covers CPU, allocation, lock, and wall-clock profiling via async-profiler, JFR recording control, flame graph generation, real-time slow method detection, method-level call tracing, and offline JFR file analysis. Use these commands to find hot methods, allocation hotspots, lock contention, and throughput regressions.

## Table of Contents

- [argus profile \<pid\>](#argus-profile-pid)
- [argus profile-gate \<before\> \<after\>](#argus-profile-gate-before-after)
- [argus flame \<pid\>](#argus-flame-pid)
- [argus jfr \<pid\> \<subcommand\>](#argus-jfr-pid-subcommand)
- [argus jfranalyze \<file.jfr\>](#argus-jfranalyze-filejfr)
- [argus slowlog \<pid\>](#argus-slowlog-pid)
- [argus trace \<pid\> \<class.method\>](#argus-trace-pid-classmethod)
- [argus benchmark \<pid\> \<class.method\>](#argus-benchmark-pid-classmethod)

---

## argus profile \<pid\>

CPU, allocation, lock, and wall-clock profiling powered by async-profiler. Auto-downloads the native binary on first use.

```bash
$ argus profile 29286 --duration 3
```

```
 argus profile
 Profiling with async-profiler. Shows hottest methods by sample count.

╭─ Profile Results ── pid:29286 ── cpu 3s ── 3 samples ────────────────────────╮
│                                                                              │
│   #   Method                                           Samples        %      │
│   ────  ────────────────────────────────────────────  ──────────  ────────   │
│   1   __psynch_cvwait                                        2    66.7%  ███ │
│   2   std::__1::basic_ostream<char, std::__1::cha…           1    33.3%  █   │
│                                                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--type cpu|alloc|lock|wall` — Profiling type (default: cpu)
- `--duration N` — Duration in seconds (default: 30)
- `--flame` — Generate flame graph HTML and open in browser
- `--file NAME` — Output file for flame graph
- `--top N` — Show top N methods (default: 20)
- `--format=json` — JSON output
- `--save=PATH` — Persist this run as a snapshot (JSON) for later diffing
- `--diff=PATH` — Run profile then diff against a saved snapshot
- `--diff=A:B` — Pure-diff of two saved snapshots (no pid required)

Flame graph example:
```bash
$ argus profile 12345 --flame --duration 60
# Generates HTML flame graph and opens in browser
```

### Session subcommands (start / stop / dump / status)

For incident capture and continuous profiling — attach now, dump when something happens.

```bash
$ argus profile start 12345 --type=cpu        # attach, profile in background
$ argus profile status 12345                  # "Profiling for 145s using cpu event"
$ argus profile dump 12345 --output=now.html  # snapshot without stopping
$ argus profile stop 12345 --output=final.html --output-format=flamegraph
```

`--output-format` accepts `flamegraph` (default for `.html`), `collapsed`, `jfr`, `tree`, `text`.
The `.jfr` format is consumable by JDK Mission Control / IntelliJ Profiler.

### Save and diff for regression detection

Mirrors `nmt --save / --diff`. Captures all top methods (not just top-20) so deltas are accurate.

```bash
# Capture baseline before deploy
$ argus profile 12345 --duration=30 --save=before.json

# Live-vs-baseline after deploy
$ argus profile 12345 --duration=30 --diff=before.json

# Pure file-vs-file
$ argus profile --diff=before.json:after.json
```

Output highlights regressions (red, samples increased) and improvements (green), with `(NEW)` / `(GONE)` markers for methods present in only one snapshot. JSON output supported via `--format=json`.

### Advanced flags (asprof passthrough)

Power-user flags forwarded directly to async-profiler. All optional; defaults preserve current behavior.

| Flag | asprof | Notes |
|------|--------|-------|
| `--interval=N[ms\|us\|ns]` | `-i N` | Sampling interval (default 10ms is asprof default) |
| `--jstackdepth=N` | `-j N` | Max Java stack depth, clamped 1..2048 |
| `--cstack=fp\|dwarf\|lbr\|vm\|no` | `--cstack <mode>` | Native stack unwinding — use `dwarf` for JNI debugging |
| `--threads` | `-t` | Per-thread output |
| `--alluser` / `--allkernel` | `--alluser`/`--allkernel` | Mutually exclusive |
| `--alloc=N[k\|m\|g]` | `--alloc N` | Allocation threshold (only with `--type=alloc`) |
| `--live` | `--live` | Only count live allocations (only with `--type=alloc`) |
| `--include=PATTERN` | `-I PATTERN` | Repeatable include filter |
| `--exclude=PATTERN` | `-X PATTERN` | Repeatable exclude filter |
| `--reverse` | `--reverse` | Stack-reversed (icicle) flame graph — surfaces hot leaf frames' callers |
| `--minwidth=PCT` | `--minwidth pct` | Skip frames narrower than PCT% — noise reduction for large captures |
| `--sched` | `--sched` | Group threads by scheduling policy (Linux only) |
| `--clock=tsc\|monotonic` | `--clock source` | Clock source for JFR timestamps — use `monotonic` for container correctness |
| `--signal=N` | `--signal num` | Alternative signal number for cpu/wall profiling — resolves signal conflicts |
| `--proc=N` | `--proc interval` | Process sampling interval (e.g. `30s`) — how often asprof probes for thread list changes |
| `--nofree` | `--nofree` | Exclude free() events from `nativemem` profiling — focus on allocation hotspots |

```bash
# Native stacks for JNI debugging:
$ argus profile 12345 --duration=30 --cstack=dwarf

# Tighter sampling for short-running methods:
$ argus profile 12345 --duration=30 --interval=1ms --jstackdepth=64

# Allocation profiling with custom threshold + live filter:
$ argus profile 12345 --duration=30 --type=alloc --alloc=128k --live

# JFR output for JMC analysis (one-shot path):
$ argus profile 12345 --duration=30 --output-format=jfr --output=run.jfr
```

### Output formats

Full list of values accepted by `--output-format=` on `profile`, `profile stop`, `profile dump`, and `flame`:

| Format | Extension | Description |
|--------|-----------|-------------|
| `flamegraph` | `.html` | Interactive SVG flame graph (default for `.html` output) |
| `collapsed` | `.collapsed.txt` | Folded stacks — compatible with `flamegraph.pl` |
| `jfr` | `.jfr` | JDK Flight Recorder file — open in JDK Mission Control or IntelliJ Profiler |
| `tree` | `.html` | Call-tree HTML (top-down, expandable) |
| `text` | `.txt` | Plain-text top-methods table |
| `flat` | `.txt` | Flat list of all leaf frames with sample counts |
| `traces` | `.txt` | Full stack traces, one block per sample |
| `otlp` | `.otlp.json` | OpenTelemetry-compatible JSON payload |
| `ascii` | stdout | Inline ASCII flame graph rendered directly in the terminal — no file written |

**ASCII flame graph example:**

```bash
$ argus profile 12345 --duration=10 --output-format=ascii
```

```
  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ com.example.App.handleRequest (42%)
    ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ com.example.db.Query.execute (21%)
      ▓▓▓▓▓▓▓▓▓▓ java.sql.PreparedStatement.executeQuery (10%)
```

### Event types (PMU + method trace)

`--type=` accepts three categories of event:

| Category | Examples | Notes |
|----------|----------|-------|
| Built-in aliases | `cpu`, `alloc`, `lock`, `wall` | Default; portable across platforms |
| PMU hardware counters | `cycles`, `cache-misses`, `branch-misses`, `instructions`, `bus-cycles` | Linux perf events; requires `perf_event_paranoid <= 1` |
| Method-trace events | `java.lang.String.intern`, `com.example.MyClass.myMethod` | Fully-qualified `ClassName.methodName`; case-sensitive |

Unknown event names are forwarded to asprof, which surfaces a clear "unknown event" error if the kernel does not support the counter.

```bash
# PMU counter: instructions retired
$ argus profile 12345 --duration=10 --type=cycles

# Method-trace: count invocations of String.intern
$ argus profile 12345 --duration=10 --type=java.lang.String.intern
```

### Continuous profiling mode

Attaches a background profiling session and dumps a snapshot every `--interval=N` seconds. Ctrl-C stops cleanly. Useful for catching intermittent spikes without a fixed capture window.

```bash
$ argus profile continuous 12345
$ argus profile continuous 12345 --interval=60 --output-dir=./snapshots
$ argus profile continuous 12345 --diff-against=baseline.json
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--interval=N` | 30 | Dump interval in seconds |
| `--window=N` | 5 | Retain snapshots for the last N minutes; older files are pruned from `--output-dir` |
| `--output-dir=PATH` | — | Directory to write per-interval snapshot files (`snap-<epoch>.collapsed.txt`) |
| `--diff-against=PATH` | — | Fixed baseline snapshot; each interval prints a delta against it instead of the previous interval |
| `--type=EVENT` | cpu | Profiling event type (same values as one-shot mode) |

Each interval prints a one-line status:

```
[12:04:01]  8,432 samples  top: com.example.App.handleRequest (38.2%)  Δ+2.1pp
```

### Multi-PID parallel profiling

Profile multiple JVM processes simultaneously. All PIDs run in parallel (thread pool sized to available CPUs). Results are collected into a per-PID summary table; a failure on one PID does not abort the others.

```bash
# Comma-separated PIDs as first argument
$ argus profile 12345,67890,11111 --duration=20

# --pids flag form (interchangeable)
$ argus profile --pids=12345,67890,11111 --duration=20 --type=alloc
```

```
╭─ Multi-PID Profile ── 3 processes ───────────────────────────────────────────╮
│                                                                              │
│  pid        samples   top method                                   status   │
│  ────────  ─────────  ──────────────────────────────────────────  ──────── │
│  12345       8,432    com.example.App.handleRequest (38%)          ok       │
│  67890       6,100    java.lang.Thread.sleep (62%)                 ok       │
│  11111           -    -                                            timeout  │
│                                                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

**Additional option:** `--output-prefix=PATH` — write per-pid flame graph HTML as `<PATH>-<pid>.html`.

### Pre-flight permission checks (Linux)

Argus validates `/proc/sys/kernel/perf_event_paranoid`, `kptr_restrict`, `/proc/<pid>/status` accessibility, `ptrace_scope`, and container/cgroup state before running asprof. Failures emit copy-pasteable fix commands (e.g. `sudo sysctl kernel.perf_event_paranoid=1`) instead of cryptic asprof errors. Container detection (Docker / containerd / k8s / lxc) emits a one-line warning about `--cap-add=SYS_ADMIN` and host-vs-container PID mapping.

---

## argus profile-gate \<before\> \<after\>

CI/CD regression gate. Compares two profile snapshots produced by `--save=PATH` and exits non-zero if any method's CPU share increased beyond a configurable threshold.

```bash
# Capture baseline before deploy
$ argus profile 12345 --duration=30 --save=before.json

# Capture after deploy
$ argus profile 12345 --duration=30 --save=after.json

# Gate: fail if any method grew >= 10 percentage points
$ argus profile-gate before.json after.json
```

```
╭─ Profile Gate ── before ── after ────────────────────────────────────────────╮
│  before: before.json                                                         │
│  after:  after.json                                                          │
│  threshold: 10.0pp                                                           │
│                                                                              │
│  #    method                                       before%   after%  Δ samp  Δ % │
│  ──  ─────────────────────────────────────────────  ───────  ──────  ──────  ──── │
│  1   com.example.App.processRequest                  12.3%   24.1%  +2,840  +11.8% │
│                                                                              │
│  2 regressions, 1 improvements; gate: FAIL (threshold=10.0%)                │
╰──────────────────────────────────────────────────────────────────────────────╯
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--threshold=PCT` | 10.0 | Fail if any method's CPU share grows by >= PCT percentage points |
| `--threshold-samples=N` | 0 | Ignore changes with an absolute sample delta below N |
| `--top=N` | 20 | Show top N regressions in output |
| `--format=json` | — | Machine-readable JSON output |
| `--annotate=github` | — | Emit `::error::` / `::warning::` annotations for GitHub Actions |
| `--max-regressions=N` | — | Fail if more than N methods regress by any amount |
| `--baseline-only` | — | Print the report but always exit 0 (dry-run mode) |

**Exit codes:**

| Code | Meaning |
|------|---------|
| `0` | No regressions exceed the threshold |
| `1` | At least one method exceeds the threshold |
| `2` | Usage or I/O error (missing file, bad path) |

**JSON output shape:**

```json
{
  "status": "fail",
  "threshold": 10.0,
  "before": "before.json",
  "after": "after.json",
  "regressions": [
    {"method": "com.example.App.processRequest", "beforePct": 12.30, "afterPct": 24.10, "deltaPct": 11.80, "deltaSamples": 2840}
  ],
  "improvements": [],
  "newMethods": [],
  "goneMethods": [],
  "verdict": {"failed": 1, "reason": "1 regression(s) exceed threshold (10.0pp)"}
}
```

**GitHub Actions integration:**

```yaml
- name: Profile regression gate
  run: |
    argus profile $PID --duration=30 --save=after.json
    argus profile-gate baseline.json after.json \
      --threshold=5 \
      --annotate=github
```

---

## argus flame \<pid\>

One-shot flame graph generation. Profiles for N seconds, generates interactive HTML, and opens in browser.

```bash
$ argus flame 12345                      # 10s CPU, open browser
$ argus flame 12345 --duration 30        # 30 seconds
$ argus flame 12345 --type alloc         # allocation flame graph
$ argus flame 12345 --type lock          # lock contention flame graph
$ argus flame 12345 --output flame.html  # save to specific file
$ argus flame 12345 --no-open            # don't open browser
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--duration N` | 10 | Profiling duration in seconds |
| `--type` | cpu | Profile type: `cpu`, `alloc`, `lock`, `wall`, PMU counters, or method-trace events |
| `--output` | `/tmp/argus-flame-<pid>.<ext>` | Output file path |
| `--output-format=FMT` | `flamegraph` | Output format — same values as `argus profile`: `flamegraph`, `collapsed`, `jfr`, `tree`, `text`, `flat`, `traces`, `otlp` |
| `--no-open` | false | Skip browser auto-open (auto-skipped for non-HTML formats) |

```bash
# Write JFR file instead of HTML
$ argus flame 12345 --output-format=jfr --output=profile.jfr

# Folded stacks for flamegraph.pl
$ argus flame 12345 --output-format=collapsed --output=stacks.txt
```

Uses async-profiler under the hood. Auto-downloads if not present.

---

## argus jfr \<pid\> \<subcommand\>

Controls JDK Flight Recorder — start, stop, check, and dump recordings.

```bash
$ argus jfr 39113 check
```

```
 argus jfr check
 Controls JDK Flight Recorder: start, stop, check, and dump recordings.

╭─ Flight Recorder ── pid:39113 ── cmd:check ──────────────────────────────────╮
│ Status:   ok                                                                 │
│                                                                              │
│ 39113:                                                                       │
│                                                                              │
│ 39113:                                                                       │
│ No available recordings.                                                     │
│ Use jcmd 39113 JFR.start to start a recording.                               │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Subcommands:
- `argus jfr <pid> start` — Start recording (options: `--duration=60`, `--file=output.jfr`)
- `argus jfr <pid> stop` — Stop active recording
- `argus jfr <pid> check` — Show recording status
- `argus jfr <pid> dump` — Dump recording to file (option: `--file=dump.jfr`)

---

## argus jfranalyze \<file.jfr\>

Analyzes a JFR recording file and produces a comprehensive summary covering GC, CPU load, hot methods, top allocating classes, lock contention, exceptions, and I/O.

```bash
$ argus jfranalyze recording.jfr
$ argus jfranalyze recording.jfr --format=json
```

```
╭─ JFR Analysis ── file:recording.jfr ────────────────────────────────────────╮
│                                                                              │
│ ─────────────────────────────────────────────────────────────────────────── │
│   Overview                                                                   │
│                                                                              │
│   Duration           2m 34s                                                  │
│   Total Events       1,482,330                                               │
│                                                                              │
│ ─────────────────────────────────────────────────────────────────────────── │
│   Garbage Collection                                                         │
│                                                                              │
│   GC Events          42                                                      │
│   Total Pause        830 ms                                                  │
│   Max Pause          210 ms                                                  │
│   GC Overhead        0.54%                                                   │
│                                                                              │
│ ─────────────────────────────────────────────────────────────────────────── │
│   Hot Methods (CPU Samples)                                                  │
│                                                                              │
│     Samples       %   Method                                                 │
│       4,210   38.1%   com.example.App.handleRequest                          │
│       1,820   16.5%   com.example.db.Query.execute                           │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--format=json` — JSON output with GC, CPU, hot methods, allocations, contention, and I/O sections

---

## argus slowlog \<pid\>

Real-time slow method detection via JFR streaming. Streams method-level events (lock contention, file I/O, socket I/O, thread sleep) that exceed a configurable threshold duration. Runs until Ctrl-C or `--duration` expires.

```bash
$ argus slowlog 12345
$ argus slowlog 12345 --threshold=200
$ argus slowlog 12345 --filter=com.example.*
$ argus slowlog 12345 --duration=60
```

```
  argus slowlog
  Monitoring PID 12345 | threshold: 100ms | filter: com.example.*
  Press Ctrl+C to stop

  [14:32:01]  com.example.db.Query.execute                        320ms  ⚠ SQL?
  [14:32:03]  com.example.http.Client.send                        145ms  ⚠ Network
  [14:32:07]  com.example.OrderService.processPayment             512ms  ⚠ Lock
```

Options:
- `--threshold=N` — Minimum duration in ms to report (default: 100)
- `--filter=PATTERN` — Glob pattern to filter by class name (e.g. `com.example.*`)
- `--duration=N` — Stop after N seconds (default: run until Ctrl-C)
- `--format=json` — JSON output (one event per line)

---

## argus trace \<pid\> \<class.method\>

Traces method execution in a running JVM by collecting rapid thread dumps (10 samples/sec) for the specified duration, then aggregates stack frames into a call tree. Shows estimated time-on-CPU and call frequency per frame.

```bash
$ argus trace 12345 com.example.OrderService.createOrder
$ argus trace 12345 com.example.OrderService.createOrder --duration=20
```

```
╭─ Trace ── pid:12345 ── 10s ── 100 samples ───────────────────────────────────╮
│                                                                              │
│   com.example.OrderService.createOrder                       840.0ms (84%)  │
│     ├── com.example.db.OrderRepository.save            640.0ms (64%)        │
│     │   └── com.example.db.JdbcTemplate.execute        580.0ms (58%)        │
│     └── com.example.PaymentService.charge              200.0ms (20%)        │
│                                                                              │
│   100 samples, 84 hits for com.example.OrderService.createOrder             │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--duration=N` — Duration in seconds (default: 10)

---

## argus benchmark \<pid\> \<class.method\>

Lightweight sampling-based benchmark for a specific method in a running JVM. Uses JFR recording combined with periodic thread dump sampling to estimate throughput, GC overhead, and allocation rate during the measurement window.

```bash
$ argus benchmark 12345 com.example.Serializer.serialize
$ argus benchmark 12345 com.example.Serializer.serialize --iterations=1000 --warmup=100
$ argus benchmark 12345 com.example.Serializer.serialize --duration=60
```

```
╭─ Benchmark ── pid:12345 ── 30s ──────────────────────────────────────────────╮
│                                                                              │
│   Target               com.example.Serializer.serialize                      │
│   Duration             30s, samples: 300                                     │
│                                                                              │
│   Throughput           ~12,400 ops/s                                         │
│   (method seen in 41% of samples)                                            │
│                                                                              │
│   GC                                                                         │
│   (GC stats unavailable — JFR recording did not capture GC events)           │
│                                                                              │
│   Allocation                                                                 │
│   (allocation stats unavailable)                                             │
│                                                                              │
│   Note: throughput estimate is sampling-based, not a precise benchmark.      │
│   Note: for accurate micro-benchmarks use JMH.                               │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--iterations=N` — Approximate iteration count (maps to duration; 10 iters ≈ 1s)
- `--warmup=N` — Warmup iteration count before measurement (default: 50)
- `--duration=N` — Measurement duration in seconds (default: 30)

---

[Back to all commands](../cli-commands.md)
