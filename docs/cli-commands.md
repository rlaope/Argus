# Argus CLI Command Reference

Complete reference for all 51 Argus CLI commands with usage examples and actual output.

## Global Options

```
--source=auto|agent|jdk   Data source (default: auto)
--no-color                Disable ANSI colors
--lang=en|ko|ja|zh        Output language
--format=table|json       Output format (default: table)
--host HOST               Agent host (default: localhost)
--port PORT               Agent port (default: 9202)
--help, -h                Show help
--version, -v             Show version
```

**Locale resolution order:** `--lang=<code>` flag > `$LC_ALL` environment variable > `$LANG` environment variable > `~/.argus/config.properties` (set by `argus init`) > `en` fallback.

---

## argus ps

Lists all running JVM processes on the host.

```bash
$ argus ps
```

```
 argus ps
 Lists all running Java Virtual Machine processes on this host.

╭─ JVM Processes ──────────────────────────────────────────────────────────────╮
│                                                                              │
│ PID       Main Class                                  Arguments              │
│ ────────  ──────────────────────────────────────────  ────────────────────── │
│ 56900     /Users/heemangkim/.argus/argus-cli.jar      ps                     │
│ 39113     org.gradle.launcher.daemon.bootstrap.Grad…  8.14                   │
│ 617       com.intellij.idea.Main                                             │
│ 37278     org.gradle.launcher.daemon.bootstrap.Grad…  8.14                   │
│                                                                              │
│ 4 process(es) found                                                          │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus histo \<pid\>

Shows heap object histogram — count and size of each class in memory.

```bash
$ argus histo 39113 --top 5
```

```
 argus histo
 Analyzes heap memory objects. Count = live instances, Size = total bytes occupied.

╭─ Heap Histogram ── pid:39113 ── source:auto ─────────────────────────────────╮
│                                                                              │
│    #  Class                                                  Count      Size │
│ ────  ────────────────────────────────────────────────  ──────────  ──────── │
│    1  byte[]                                               111,050       16M │
│    2  java.lang.String                                     107,249        2M │
│    3  java.lang.Class                                       18,914        2M │
│    4  ConcurrentHashMap.Node                                62,917        2M │
│    5  java.lang.Object[]                                    25,496        1M │
│                                                                              │
│ Total: 718.6K objects · 40M                                                  │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--top N` — Show top N classes (default: 20)
- `--source=jdk|agent` — Force data source
- `--format=json` — JSON output

---

## argus threads \<pid\>

Summarizes thread states with distribution bars and deadlock detection.

```bash
$ argus threads 39113
```

```
 argus threads
 Summarizes thread states. BLOCKED threads may indicate lock contention.

╭─ Thread Summary ── pid:39113 ── source:auto ─────────────────────────────────╮
│                                                                              │
│ Total: 30    Virtual: 0    Platform: 30                                      │
│                                                                              │
│ RUNNABLE        ██████░░░░░░░░░░    11  ( 37%)                               │
│ WAITING         ███████░░░░░░░░░    14  ( 47%)                               │
│ TIMED_WAITING   ███░░░░░░░░░░░░░     5  ( 17%)                               │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus gc \<pid\>

Shows GC statistics including heap usage, pause time, and collector info.

```bash
$ argus gc 39113
```

```
 argus gc
 Shows garbage collection activity. High overhead (>5%) may impact application performance.

╭─ GC Statistics ── pid:39113 ── source:auto ──────────────────────────────────╮
│                                                                              │
│ Heap     [██░░░░░░░░░░░░░░]  40M / 256M  (16%)                               │
│ Overhead [░░░░░░░░░░░░░░░░]  0.0%                                            │
│                                                                              │
│ Total Events: 0    Pause Time: 0ms                                           │
│                                                                              │
│ Collectors:                                                                  │
│   g1 gc                      0 events    0ms                                 │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus gcutil \<pid\>

Shows GC generation utilization like `jstat -gcutil` with progress bars.

```bash
$ argus gcutil 39113
```

```
 argus gcutil
 Monitors GC generation utilization. S0/S1 = Survivor, E = Eden, O = Old, M = Metaspace, CCS = Compressed Class.

╭─ GC Utilization ── pid:39113 ── source:auto ─────────────────────────────────╮
│                                                                              │
│ S0      S1      Eden    Old     Meta    CCS     YGC    FGC    GCT            │
│ ──────────────────────────────────────────────────────────────────────       │
│ 0.0%    0.0%    0.0%    41.8%   96.5%   87.1%   18     2      10.000         │
│                                                                              │
│   S0    [░░░░░░░░░░░░░░░░░░░░]    0.0%                                       │
│   S1    [░░░░░░░░░░░░░░░░░░░░]    0.0%                                       │
│   Eden  [░░░░░░░░░░░░░░░░░░░░]    0.0%                                       │
│   Old   [████████░░░░░░░░░░░░]   41.8%                                       │
│   Meta  [███████████████████░]   96.5%                                       │
│   CCS   [█████████████████░░░]   87.1%                                       │
│                                                                              │
│ YGC: 18 (0.163s)    FGC: 2 (0.215s)    Total: 10.000s                        │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--watch N` — Continuous monitoring every N seconds

---

## argus heap \<pid\>

Shows detailed heap memory usage with exact byte counts, free memory, GC summary, and per-space breakdown.

```bash
$ argus heap 39113
```

```
 argus heap
 Shows heap memory regions. High usage in Old Gen may indicate memory pressure.

╭─ Heap Memory ── pid:39113 ── source:auto ────────────────────────────────────╮
│                                                                              │
│ Used     [███░░░░░░░░░░░░░]  40M / 256M  (16%)                               │
│                                                                              │
│   Used:                40M      (41,976,832 bytes)                           │
│   Committed:          256M     (268,435,456 bytes)                           │
│   Max:                256M            (15.6% used)                           │
│   Free:               216M                                                   │
│                                                                              │
│   Heap Spaces                                                                │
│   class space   [█████████░]     11M /    12M  ( 87%)                        │
│   Metaspace     [██████████]     80M /    83M  ( 97%) ⚠                      │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus sysprops \<pid\>

Displays JVM system properties with optional filter.

```bash
$ argus sysprops 39113 --filter=java.version
```

```
 argus sysprops
 Displays JVM system properties (-D flags and runtime defaults). Use --filter to search.

╭─ System Properties ── pid:39113 ── source:jdk ───────────────────────────────╮
│                                                                              │
│ Key                                     Value                                │
│ ──────────────────────────────────────────────────────────────────────       │
│ java.version                            21.0.9                               │
│ java.version.date                       2025-10-21                           │
│                                                                              │
│ Total: 55 properties  filter: "java.version"  shown: 2                       │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--filter=<pattern>` — Case-insensitive search on key or value

---

## argus vmflag \<pid\>

Shows non-default VM flags with optional filter. Supports runtime flag modification.

```bash
$ argus vmflag 39113 --filter=Heap
```

```
 argus vmflag
 Shows all non-default VM flags. Use --set to modify manageable flags at runtime.

╭─ VM Flags ── pid:39113 ── source:jdk ────────────────────────────────────────╮
│                                                                              │
│ Flag                                        Value                            │
│ ──────────────────────────────────────────────────────────────────────       │
│ G1HeapRegionSize                            1048576                          │
│ HeapDumpOnOutOfMemoryError                  true                             │
│ InitialHeapSize                             268435456                        │
│ MaxHeapSize                                 536870912                        │
│ MinHeapDeltaBytes                           1048576                          │
│ MinHeapSize                                 268435456                        │
│ NonNMethodCodeHeapSize                      5839564                          │
│ NonProfiledCodeHeapSize                     122909338                        │
│ ProfiledCodeHeapSize                        122909338                        │
│ SoftMaxHeapSize                             536870912                        │
│                                                                              │
│ Total: 28 flags  filter: "Heap"  shown: 10                                   │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--filter=<pattern>` — Search flag names
- `--set FlagName=value` — Modify a manageable flag at runtime
- `--set +FlagName` / `--set -FlagName` — Toggle boolean flags

---

## argus nmt \<pid\>

Shows native memory tracking by category. Requires the target JVM to be started with `-XX:NativeMemoryTracking=summary`.

```bash
$ argus nmt 39113
```

```
 argus nmt
 Shows native memory usage by category. Requires -XX:NativeMemoryTracking=summary on target JVM.

╭─ Native Memory ── pid:39113 ── source:jdk ───────────────────────────────────╮
│                                                                              │
│ NMT not enabled on this JVM.                                                 │
│ Start the JVM with: -XX:NativeMemoryTracking=summary                         │
│                                                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

To enable NMT:
```bash
java -XX:NativeMemoryTracking=summary -jar your-app.jar
```

---

## argus classloader \<pid\>

Shows the class loader hierarchy and loaded class counts per loader.

```bash
$ argus classloader 39113
```

```
 argus classloader
 Shows class loader hierarchy and loaded class counts per loader.

╭─ Class Loaders ── pid:39113 ── source:auto ──────────────────────────────────╮
│ <bootstrap>                                                                  │
│ reflect.DelegatingClassLoader                                                │
│ ClassLoaders.PlatformClassLoader                                             │
│ ClassLoaders.AppClassLoader                                                  │
│ classloader.VisitableURLClassLoader                                          │
│ classloader.VisitableURLClassLoader                                          │
│ reflect.DelegatingClassLoader                                                │
│ initialization.MixInLegacyTypesClassLoader                                   │
│ classloader.FilteringClassLoader                                             │
│ classloader.VisitableURLClassLoader                                          │
│ classloader.FilteringClassLoader                                             │
│ classloader.CachingClassLoader                                               │
│ classloader.VisitableURLClassLoader                                          │
│ VisitableURLClassLoader.InstrumentingVisitableURLClassLoader                 │
│ classloader.VisitableURLClassLoader                                          │
│ reflect.DelegatingClassLoader                                                │
│                                                                              │
│ Total loaded classes: 0                                                      │
╰──────────────────────────────────────────────────────────────────────────────╯
```

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

## argus info \<pid\>

Displays JVM runtime information, version, vendor, uptime, and VM flags.

```bash
$ argus info 39113
```

```
 argus info
 Displays JVM runtime details and configuration flags.

╭─ JVM Information ── pid:39113 ───────────────────────────────────────────────╮
│                                                                              │
│ VM Name     39113:                                                           │
│ Version     OpenJDK 64-Bit Server VM version 21.0.9                          │
│ Vendor      Homebrew                                                         │
│ Uptime      57m 3s                                                           │
│ PID         39113                                                            │
│                                                                              │
│ VM Flags:                                                                    │
│   -XX:CICompilerCount=4                                                      │
│   -XX:CompressedClassSpaceSize=335544320                                     │
│   -XX:ConcGCThreads=2                                                        │
│   -XX:G1ConcRefinementThreads=9                                              │
│   -XX:G1EagerReclaimRemSetThreshold=12                                       │
│   -XX:G1HeapRegionSize=1048576                                               │
│   -XX:G1RemSetArrayOfCardsEntries=12                                         │
╰──────────────────────────────────────────────────────────────────────────────╯
```

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

## argus diff \<pid\> [interval]

Compares two heap snapshots to detect memory growth — useful for memory leak investigation.

```bash
$ argus diff 39113 5 --top 5
```

Takes a first snapshot, waits for the specified interval (default 10s), takes a second snapshot, and shows classes that grew.

Options:
- Second argument: interval in seconds (default: 10)
- `--top N` — Show top N growing classes (default: 20)

---

## argus report \<pid\>

Comprehensive single-view diagnostic report collecting all available JVM metrics.

```bash
$ argus report 39113
```

```
 argus report
 Comprehensive JVM diagnostic report. Collects all available metrics in one view.

╭─ JVM Report ── pid:39113 ── source:auto ─────────────────────────────────────╮
│                                                                              │
│   ▸ JVM Info                                                                 │
│     39113: OpenJDK 64-Bit Server VM version 21.0.9    Uptime: 57m 5s         │
│                                                                              │
│   ▸ Memory                                                                   │
│     Heap    [███░░░░░░░░░░░░░]  41M / 256M  (16%)                            │
│     Free    215M                                                             │
│                                                                              │
│   ▸ GC                                                                       │
│     S0: 0%  S1: 0%  Eden: 0%  Old: 42%  Meta: 97%                            │
│                                                                              │
│   ▸ Threads                                                                  │
│     Total: 29    Virtual: 0    Platform: 29                                  │
│     TIMED_WAITING: 5  WAITING: 13  RUNNABLE: 11                              │
│                                                                              │
│   ▸ Top Heap Objects                                                         │
│     1. byte[]                111.0K instances   16M                          │
│     2. java.lang.String      107.2K instances   2M                           │
│     3. java.lang.Class       18.9K instances   2M                            │
│                                                                              │
│   ▸ Warnings                                                                 │
│     ⚠ Metaspace at 97% — near limit                                          │
│                                                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Auto-detected warnings:
- Heap usage > 85%
- Old Gen > 85%
- Metaspace > 90%
- GC overhead > 2%
- BLOCKED threads > 10%

---

## argus top

Real-time terminal monitoring dashboard (requires Argus agent attached to the target JVM).

```bash
$ argus top
$ argus top --host 192.168.1.100 --port 9202 --interval 2
```

Options:
- `--host HOST` — Argus agent host (default: localhost)
- `--port PORT` — Argus agent port (default: 9202)
- `--interval N` — Refresh interval in seconds (default: 1)

---

## argus heapdump \<pid\>

Generates a heap dump (.hprof) file. Shows a Stop-The-World warning before proceeding.

```bash
$ argus heapdump 39113
```

Options:
- `--file=<path>` — Output file path
- `--live` — Live objects only (default)
- `--all` — All objects including garbage
- `--yes` — Skip confirmation prompt

---

## argus deadlock \<pid\>

Detects Java-level deadlocks by analyzing thread dumps. Shows lock chains, held/waiting locks, and stack traces.

```bash
$ argus deadlock 39113
```

```
 argus deadlock
 Analyzes thread dumps to detect Java-level deadlocks. Shows lock chains and stack traces.

╭─ Deadlock Detection ── pid:39113 ── source:auto ─────────────────────────────╮
│                                                                              │
│ ✔ No deadlocks detected                                                      │
╰──────────────────────────────────────────────────────────────────────────────╯
```

When deadlocks are found, shows:
- Thread names and states
- Lock addresses and classes (waiting/holding)
- Top stack frame for each thread

---

## argus env \<pid\>

Shows JVM launch environment: command line, java home, classpath, and VM arguments.

```bash
$ argus env 39113
```

```
 argus env
 Shows JVM launch environment: command line, java home, classpath, and VM arguments.

╭─ JVM Environment ── pid:39113 ── source:auto ────────────────────────────────╮
│                                                                              │
│ Command Line                                                                 │
│   com.example.MyApp --port 8080                                              │
│                                                                              │
│ Java Home:  /usr/lib/jvm/java-21-openjdk                                     │
│ Working Dir:  /opt/myapp                                                     │
│ Classpath                                                                    │
│   /opt/myapp/lib/app.jar                                                     │
│                                                                              │
│ VM Arguments                                                                 │
│   -Xms128m                                                                   │
│   -Xmx2048m                                                                  │
│   -XX:+HeapDumpOnOutOfMemoryError                                            │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus compiler \<pid\>

Shows JIT compiler status and code cache usage. High code cache usage may cause deoptimization.

```bash
$ argus compiler 39113
```

```
 argus compiler
 Shows JIT compiler status and code cache usage. High code cache usage may cause deoptimization.

╭─ JIT Compiler ── pid:39113 ── source:auto ───────────────────────────────────╮
│                                                                              │
│ ✔ Compilation enabled                                                        │
│                                                                              │
│ Code Cache  [█████░░░░░░░░░░░░░░░]  233M / 1.0G  (23%)                      │
│   Max Used: 342M    Free: 791M                                               │
│                                                                              │
│ Blobs: 35.3K    nmethods: 32.9K    adapters: 2.3K                            │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Warnings:
- Code cache usage > 80% — may trigger deoptimization

---

## argus finalizer \<pid\>

Shows finalizer queue status and pending count. High pending count may indicate resource leak.

```bash
$ argus finalizer 39113
```

```
 argus finalizer
 Shows pending finalizers and finalizer thread state. High pending count may indicate resource leak.

╭─ Finalizer Queue ── pid:39113 ── source:auto ────────────────────────────────╮
│                                                                              │
│ ✔ No pending finalizers                                                      │
│                                                                              │
│ Finalizer Thread:  WAITING                                                   │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Warnings:
- Pending count > 100 — possible resource leak or slow finalization

---

## argus stringtable \<pid\>

Shows interned string table statistics. Useful for tuning `-XX:StringTableSize`.

```bash
$ argus stringtable 39113
```

```
 argus stringtable
 Shows interned string table statistics. Useful for tuning -XX:StringTableSize.

╭─ String Table ── pid:39113 ── source:auto ───────────────────────────────────╮
│                                                                              │
│ Category                   Count        Size                                 │
│ ────────────────────────────────────────────                                 │
│ Buckets                     4.3K          0B                                 │
│ Entries                    16.7K          0B                                 │
│ Literals                  242.8K         19M                                 │
│ ────────────────────────────────────────────                                 │
│ Total                                    24M                                 │
│                                                                              │
│ Avg literal size: 82.0 bytes                                                 │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus pool \<pid\>

Groups threads by pool name and shows state distribution per pool.

```bash
$ argus pool 39113
```

```
 argus pool
 Groups threads by pool name and shows state distribution per pool.

╭─ Thread Pools ── pid:39113 ── source:auto ───────────────────────────────────╮
│                                                                              │
│ Threads: 83    Pools: 12                                                     │
│                                                                              │
│ Pool Name                      Count  State                                  │
│ ──────────────────────────────────────────────────────────────────────────   │
│ DefaultDispatcher-worker          15  TWAIT:15                               │
│ (JVM Internal)                     6  WAIT:1 RUN:4 TWAIT:1                   │
│ ForkJoinPool.commonPool            4  WAIT:3 RUN:1                           │
│ pool-1                             3  RUN:1 WAIT:2                           │
│ (JIT Compiler)                     2  RUN:2                                  │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--top N` — Show top N pools

---

## argus gccause \<pid\>

Shows last and current GC cause alongside generation utilization stats. Root-cause analysis for unexpected GC pauses.

```bash
$ argus gccause 39113
```

```
 argus gccause
 Shows last and current GC cause alongside generation utilization.

╭─ GC Cause ── pid:39113 ── source:auto ───────────────────────────────────────╮
│                                                                              │
│ Last GC Cause:     Humongous Allocation                                      │
│ Current GC Cause:  No GC                                                     │
│                                                                              │
│   Eden  [█████████████░░░░░░░]   62.8%                                       │
│   Old   [███████████████████░]   94.5%                                       │
│   Meta  [████████████████████]   98.8%                                       │
│                                                                              │
│ YGC: 236 (2.380s)    FGC: 6 (1.105s)    GCT: 7.897s                         │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus metaspace \<pid\>

Shows detailed metaspace breakdown by space type (Class/Non-Class). Requires Java 16+.

```bash
$ argus metaspace 39113
```

```
 argus metaspace
 Shows metaspace usage breakdown by space type (Class/Non-Class).

╭─ Metaspace ── pid:39113 ── source:auto ──────────────────────────────────────╮
│                                                                              │
│ Used  [████████████████████]  548M / 555M  (99%)                             │
│   Reserved: 1.5G                                                             │
│                                                                              │
│ Space                 Used   Committed    Reserved                           │
│ ──────────────────────────────────────────────────                           │
│ Non-Class             478M        481M        512M                           │
│ Class                  70M         73M        1.0G                           │
│                                                                              │
│ ⚠ Metaspace usage high — possible classloader leak                           │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus dynlibs \<pid\>

Lists native libraries loaded in the JVM process, grouped by category (JDK/App/System).

```bash
$ argus dynlibs 39113
```

Options:
- `--filter=<pattern>` — Filter library paths

---

## argus vmset \<pid\> Flag=value

Sets a manageable VM flag on a live JVM without restart. Requires confirmation unless `--yes` is passed.

```bash
$ argus vmset 39113 HeapDumpOnOutOfMemoryError=true --yes
```

Options:
- `--yes` — Skip confirmation prompt

---

## argus vmlog \<pid\> [options]

Controls JVM unified logging (GC, JIT, safepoint) on a running JVM without restart.

```bash
# Show current log configuration
$ argus vmlog 39113

# Enable GC logging to stdout
$ argus vmlog 39113 what=gc=info output=#0

# Enable JIT compilation logging
$ argus vmlog 39113 what=jit+compilation=debug
```

---

## argus jmx \<pid\> [subcommand]

Controls JMX Management Agent. Enables VisualVM/JConsole to connect to a running JVM.

```bash
# Check status
$ argus jmx 39113

# Start local JMX (same-host only)
$ argus jmx 39113 start-local

# Start remote JMX
$ argus jmx 39113 start --port=9999 --no-auth --no-ssl

# Stop JMX agent
$ argus jmx 39113 stop
```

Subcommands:
- `status` (default) — Show JMX agent status
- `start-local` — Start local-only JMX agent
- `start` — Start remote JMX agent (opens network port)
- `stop` — Stop JMX agent

---

## argus classstat \<pid\>

Shows class loading throughput: loaded/unloaded counts, bytes, and time spent.

```bash
$ argus classstat 39113
```

```
 argus classstat
 Shows class loading throughput: loaded/unloaded counts, bytes consumed, and time spent.

╭─ Class Loading ── pid:39113 ── source:auto ──────────────────────────────────╮
│                                                                              │
│ Loaded:    112.0K  (220722.8 KB)                                             │
│ Unloaded:  3.4K  (4372.5 KB)                                                │
│                                                                              │
│ Net:       108.7K                                                            │
│ Time:      20.510s                                                           │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus gcnew \<pid\>

Shows young gen detail: survivor spaces, tenuring threshold, eden usage. Useful for tuning MaxTenuringThreshold.

```bash
$ argus gcnew 39113
```

```
 argus gcnew
 Shows young gen detail: survivor spaces, tenuring threshold, eden usage.

╭─ Young Generation ── pid:39113 ── source:auto ───────────────────────────────╮
│                                                                              │
│   Eden  [█░░░░░░░░░░░░░░░]  11M / 350M  (3%)                                │
│   S0    [░░░░░░░░░░░░░░░░]  0B / 0B  (0%)                                   │
│   S1    [░░░░░░░░░░░░░░░░]  0B / 0B  (0%)                                   │
│                                                                              │
│ Tenuring Threshold:  15 / 15                                                 │
│ Desired Survivor:        23M                                                 │
│                                                                              │
│ YGC: 328  (3.104s)                                                           │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus symboltable \<pid\>

Shows JVM symbol table statistics (class/method/field names). Complements stringtable.

```bash
$ argus symboltable 39113
```

```
 argus symboltable
 Shows JVM symbol table statistics (class/method/field names). Complements stringtable.

╭─ Symbol Table ── pid:39113 ── source:auto ───────────────────────────────────╮
│                                                                              │
│ Category                   Count        Size                                 │
│ ────────────────────────────────────────────                                 │
│ Buckets                    20.4K          0B                                 │
│ Entries                    77.5K          0B                                 │
│ Literals                    1.3M         95M                                 │
│ ────────────────────────────────────────────                                 │
│ Total                                   117M                                 │
│                                                                              │
│ Avg literal size: 76.0 bytes                                                 │
╰──────────────────────────────────────────────────────────────────────────────╯
```

---

## argus init

First-time setup wizard. Prompts you to select the default output language (en/ko/ja/zh) and saves the preference to `~/.argus/config.properties`.

```bash
$ argus init
```

```
 argus init
 First-time setup: select your preferred output language.

? Select language [en/ko/ja/zh]: ko
✔ Language set to: ko
  Config saved to: /Users/you/.argus/config.properties
```

After running `argus init`, all subsequent commands use the chosen language by default. Override any time with `--lang=<code>`.

---

## JSON Output

All commands support `--format=json` for scripting and pipeline integration:

```bash
$ argus --format=json gc 39113
```

```json
{"totalEvents":0,"totalPauseMs":0.0,"overheadPercent":0.0,"lastCause":"","heapUsed":56849408,"heapCommitted":268435456,"collectors":[{"name":"g1 gc","count":0,"totalMs":0.0}]}
```

---

## Multi-language Support

Set the output language with `--lang` or configure it with `argus init`:

```bash
$ argus --lang=ko histo 39113 --top 3
```

Supported languages: English (`en`), Korean (`ko`), Japanese (`ja`), Chinese (`zh`)

---

## argus doctor

One-click JVM health diagnosis with actionable recommendations and JVM flag suggestions.

```bash
$ argus doctor
```

Cross-correlates GC, memory, CPU, threads, and buffer metrics to produce severity-rated findings.

**Health checks (11 rules):**

| Rule | What it detects | Thresholds |
|------|----------------|------------|
| GC Overhead | Excessive GC time | warn > 5%, critical > 15% |
| Heap Pressure | Heap/old gen saturation | warn > 75%, critical > 92% |
| Thread Contention | Deadlocks, blocked threads | deadlock = critical, blocked > 5 = warn |
| Metaspace | ClassLoader leaks | warn > 80%, critical > 95% |
| Direct Buffers | NIO buffer leaks | warn > 200MB, critical > 800MB |
| CPU Usage | Process CPU saturation | warn > 70%, critical > 90% |
| Finalizer Queue | Backed-up finalization | warn > 100, critical > 1000 |
| GC Algorithm | Suboptimal GC choice; Generational ZGC hint on JDK 21–23 | Serial on large heap, Full GC frequency; INFO when plain ZGC + JDK 21–23 |
| ZgcSoftMaxBreachRule | ZGC committed heap > SoftMaxHeapSize | WARNING (ZGC only) |
| ZgcCycleOverlapRule | ZGC avg cycle duration > 80% of avg interval | CRITICAL when ≥ 5 cycles (ZGC only) |
| GcPressureRule | High-frequency young-gen GC | warn > 200/min, critical > 500/min |

**Exit codes** for CI/CD: `0` = healthy, `1` = warnings, `2` = critical.

```bash
# CI/CD integration
argus doctor --format=json || alert "JVM unhealthy"
```

### Profile-aware mode

Appends a **Profile Findings** section to the normal health report by running a short CPU profile and analysing the hot-method distribution.

```bash
# Live capture (default 5s)
$ argus doctor 12345 --profile

# Longer capture
$ argus doctor 12345 --profile --profile-duration=15

# Load from a previously saved snapshot
$ argus doctor 12345 --profile=snapshot.json
```

**Options:**

| Option | Description |
|--------|-------------|
| `--profile` | Capture a live CPU profile (default duration: 5s) |
| `--profile=<file>` | Load a saved snapshot instead of capturing live |
| `--profile-duration=N` | Override the live capture duration in seconds (default: 5) |

**Profile rules (5):**

| Rule | Severity | Fires when |
|------|----------|------------|
| `HotWaitRule` | WARNING | Blocking/wait methods (`Object.wait`, `Thread.sleep`, `LockSupport.park`) exceed 50% of samples |
| `HotJitBarrierRule` | WARNING | JIT internals (`c2_runtime`, `OptoRuntime`, `CompileBroker`) exceed 10% of samples |
| `HotGcBarrierRule` | WARNING | GC code paths (`G1`, `ZHeap`, `GenCollect`, etc.) exceed 15% of samples |
| `HotAllocationLeafRule` | INFO | Top-3 allocation hot leaves surfaced (alloc-type snapshots only) |
| `DominantMethodRule` | CRITICAL | A single method exceeds 60% of samples (tight loop / runaway computation) |

---

## argus gclog

Analyze GC log files with pause distribution, cause breakdown, and tuning recommendations. Free alternative to GCEasy.io.

```bash
$ argus gclog /var/log/gc.log
$ argus gclog gc.log --suggest-flags    # output only JVM flags
$ argus gclog gc.log --format=json      # CI/CD integration
```

**Supported formats:** JDK 9+ unified logging (`-Xlog:gc*`) and JDK 8 legacy (`-XX:+PrintGCDetails`). Auto-detected.

**ZGC allocation stalls:** When the log contains `Allocation Stall (thread) Xms` lines (produced by ZGC under allocation pressure), `argus gclog` parses them and renders an **Allocation Stalls (ZGC)** section above the GC Pause Summary, showing stall count, total time, max stall, and the top thread. This section only appears when at least one stall is present. Enable the stall log lines with `-Xlog:gc*:file=gc.log:time,uptime,level,tags`.

**Output sections:**
1. **Allocation Stalls (ZGC)** — stall count, total/max duration, top thread (ZGC only, when stalls present)
2. **Summary** — duration, event count, throughput %
3. **Pause Distribution** — p50, p95, p99, max with ASCII histogram
4. **Heap** — peak usage, avg after GC
5. **Cause Breakdown** — per-cause count/avg/max table
6. **Tuning Recommendations** — severity-rated with specific JVM flags

**Tuning rules (7):**

| Pattern | Recommendation |
|---------|---------------|
| Full GC detected | Increase `-Xmx` |
| Throughput < 95% | Increase heap or switch GC |
| p99 > 200ms | `-XX:MaxGCPauseMillis=200` or ZGC |
| Humongous allocations | `-XX:G1HeapRegionSize=16m` |
| Metaspace GC | `-XX:MaxMetaspaceSize=512m` |
| Allocation failures | `-XX:NewRatio=2` |
| Max pause > 1s | `-XX:+UseZGC` |

---

## argus gcscore

One-page GC Health Score Card. Scores six KPI axes against widely-accepted thresholds and emits a weighted A–F grade plus up to three improvement hints. The short answer to *"does my app need GC tuning right now?"*.

```bash
$ argus gcscore /var/log/gc.log
$ argus gcscore <PID>                   # live 30s JFR capture
$ argus gcscore gc.log --format=json    # CI/CD integration
```

**Axes (weighted):**

| Axis | Pass target | Notes |
|------|-------------|-------|
| Pause p99 | < 200 ms (< 5 ms for ZGC) | Threshold tightened 10× for ZGC |
| Pause tail (max) | < 500 ms (< 10 ms for ZGC) | Threshold tightened 50× for ZGC |
| Throughput | > 95% | Weight raised to 0.30 for ZGC |
| Full GC frequency | 0 / hour | |
| Allocation rate | < 1 GB/s | |
| Promotion ratio | < 20% of allocation | |
| Allocation pressure (ZGC) | < 0.5 cycles/s | ZGC only — added axis |

**ZGC-aware scoring:** When the GC algorithm is detected as ZGC, pause-axis weights are halved (p99: 0.25→0.12, tail: 0.15→0.08) because sub-millisecond pauses are expected by design. The saved weight shifts to throughput (0.30) and a new **Allocation pressure (ZGC)** axis (cycle frequency, weight 0.15). Hints for ZGC failures point to `-Xmx`, `-XX:SoftMaxHeapSize`, and `-XX:ConcGCThreads` rather than `-XX:MaxGCPauseMillis`.

**Grade:** `A` (≥ 90), `B` (≥ 75), `C` (≥ 60), `D` (≥ 40), `F` (< 40). Up to 3 improvement hints selected from a rule base when axes fail. Missing rate data marks axes as N/A and excludes them from the weighted average.

**Lineage:** GCeasy.io score card (1-page KPI summary) + Censum-style rule-based hints.

---

## argus gcwhy

Narrates why the single worst GC pause in a lookback window happened. Picks the top pause event and correlates the preceding events to produce up to three plain-English "why" bullets plus a related-counters block.

```bash
$ argus gcwhy /var/log/gc.log --last=5m
$ argus gcwhy gc.log --last=30s --format=json
```

**Rule engine (ordered):**

| Rule | Triggered when |
|------|----------------|
| Explicit `System.gc()` | Cause contains `System.gc` |
| G1 humongous allocation | Cause contains `humongous` |
| Metaspace pressure | Cause contains `Metadata` |
| Full GC fallback | Full GC + concurrent/evacuation/failure in cause |
| Allocation burst | Target's alloc rate ≥ 2× recent baseline |
| High heap occupancy | Heap-before ≥ 90% of total |
| Outlier pause | Target pause ≥ 3× recent average |

Always emits at least one bullet (neutral fallback when nothing anomalous fires). `--last` accepts `Ns`, `Nm`, `Nh` or a bare number in seconds; default `5m`.

**Lineage:** Censum-style reasoning ("why", not just "what").

---

## argus gcprofile

Short JFR recording focused on allocation events (`jdk.ObjectAllocationInNewTLAB` / `ObjectAllocationOutsideTLAB`). Produces three views:

```bash
$ argus gcprofile 12345                       # default: top stack-frame sites
$ argus gcprofile 12345 --duration=30 --top=20
$ argus gcprofile 12345 --by=class            # top allocated types instead
$ argus gcprofile 12345 --fold=alloc.folded   # folded stacks for flamegraph.pl
$ argus gcprofile 12345 --format=json
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--duration=N` | 30 | Recording duration in seconds |
| `--top=N` | 10 | Show top N rows |
| `--by=site\|class` | `site` | Aggregate by stack frame (default) or allocated object class |
| `--fold=FILE` | off | Write folded stacks in flamegraph.pl format (root-first, leaf-last, merged) |
| `--format=json` | off | JSON output |

**Rendering the flamegraph** (requires `flamegraph.pl` from Brendan Gregg):

```bash
$ argus gcprofile 12345 --duration=30 --fold=alloc.folded
$ flamegraph.pl --colors=mem --title="alloc" alloc.folded > alloc.svg
```

**Lineage:** async-profiler `-e alloc` + JDK Mission Control allocation view.

---

## argus flame

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

## argus watch

Real-time terminal dashboard — htop for JVM. Updates in-place using ANSI escape codes.

```bash
$ argus watch                    # monitor local JVM, 2s refresh
$ argus watch --interval=5       # 5 second refresh
```

**Display:**
- Heap/Old/Metaspace with color progress bars and sparkline history
- CPU usage with processor count
- GC stats with overhead % (green/yellow/red)
- Thread summary: total/runnable/waiting/blocked/deadlocked
- NIO direct buffer usage
- Class loading stats

**Controls:** `q` = quit, `r` = force refresh.

---

## argus suggest

JVM flag optimization based on workload analysis. Auto-detects workload type or accepts `--profile` override.

```bash
$ argus suggest                        # auto-detect workload
$ argus suggest --profile=web          # optimize for web server
$ argus suggest --profile=batch        # optimize for batch processing
$ argus suggest --profile=microservice # optimize for microservice
$ argus suggest --profile=streaming    # optimize for streaming
$ argus suggest --advanced             # include advanced ZGC flags
$ argus suggest --format=json          # JSON output
```

**Workload profiles:**

| Profile | Optimizes for | Key suggestions |
|---------|--------------|----------------|
| `web` | Low latency, consistent response times | G1GC/ZGC, MaxGCPauseMillis |
| `batch` | Maximum throughput | ParallelGC, GC thread tuning |
| `microservice` | Fast startup, low memory | SerialGC for small heaps, CDS |
| `streaming` | Steady allocation rate | G1GC, TLAB sizing |

Includes a copy-paste ready flag summary at the bottom of output.

**ZGC-specific suggestions:** When the current GC is ZGC, `argus suggest` emits the following additional recommendations:

| Suggestion | Condition | Flag |
|-----------|-----------|------|
| Set SoftMaxHeapSize | Heap usage < 80% of `-Xmx` | `-XX:SoftMaxHeapSize=<N>g` |
| Raise ZAllocationSpikeTolerance | `--advanced` flag **or** detected spike behavior (GC overhead > 3% and max recent pause > 10 ms) | `-XX:ZAllocationSpikeTolerance=5.0` |
| Enable ZUncommit | Committed heap > 4 GB **and** uptime > 1 hour | `-XX:+ZUncommit -XX:ZUncommitDelay=300` |

`-XX:MaxGCPauseMillis` is suppressed for ZGC (it is a no-op on that collector).

**`--advanced` flag:** Gates the `ZAllocationSpikeTolerance` suggestion so it only appears when explicitly requested or when spike behavior is auto-detected.

### Profile-driven suggestions

Appends a **Profile-Driven Suggestions** section backed by hot-method analysis. Pass `--profile` to capture a live CPU profile, or `--profile=<file>` to load a saved snapshot.

```bash
# Live capture (default 5s)
$ argus suggest 12345 --profile

# Longer capture
$ argus suggest 12345 --profile --profile-duration=15

# From saved snapshot
$ argus suggest 12345 --profile=snapshot.json
```

**Options:**

| Option | Description |
|--------|-------------|
| `--profile` | Capture a live CPU profile (default duration: 5s) |
| `--profile=<file>` | Load a saved snapshot instead of capturing live |
| `--profile-duration=N` | Override the live capture duration in seconds (default: 5) |

**Profile suggestion rules (6):**

| Rule | Confidence | Flag suggested | Fires when |
|------|-----------|----------------|------------|
| `StringDeduplicationSuggestion` | MED | `-XX:+UseStringDeduplication` | `String.intern` / HashMap hash activity >= 5% of samples |
| `TieredCompilationSuggestion` | MED | `-XX:+TieredCompilation -XX:TieredStopAtLevel=4` | JIT compiler CPU (`c2_compile`, `OptoRuntime`) >= 5% of samples |
| `EscapeAnalysisSuggestion` | LOW | `-XX:+DoEscapeAnalysis -XX:+EliminateAllocations` | Short-lived wrapper objects (`Long`, `Integer`, `ArrayList$Itr`) >= 2% of alloc samples |
| `YoungGenSizingSuggestion` | LOW | `-XX:NewRatio=2` | Alloc snapshot with >= 10,000 samples (high allocation pressure) |
| `LockContentionHint` | HIGH | _(no flag — structural hint)_ | Single monitor >= 30% of lock-type snapshot samples |
| `RuntimeWaitHint` | HIGH | _(no flag — structural hint)_ | Wait/park methods >= 60% of all samples |

When a profile rule fires for the same JVM flag area as a workload suggestion, the workload suggestion is marked `(superseded by profile evidence)` in the output.

---

## Monitoring

### argus alert \<target\>

Monitors an Argus instance via its `/prometheus` endpoint and sends webhook notifications when metric thresholds are breached.

```bash
$ argus alert localhost:9202 --gc-overhead=10 --webhook=https://hooks.slack.com/xxx
$ argus alert localhost:9202 --leak --interval=60
$ argus alert --config=alerts.yml
```

Options:
- `--gc-overhead=N` — Alert when GC overhead exceeds N%
- `--leak` — Alert when memory leak is suspected (`argus_gc_leak_suspected >= 1`)
- `--webhook=URL` — Webhook URL for notifications
- `--interval=N` — Poll interval in seconds (default: 30)
- `--config=FILE` — Load rules from a config file

Config file format (`key=value`, no YAML library required):

```
target=localhost:9202
interval=30
rule.gc-overhead.metric=argus_gc_overhead_ratio
rule.gc-overhead.threshold=0.10
rule.gc-overhead.severity=warning
webhook=https://hooks.slack.com/xxx
```

---

### argus cluster \<subcommand\> \<targets...\>

Discovers multiple Argus-enabled JVM instances and shows aggregated health metrics. Fetches `/prometheus` endpoints in parallel.

```bash
$ argus cluster scan localhost:9202 localhost:9203 localhost:9204
$ argus cluster scan --file=targets.txt
$ argus cluster health localhost:9202 localhost:9203
```

```
╭─ Cluster Health ── 3 instances ──────────────────────────────────────────────╮
│                                                                              │
│   Instance               Heap%   GC OH  CPU    Leak?   VThreads  Status     │
│                                                                              │
│   localhost:9202         42%     0.1%   12%    No      0         ✓ Healthy  │
│   localhost:9203         78%     3.2%   55%    No      0         ⚠ Warning  │
│   localhost:9204         N/A     N/A    N/A    N/A     N/A       ✗ DOWN     │
│                                                                              │
│   Aggregate              42-78%  0-3%   12-55% 0/3                          │
│   Worst: localhost:9203 — heap pressure                                      │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Subcommands:
- `scan` — Discover and display health of multiple JVM instances
- `health` — Show aggregated health metrics

Options:
- `--file=FILE` — Read `host:port` targets from file (one per line)
- `--format=json` — JSON output

---

### argus perfcounter \<pid\>

Displays JVM internal performance counters via `jcmd PerfCounter.print`. Exposes low-level metrics not available through standard MXBeans: GC invocation counts, compiler time, class loading stats, and internal timers.

```bash
$ argus perfcounter 12345
$ argus perfcounter 12345 --filter=gc
$ argus perfcounter 12345 --filter=sun.gc
```

```
╭─ Performance Counters ── pid:12345 ── filter:gc ─────────────────────────────╮
│                                                                              │
│ sun.gc                                                                       │
│   collector.0.invocations                  18                                │
│   collector.0.time                         163,412                           │
│   collector.1.invocations                  2                                 │
│   collector.1.time                         215,088                           │
│                                                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--filter=<pattern>` — Case-insensitive filter on counter name
- `--format=json` — JSON output grouped by category prefix

---

### argus tui

Launches the interactive terminal UI (k9s-style). Displays a JVM process list first; selecting a process shows the command list for that PID.

```bash
$ argus tui
```

No options. Press `q` to quit, arrow keys to navigate, Enter to select.

---

## Memory & GC

### argus zgc \<pid\>

One-shot ZGC health verdict from a live JFR capture. Pre-checks via JMX that the target JVM is using ZGC, then starts a `JFR.start` recording with `settings=profile`, waits for the capture window, dumps and parses the recording, and prints a structured report with a HEALTHY / WARNING / UNHEALTHY verdict.

```
argus zgc <PID> [--duration=N] [--save=PATH] [--diff=PATH] [--watch[=N]] [--interval=N]
```

```bash
$ argus zgc <PID>
$ argus zgc <PID> --duration=60    # extend capture to 60s (5–120 range)
$ argus zgc <PID> --save=/tmp/zgc-baseline.txt
$ argus zgc <PID> --diff=/tmp/zgc-baseline.txt
$ argus zgc <PID> --watch --interval=60
$ argus zgc <PID> --watch=10 --interval=60
```

**Sample output (UNHEALTHY with allocation hotspots):**

```
ZGC Diagnosis (PID 18420, JDK 21, Non-generational)
════════════════════════════════════════════════════
  Heap         committed 3.8 GiB / soft 4.0 GiB / max 8.0 GiB
  Cycles       0 minor, 14 major (avg interval 2.1s, duration 1.8s)
  STW pauses   Mark Start 0.42ms · Mark End 2.31ms · Relocate Start 0.38ms
  Allocation Stalls
               ✘ 7 stalls in window (max 84.3ms in "http-nio-8080-exec-3")
               Top alloc sources during capture (n=84,321 events)
                1. com.example.service.OrderService.processOrder(OrderService.java:142)  38.2%
                2. java.util.HashMap.resize(HashMap.java:704)                            14.7%
                3. com.example.model.Item.<init>(Item.java:23)                            9.1%
                4. com.example.cache.LocalCache.put(LocalCache.java:88)                   6.4%
                5. com.example.util.JsonMapper.toJson(JsonMapper.java:61)                 4.3%
  SoftMax      ✓ within budget
  Overlap      ✘ consecutive cycles overlap

Verdict: UNHEALTHY  — allocation stalls + cycle overlap.
Recommend:
  • Raise -Xmx (e.g. -Xmx10g)
  • Or raise -XX:SoftMaxHeapSize toward -Xmx
  • Profile allocations: argus profile <PID> --event=alloc
```

The **Top alloc sources** block appears only when stalls are detected and `jdk.ObjectAllocationInNewTLAB` / `jdk.ObjectAllocationOutsideTLAB` events were recorded by the `profile.jfc` settings. The top-5 call sites are ranked by event count. Stack-frame extraction skips `java.`, `jdk.`, `sun.`, and `com.sun.` prefixes and falls back to the first frame when all frames are JDK-internal. When no allocation events are present and stalls were detected, a note is printed instead.

**Verdict table:**

| Verdict | Condition |
|---------|-----------|
| UNHEALTHY | Allocation stalls present **or** consecutive cycles overlap |
| WARNING | Committed heap > SoftMaxHeapSize **or** avg Pause Mark End > 1.0 ms |
| HEALTHY | None of the above |

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--duration=N` | 30 | JFR capture window in seconds (clamped to 5–120) |
| `--save=PATH` | | Capture once, render normally, and persist a baseline to PATH |
| `--diff=PATH` | | Capture once, compare against baseline at PATH, render diff table (suppresses normal verdict) |
| `--watch[=N]` | | Loop indefinitely (`--watch`) or for N iterations (`--watch=N`). Each iteration captures using `--interval`, prints a 1-line summary with delta from the prior iteration. Every 5th iteration prints the full diagnosis table. Ctrl-C prints a final summary and cleans up JFR. |
| `--interval=N` | 30 | JFR capture duration and watch loop period in seconds (clamped to 10–300) |

**Trend tracking:**

Save a baseline during a known-good window:

```bash
argus zgc 12345 --save=/tmp/zgc-baseline.txt
```

After a deploy or config change, diff against the baseline:

```bash
argus zgc 12345 --diff=/tmp/zgc-baseline.txt
```

Sample diff output (REGRESSION rows marked with ✘):

```
Baseline: 2026-05-08T09:00:00Z → Now: 2026-05-08T11:34:21Z  (+2.6 hr)
═══════════════════════════════════════════════════════════════
  Heap committed       3.2 GB → 4.1 GB       +0.9 GB  ✘
  Minor cycles            240 → 241              +1
  Major cycles             20 → 24               +4
  Allocation stalls          0 → 7               +7  ✘
  Pause Mark End         0.31ms → 0.82ms      +0.51ms  ⚠
  SoftMax breached           no → no            none
```

REGRESSION conditions: new stalls (baseline=0 → current>0), committed heap grew past SoftMaxHeapSize, minor:major ratio worsened >50%, Pause Mark End grew >50%, or cycleOverlap newly true.

Continuous watch for 10 iterations at 60-second intervals:

```bash
argus zgc 12345 --watch --interval=60
```

Sample watch output:

```
[10:01:00] ZGC | committed 3.2 GB | cycles 240m/20M | stalls 0 | mark-end 0.31ms
[10:02:01] ZGC | committed 3.3 GB (+0.1 GB) | cycles 243m/20M | stalls 0 | mark-end 0.33ms
[10:03:02] ZGC | committed 3.8 GB (+0.5 GB) ⚠ | cycles 248m/21M | stalls 3 ✘ (max 22ms in "exec-5") | mark-end 0.41ms ⚠
```

**When to use:**

- Use `argus zgc <PID>` as a first-line ZGC health check. It gives a verdict in one command without pre-existing GC logs.
- Use `argus doctor <PID>` afterward for cross-cutting findings (heap, threads, CPU) and the ZGC doctor rules (`ZgcSoftMaxBreachRule`, `ZgcCycleOverlapRule`).
- Use `argus gclog <file>` when you have an existing GC log and want pause histograms, cause breakdowns, and allocation stall counts over a longer window.
- If `argus zgc` reports allocation stalls, check the **Top alloc sources** block in the output before reaching for `argus profile`.

---

### argus buffers \<pid\>

Displays NIO buffer pool statistics (direct and mapped) for a given PID. Essential for diagnosing direct buffer leaks in production.

```bash
$ argus buffers 39113
```

```
 argus buffers
 Shows NIO direct and mapped buffer pool usage.

╭─ NIO Buffers ── pid:39113 ── source:auto ────────────────────────────────────╮
│                                                                              │
│ Pool                       Count      Capacity          Used     Usage       │
│ ─────────────────────────────────────────────────────────────────────────── │
│ direct                        24           12M           12M     98.5%       │
│ mapped                         0             0             0        -        │
│                                                                              │
│ Total                         24           12M           12M                 │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--source=jdk|agent` — Force data source
- `--format=json` — JSON output

---

### argus classleak \<pid\>

Classloader-level metaspace leak attribution. Uses `jcmd VM.classloader_stats` to show class counts and metaspace usage per classloader instance. Supports save/diff for trend detection and live watch mode.

```bash
$ argus classleak 39113
$ argus classleak 39113 --top=20
$ argus classleak 39113 --save=/tmp/cl.json
$ argus classleak 39113 --diff=/tmp/cl.json
$ argus classleak 39113 --watch
```

```
╭─ Classloader Leak ── pid:39113 ── loaders:16 ── top:10 ──────────────────────╮
│                                                                              │
│ Type                                          Classes   ChunkSz   BlockSz   │
│ ─────────────────────────────────────────────────────────────────────────── │
│ ClassLoaders.AppClassLoader                     8,432      48M       47M    │
│ ClassLoaders.PlatformClassLoader                1,201       6M        6M    │
│ <boot class loader>                             1,085       9M        9M    │
│                                                                              │
│ Total: 16 loaders, 10,718 classes, 63M metaspace                             │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--top=N` — Show top N classloaders by class count (default: 10)
- `--save=PATH` — Persist a snapshot to JSON for later diffing
- `--diff=PATH` — Compare current state against a saved snapshot
- `--watch[=N]` — Live mode, refresh every N seconds (default: 5). Controls: `q` quit, `r` refresh

---

### argus gclogdiff \<file1\> \<file2\>

Compares two GC log files and shows metric deltas with color-coded improvements (green) and regressions (red). Exits non-zero on major regressions when used with `--format=json`.

```bash
$ argus gclogdiff before.log after.log
$ argus gclogdiff before.log after.log --format=json
```

```
╭─ GC Log Diff ── before.log ── vs ── after.log ───────────────────────────────╮
│                                                                              │
│ Metric                  before.log       after.log       Delta              │
│ ─────────────────────────────────────────────────────────────────────────── │
│ Throughput %               98.2%            97.1%       -1.1%               │
│ p50 Pause ms                 12ms             14ms       +2 (+17%)           │
│ p99 Pause ms                 85ms            210ms     +125 (+147%)          │
│ Max Pause ms                180ms            450ms     +270 (+150%)          │
│ Full GC Count                   0                2        +2 (+inf%)         │
│                                                                              │
│  ✗ REGRESSION DETECTED  (4 worse, 1 unchanged)                               │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--format=json` — JSON output; exits `1` if any metric regresses by more than 10%

---

### argus gcrun \<pid\>

Triggers `System.gc()` on the target JVM via `jcmd GC.run`. Optionally triggers finalization via `GC.run_finalization`.

```bash
$ argus gcrun 39113
$ argus gcrun 39113 --finalize
```

```
╭─ GC Run ── pid:39113 ────────────────────────────────────────────────────────╮
│                                                                              │
│   ✔ GC triggered successfully                                                │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--finalize` — Also trigger `GC.run_finalization` after GC
- `--format=json` — JSON output

---

### argus heapanalyze \<file.hprof\>

Offline HPROF analysis — MAT alternative. Parses HPROF binary format in streaming mode (handles multi-GB dumps) and produces a class histogram by size, instance counts, and quick insights.

```bash
$ argus heapanalyze app.hprof
$ argus heapanalyze app.hprof --top=30
$ argus heapanalyze app.hprof --sort=count
$ argus heapanalyze app.hprof --format=json
```

```
 argus heapanalyze
 Heap dump analysis — instant answers from HPROF binary

╭─ Heap Analysis ── app.hprof ── 1.2G ─────────────────────────────────────────╮
│                                                                              │
│ Summary                                                                      │
│   Objects: 4,832,100  |  Classes: 12,440  |  Shallow size: 312M             │
│   Instances: 4,801,200 (298M)  |  Arrays: 30,900 (14M)                      │
│   Strings: 1,204,000  |  ID size: 8 bytes  |  Parsed in 3,210ms             │
│                                                                              │
│ Top 20 classes by shallow size                                               │
│                                                                              │
│ #    Instances       Shallow Size   %     Class                              │
│ ─────────────────────────────────────────────────────────────────            │
│ 1    1,204,112           48M       15.4%  byte[]                             │
│ 2      980,001           31M        9.9%  java.lang.String                   │
│                                                                              │
│ Insights                                                                     │
│   ✔ No obvious anomalies detected                                            │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--top=N` — Show top N classes (default: 20)
- `--sort=size|count` — Sort by shallow size (default) or instance count
- `--format=json` — JSON output

Exit codes: `0` = success, `2` = parse error.

---

## Profiling & Tracing

### argus benchmark \<pid\> \<class.method\>

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

### argus ci \[pid\]

CI/CD health gate. Runs doctor checks against a JVM and returns machine-readable output with exit codes for build gating.

```bash
$ argus ci 12345
$ argus ci 12345 --fail-on=warning
$ argus ci 12345 --format=github-annotations
$ argus ci 12345 --format=json
$ argus ci 12345 --format=junit
```

```
PASS: all JVM health checks passed
```

Or on failure:

```
FAIL: 1 critical, 2 warning(s)
  [CRITICAL] Heap Pressure: heap usage at 94%
  [WARNING]  GC Overhead: 8.2%
  [WARNING]  Metaspace: 87%
```

Options:
- `--fail-on=critical|warning` — Severity threshold for non-zero exit (default: `critical`)
- `--format=summary|json|github-annotations|junit` — Output format (default: `summary`)

Exit codes: `0` = pass, `1` = warnings present, `2` = critical findings.

**GitHub Actions integration:**

```yaml
- name: JVM health gate
  run: argus ci $PID --format=github-annotations
```

---

### argus compare \<pid1\> \<pid2\>

Compares two JVM processes side by side, or compares a live JVM against a saved baseline. Shows heap, GC, CPU, threads, loaded classes, and native memory (NMT) deltas.

```bash
$ argus compare 12345 67890
$ argus compare 12345 --save baseline.json
$ argus compare 12345 --load baseline.json
```

```
╭─ JVM Comparison ── pid:12345 ── vs ── pid:67890 ─────────────────────────────╮
│                                                                              │
│   Metric                  pid:12345        pid:67890       Delta             │
│   ──────────────────────────────────────────────────────────────────         │
│   Heap Used                    41M              89M      +48M (+117%)        │
│   Heap Max                    256M             512M     +256M (+100%)        │
│   Heap %                     16.0%            17.4%          +1.4%          │
│                                                                              │
│   GC Overhead                 0.0%             2.1%          +2.1%          │
│   Threads                       30               48            +18 (+60%)   │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--save=PATH` — Save a snapshot of the live JVM to JSON
- `--load=PATH` — Load a saved snapshot to use as the comparison baseline
- `--format=json` — JSON output

---

### argus events \<pid\>

Displays the VM internal event log (safepoints, deoptimizations, GC phases) via `jcmd VM.events`. Safepoint events are highlighted yellow, deoptimizations red, GC events green.

```bash
$ argus events 39113
$ argus events 39113 --format=json
```

```
╭─ VM Events ── pid:39113 ────────────────────────────────────────────────────╮
│                                                                              │
│ Events (250 events):                                                         │
│ ─────────────────────────────────────────────────────────────────────────── │
│   [12.345s] Safepoint "G1CollectForAllocation"                               │
│   [12.312s] GC(18) Pause Young (Normal) (G1 Evacuation Pause) 12ms          │
│   [11.001s] Deoptimization: method=com.example.App.process reason=unstable_if│
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--format=json` — JSON output (raw event text in a single field)

---

### argus explain \<term\>

Explains JVM metrics, GC causes, and flags in plain English. Looks up the term in a built-in knowledge base; falls back to fuzzy matching when the exact term is not found.

```bash
$ argus explain "G1 Evacuation Pause"
$ argus explain -XX:MaxGCPauseMillis
$ argus explain throughput
$ argus explain gc-overhead
```

```
╭─ explain ── "gc-overhead" ───────────────────────────────────────────────────╮
│                                                                              │
│ gc-overhead                                                                  │
│                                                                              │
│ The percentage of elapsed time that the JVM spends performing garbage        │
│ collection. Calculated as total GC pause time divided by total elapsed       │
│ time. Values above 5% indicate the application is spending too much time     │
│ in GC and may need heap tuning. Values above 15% are critical and may        │
│ trigger OutOfMemoryError: GC overhead limit exceeded.                        │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--format=json` — JSON output with `term`, `found`, and `explanation` fields

---

### argus jfranalyze \<file.jfr\>

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

### argus slowlog \<pid\>

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

### argus trace \<pid\> \<class.method\>

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

## Runtime & JVM Internals

### argus compilerqueue \<pid\>

Shows the current JIT compilation queue via `jcmd Compiler.queue`. C2 methods are highlighted red, C1 methods yellow.

```bash
$ argus compilerqueue 39113
$ argus compilerqueue 39113 --format=json
```

```
╭─ JIT Compiler Queue ── pid:39113 ────────────────────────────────────────────╮
│                                                                              │
│ Contents of C2 compile queue:                                                │
│   com.example.App.processRequest (3)                                         │
│   com.example.db.Query.execute (3)                                           │
│                                                                              │
│ Contents of C1 compile queue:                                                │
│   java.util.HashMap.getNode (1)                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--format=json` — JSON output with `queue` array of raw entry strings

---

### argus logger \<pid\>

Views and changes log levels at runtime. Lists all loggers (from JVM unified logging and `java.util.logging`) and optionally sets a log level without restart.

```bash
$ argus logger 39113
$ argus logger 39113 --filter=gc
$ argus logger 39113 --name=gc* --level=debug
```

```
╭─ Loggers ── pid:39113 ─────────────────────────────────────────────────────╮
│                                                                              │
│ Logger                                  Level        Source                  │
│ ─────────────────────────────────────────────────────────────────────────── │
│ ROOT                                    INFO         jul                     │
│ com.example                             DEBUG        jul                     │
│ gc                                      info         vm-log                  │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--filter=<pattern>` — Case-insensitive filter on logger name
- `--name=<name>` — Logger name to change (supports `*` glob via VM.log)
- `--level=<level>` — New log level (`trace`, `debug`, `info`, `warning`, `error`, `off`)
- `--format=json` — JSON output

---

### argus mbean \<pid\>

MBean browser — inspect JVM MBeans via JMX attach. Connects to the local JVM process, then browses domains, lists beans, and reads attributes.

```bash
$ argus mbean 12345
$ argus mbean 12345 --list
$ argus mbean 12345 --domain=java.lang
$ argus mbean 12345 --name="java.lang:type=Memory"
$ argus mbean 12345 --name="java.lang:type=Memory" --attr=HeapMemoryUsage
```

```
╭─ MBean Domains ── 8 domains ────────────────────────────────────────────────╮
│                                                                              │
│   8 total MBeans                                                             │
│                                                                              │
│   com.sun.management  (3 beans)                                              │
│   java.lang           (24 beans)                                             │
│   java.nio            (2 beans)                                              │
│   java.util.logging   (1 beans)                                              │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--list` — List all MBean names
- `--domain=<name>` — List all MBeans in a domain
- `--name=<objectName>` — Show all attributes of a specific MBean
- `--attr=<name>` — Show only the named attribute
- `--format=json` — JSON output

Note: requires JMX attach; automatically starts the local management agent if not running. If attach fails, run `argus jmx <pid> start-local` first.

---

### argus sc \<pid\> \<pattern\>

Searches loaded classes by pattern. Useful for diagnosing classpath conflicts and finding duplicate class loading.

```bash
$ argus sc 12345 "*.UserService"
$ argus sc 12345 "org.slf4j.*"
$ argus sc 12345 "*.UserService" --limit=100
```

```
╭─ Search Classes ── pid:12345 ── pattern:*.UserService ───────────────────────╮
│                                                                              │
│     #    Instances         Bytes  Class                                      │
│ ─────────────────────────────────────────────────────────────────────────── │
│     1          1            480B  com.example.UserService                    │
│     2          1            480B  com.example.legacy.UserService             │
│                                                                              │
│ 2 match(es)                                                                  │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--limit=N` — Maximum results to display (default: 50)
- `--source=jdk|agent` — Force data source
- `--format=json` — JSON output

---

### argus spring \<pid\>

Inspects Spring Boot applications via JMX MBeans. Auto-detects Spring presence and shows application name, version, active profiles, health status, HikariCP connection pool stats, and Tomcat thread pool metrics.

```bash
$ argus spring 12345
$ argus spring 12345 --beans
$ argus spring 12345 --datasource
```

```
╭─ Spring Boot ── pid:12345 ── my-service v3.2.1 ──────────────────────────────╮
│                                                                              │
│   Application          my-service                                            │
│   Version              3.2.1                                                 │
│   Profiles             [prod]                                                │
│   Health               UP                                                    │
│                                                                              │
│   Datasource (HikariCP)                                                      │
│   Active connections   8 / 20 (40%)                                          │
│   Idle connections     12                                                    │
│   Pending threads      0                                                     │
│                                                                              │
│   Tomcat                                                                     │
│   Active threads       5 / 200                                               │
│   Request count        128,432                                               │
│   Error count          14                                                    │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--beans` — Show Spring context bean count and context list
- `--datasource` — Show detailed HikariCP connection pool stats

Note: requires Spring Boot JMX MBeans to be enabled. Automatically starts the local JMX management agent if not running.

---

## Threads

### argus threaddump \<pid\>

Captures and displays a full thread dump equivalent to `jstack`, with state grouping, lock analysis, and structured output. Shows per-thread stack traces, held/waiting lock addresses, and a state distribution summary.

```bash
$ argus threaddump 39113
$ argus threaddump 39113 --raw
$ argus threaddump 39113 --depth=5
$ argus threaddump 39113 --format=json
```

```
╭─ Thread Dump ── pid:39113 ── source:auto ────────────────────────────────────╮
│                                                                              │
│ Total: 30  (daemon: 18)                                                      │
│                                                                              │
│   RUNNABLE           11                                                      │
│   WAITING            14                                                      │
│   TIMED_WAITING       5                                                      │
│                                                                              │
│ ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│   main  [RUNNABLE]                                                           │
│     tid=0x1  nid=0x1  prio=5                                                 │
│       at com.example.App.main(App.java:42)                                   │
│       at java.lang.Thread.run(Thread.java:833)                               │
╰──────────────────────────────────────────────────────────────────────────────╯
```

Options:
- `--raw` — Print the raw jstack/jcmd output without formatting
- `--depth=N` — Maximum stack frames per thread (default: 20)
- `--source=jdk|agent` — Force data source
- `--format=json` — JSON output with full thread array
