# Argus CLI Command Reference

Complete reference for all 17 Argus CLI commands with usage examples and actual output.

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
