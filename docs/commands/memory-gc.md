# Argus Commands — Memory & GC

Commands for inspecting heap memory, garbage collection activity, and memory leak diagnosis. This category covers everything from live GC statistics and heap histograms to offline GC log analysis, ZGC health verdicts, NIO buffer pools, and classloader leak attribution. Start with `argus gc` or `argus heap` for a quick overview, then drill down with the specialized commands.

## Table of Contents

- [argus gc \<pid\>](#argus-gc-pid)
- [argus gcutil \<pid\>](#argus-gcutil-pid)
- [argus gcnew \<pid\>](#argus-gcnew-pid)
- [argus gccause \<pid\>](#argus-gccause-pid)
- [argus gcrun \<pid\>](#argus-gcrun-pid)
- [argus heap \<pid\>](#argus-heap-pid)
- [argus heapdump \<pid\>](#argus-heapdump-pid)
- [argus histo \<pid\>](#argus-histo-pid)
- [argus nmt \<pid\>](#argus-nmt-pid)
- [argus buffers \<pid\>](#argus-buffers-pid)
- [argus finalizer \<pid\>](#argus-finalizer-pid)
- [argus metaspace \<pid\>](#argus-metaspace-pid)
- [argus classstat \<pid\>](#argus-classstat-pid)
- [argus classleak \<pid\>](#argus-classleak-pid)
- [argus gclog](#argus-gclog)
- [argus gclogdiff \<file1\> \<file2\>](#argus-gclogdiff-file1-file2)
- [argus gcscore](#argus-gcscore)
- [argus gcwhy](#argus-gcwhy)
- [argus gcprofile](#argus-gcprofile)
- [argus heapanalyze \<file.hprof\>](#argus-heapanalyze-filehprof)
- [argus g1 \<pid\>](#argus-g1-pid)
- [argus zgc \<pid\>](#argus-zgc-pid)

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

## argus gcrun \<pid\>

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
│ ────  ────────────────────────────────────────────  ──────────  ──────── │
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

## argus buffers \<pid\>

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

## argus classleak \<pid\>

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

## argus gclogdiff \<file1\> \<file2\>

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

## argus heapanalyze \<file.hprof\>

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
│ ─────────────────────────────────────────────────────────────────────────── │
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

## argus g1 \<pid\>

One-shot G1GC health verdict from a live JFR capture. Pre-checks via JMX that the target JVM is using G1, then starts a `JFR.start` recording with `settings=profile`, waits for the capture window, dumps and parses the recording, and prints a structured report with a HEALTHY / WARNING / UNHEALTHY verdict.

```
argus g1 <PID> [--duration=N] [--save=PATH] [--diff=PATH] [--watch[=N]] [--interval=N]
```

```bash
$ argus g1 <PID>
$ argus g1 <PID> --duration=60    # extend capture to 60s (5–120 range)
$ argus g1 <PID> --save=/tmp/g1-baseline.txt
$ argus g1 <PID> --diff=/tmp/g1-baseline.txt
$ argus g1 <PID> --watch --interval=60
$ argus g1 <PID> --watch=10 --interval=60
```

**Sample output (WARNING with humongous pressure):**

```
G1 Diagnosis (PID 18420, JDK 21, Adaptive IHOP)
═══════════════════════════════════════════════
  Config     target 200ms · region 8MB · IHOP 45%
  Heap         committed 4.0 GiB / max 8.0 GiB
  Regions      eden 200 · survivor 12 · old 320 · humongous 18 / total 1024
  Cycles       42 young, 5 mixed, 2 concurrent, 0 full (avg young 38.2ms, avg mixed 92.1ms, max 184.5ms)
  Evacuation   ✓ no failures · young 1.8 GB · old 240 MB
  MMU          min 88.3%, avg 95.1%
  IHOP         predicted 45.0% / actual 46.2%
  Humongous    ⚠ 3 cycle(s) triggered by humongous allocation
               Top humongous-class alloc sites during capture (n=512 events)
                1. com.example.report.ReportBuilder.buildPdf(ReportBuilder.java:88)   (210 allocs, max 12 MB)
                2. com.example.cache.LocalCache.preload(LocalCache.java:42)           (180 allocs, max 8.4 MB)

Verdict: WARNING  — humongous allocation cycles observed.
Recommend:
  • Raise -XX:G1HeapRegionSize=16m so humongous threshold rises above common allocations
```

**Verdict table:**

| Verdict | Condition |
|---------|-----------|
| UNHEALTHY | Full GC observed **or** evacuation failure ("to-space exhausted") |
| WARNING | Mixed-cycle starvation, IHOP timing off, humongous allocation cycles, **or** max pause > 2 × target |
| HEALTHY | None of the above |

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--duration=N` | 30 | JFR capture window in seconds (clamped to 5–120) |
| `--save=PATH` | | Capture once, render normally, and persist a baseline to PATH |
| `--diff=PATH` | | Capture once, compare against baseline at PATH, render diff table (suppresses normal verdict) |
| `--watch[=N]` | | Loop indefinitely (`--watch`) or for N iterations (`--watch=N`). Each iteration captures using `--interval`, prints a 1-line summary. Every 5th iteration prints the full diagnosis table. Ctrl-C prints a final summary and cleans up JFR. |
| `--interval=N` | 30 | JFR capture duration and watch loop period in seconds (clamped to 10–300) |

**Subscribed JFR events:** `jdk.G1GarbageCollection` (cycle type), `jdk.G1HeapSummary` (region counts), `jdk.G1EvacuationYoungStatistics` / `jdk.G1EvacuationOldStatistics` (evac bytes + failure flag), `jdk.G1MMU` (mutator utilization), `jdk.G1AdaptiveIHOP` (predicted vs. actual threshold), `jdk.GarbageCollection` (Full GC + Mixed labels for older JDKs), `jdk.ObjectAllocationOutsideTLAB` (humongous-class hotspots, gated on `bytes ≥ regionSize/2`).

**Trend tracking:**

```bash
argus g1 12345 --save=/tmp/g1-baseline.txt
# … later, after deploy or config change …
argus g1 12345 --diff=/tmp/g1-baseline.txt
```

The diff classifies each row as INFO / WARN / REGRESSION. Newly-present Full GC, evacuation failure, or mixed starvation are REGRESSION. Max-pause increases > 50% and MMU drops > 20% are REGRESSION.

**Companion doctor rules** (run via `argus doctor <PID>`):

| Rule | Severity | Fires when |
|------|----------|-----------|
| G1FullGcRule | CRITICAL | `G1 Old Generation` collector count > 0 |
| G1RegionSizeRule | WARNING | Heap > 32 GB without `-XX:G1HeapRegionSize`, or tiny explicit region on a non-tiny heap |
| G1IhopConfigurationRule | WARNING | `-XX:-G1UseAdaptiveIHOP` with manual IHOP ≥ 70% |

---

## argus zgc \<pid\>

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

[Back to all commands](../cli-commands.md)
