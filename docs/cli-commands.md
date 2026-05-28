# Argus CLI Command Reference

Complete reference for all 70 Argus CLI commands. Commands are organized into five categories. Each category page contains full synopses, option tables, and output samples. Use the alphabetical index at the bottom to jump directly to any command.

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

**Locale resolution order:** `--lang=<code>` flag > `$LC_ALL` > `$LANG` > `~/.argus/config.properties` (set by `argus init`) > `en` fallback.

---

## JSON Output

All commands support `--format=json` for scripting and pipeline integration:

```bash
$ argus --format=json gc 39113
```

```json
{"totalEvents":0,"totalPauseMs":0.0,"overheadPercent":0.0,"lastCause":"","heapUsed":56849408,"heapCommitted":268435456,"collectors":[{"name":"g1 gc","count":0,"totalMs":0.0}]}
```

## Multi-language Support

Set the output language with `--lang` or configure it with `argus init`:

```bash
$ argus --lang=ko histo 39113 --top 3
```

Supported languages: English (`en`), Korean (`ko`), Japanese (`ja`), Chinese (`zh`)

---

## Categories

### Monitoring
Real-time process observation, dashboards, environment inspection, multi-instance cluster health, and alerting.
→ [commands/monitoring.md](commands/monitoring.md)

- `argus ps` — list running JVM processes
- `argus info` — JVM version, uptime, VM flags
- `argus env` — launch environment (classpath, VM args)
- `argus top` — real-time agent dashboard
- `argus watch` — htop-style live JVM dashboard
- `argus report` — single-view diagnostic report
- `argus diff` — heap snapshot comparison (leak detection)
- `argus alert` — threshold-based webhook alerting
- `argus cluster` — multi-instance aggregated health
- `argus perfcounter` — low-level JVM performance counters
- `argus tui` — interactive terminal UI (k9s-style)
- `argus harness` — continuous trend-aware health watch (doctor + 4 trend rules over a window)

---

### Memory & GC
Heap inspection, GC statistics, NIO buffers, metaspace, classloader leaks, GC log analysis, and ZGC health verdicts.
→ [commands/memory-gc.md](commands/memory-gc.md)

- `argus gc` — GC stats (heap usage, pause time, collector)
- `argus gcutil` — generation utilization % (jstat-style)
- `argus gcnew` — young-gen detail (eden, survivor, tenuring)
- `argus gccause` — last/current GC cause with gen utilization
- `argus gcrun` — trigger System.gc() remotely
- `argus heap` — detailed heap usage with space breakdown
- `argus heapdump` — generate heap dump (.hprof)
- `argus histo` — heap object histogram (count + size)
- `argus nmt` — native memory tracking by category
- `argus buffers` — NIO direct/mapped buffer pools
- `argus finalizer` — finalizer queue status
- `argus metaspace` — metaspace usage by space type
- `argus classstat` — class loading throughput
- `argus classleak` — classloader-level metaspace leak attribution
- `argus gclog` — analyze GC log files (pauses, causes, tuning)
- `argus gclogdiff` — compare two GC log files
- `argus gcscore` — A–F GC health scorecard
- `argus gcwhy` — narrate the worst GC pause in plain English
- `argus gcprofile` — JFR-based allocation profiling
- `argus heapanalyze` — offline HPROF analysis (MAT alternative)
- `argus g1` — G1GC live health verdict (HEALTHY/WARNING/UNHEALTHY)
- `argus zgc` — ZGC live health verdict (HEALTHY/WARNING/UNHEALTHY)

---

### Profiling & Tracing
CPU, allocation, lock, and wall-clock profiling via async-profiler; flame graph generation; JFR control; slow method detection; and method call tracing.
→ [commands/profiling-tracing.md](commands/profiling-tracing.md)

- `argus profile` — CPU/alloc/lock/wall profiling (async-profiler)
- `argus profile-gate` — CI/CD CPU regression gate
- `argus flame` — one-shot flame graph (HTML, opens browser)
- `argus jfr` — JFR start/stop/check/dump
- `argus jfranalyze` — analyze JFR recording file
- `argus slowlog` — real-time slow method detection (JFR streaming)
- `argus trace` — method call tree from thread-dump sampling
- `argus instrument` — opt-in live method instrumentation (watch args/return/exception, trace call tree, monitor invocation stats)
- `argus benchmark` — sampling-based throughput estimate for a method
- `argus snapshot` — forensic bundle (threads + histogram + doctor + JFR + optional heap dump) as a single tar.gz

---

### Runtime & JVM Internals
VM flag inspection and live modification, unified logging control, JMX management, JIT compiler state, class search, Spring Boot integration, and cross-cutting operations tooling.
→ [commands/runtime-internals.md](commands/runtime-internals.md)

- `argus vmflag` — show/filter non-default VM flags
- `argus vmset` — set a manageable VM flag at runtime
- `argus vmlog` — control JVM unified logging at runtime
- `argus sysprops` — JVM system properties (-D flags)
- `argus compiler` — JIT compiler status + code cache usage
- `argus compilerqueue` — current JIT compilation queue
- `argus mbean` — MBean browser (domains, attributes)
- `argus jmx` — JMX management agent control
- `argus dynlibs` — native libraries loaded in the JVM
- `argus stringtable` — interned string table statistics
- `argus symboltable` — JVM symbol table statistics
- `argus sc` — search loaded classes by pattern
- `argus classloader` — class loader hierarchy + class counts
- `argus spring` — Spring Boot app inspection via JMX
- `argus logger` — view/change log levels at runtime
- `argus events` — VM internal event log (safepoints, deoptimizations)
- `argus explain` — explain JVM metrics, GC causes, and flags
- `argus doctor` — one-click JVM health diagnosis
- `argus suggest` — JVM flag optimization by workload
- `argus ci` — CI/CD health gate with exit codes
- `argus compare` — side-by-side JVM comparison
- `argus init` — first-time setup wizard (language preference)

---

### Threads
Thread state summaries, full thread dumps, deadlock detection, and thread pool distribution analysis.
→ [commands/threads.md](commands/threads.md)

- `argus threads` — thread state summary with distribution bars
- `argus threaddump` — full thread dump (jstack replacement)
- `argus deadlock` — automated deadlock detection
- `argus pool` — thread pool grouping with state distribution

---

## All Commands A–Z

| Command | Category |
|---------|----------|
| `argus alert` | [Monitoring](commands/monitoring.md#argus-alert-target) |
| `argus benchmark` | [Profiling & Tracing](commands/profiling-tracing.md#argus-benchmark-pid-classmethod) |
| `argus buffers` | [Memory & GC](commands/memory-gc.md#argus-buffers-pid) |
| `argus ci` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-ci-pid) |
| `argus classloader` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-classloader-pid) |
| `argus classleak` | [Memory & GC](commands/memory-gc.md#argus-classleak-pid) |
| `argus classstat` | [Memory & GC](commands/memory-gc.md#argus-classstat-pid) |
| `argus cluster` | [Monitoring](commands/monitoring.md#argus-cluster-subcommand-targets) |
| `argus compare` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-compare-pid1-pid2) |
| `argus compiler` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-compiler-pid) |
| `argus compilerqueue` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-compilerqueue-pid) |
| `argus deadlock` | [Threads](commands/threads.md#argus-deadlock-pid) |
| `argus diff` | [Monitoring](commands/monitoring.md#argus-diff-pid-interval) |
| `argus doctor` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-doctor) |
| `argus dynlibs` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-dynlibs-pid) |
| `argus env` | [Monitoring](commands/monitoring.md#argus-env-pid) |
| `argus events` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-events-pid) |
| `argus explain` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-explain-term) |
| `argus finalizer` | [Memory & GC](commands/memory-gc.md#argus-finalizer-pid) |
| `argus flame` | [Profiling & Tracing](commands/profiling-tracing.md#argus-flame-pid) |
| `argus g1` | [Memory & GC](commands/memory-gc.md#argus-g1-pid) |
| `argus gc` | [Memory & GC](commands/memory-gc.md#argus-gc-pid) |
| `argus gccause` | [Memory & GC](commands/memory-gc.md#argus-gccause-pid) |
| `argus gclog` | [Memory & GC](commands/memory-gc.md#argus-gclog) |
| `argus gclogdiff` | [Memory & GC](commands/memory-gc.md#argus-gclogdiff-file1-file2) |
| `argus gcnew` | [Memory & GC](commands/memory-gc.md#argus-gcnew-pid) |
| `argus gcprofile` | [Memory & GC](commands/memory-gc.md#argus-gcprofile) |
| `argus gcrun` | [Memory & GC](commands/memory-gc.md#argus-gcrun-pid) |
| `argus gcscore` | [Memory & GC](commands/memory-gc.md#argus-gcscore) |
| `argus gcutil` | [Memory & GC](commands/memory-gc.md#argus-gcutil-pid) |
| `argus gcwhy` | [Memory & GC](commands/memory-gc.md#argus-gcwhy) |
| `argus harness` | [Monitoring](harness.md) |
| `argus heap` | [Memory & GC](commands/memory-gc.md#argus-heap-pid) |
| `argus heapanalyze` | [Memory & GC](commands/memory-gc.md#argus-heapanalyze-filehprof) |
| `argus heapdump` | [Memory & GC](commands/memory-gc.md#argus-heapdump-pid) |
| `argus histo` | [Memory & GC](commands/memory-gc.md#argus-histo-pid) |
| `argus info` | [Monitoring](commands/monitoring.md#argus-info-pid) |
| `argus init` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-init) |
| `argus instrument` | [Profiling & Tracing](commands/profiling-tracing.md#argus-instrument-sub-pid-classmethod) |
| `argus jfr` | [Profiling & Tracing](commands/profiling-tracing.md#argus-jfr-pid-subcommand) |
| `argus jfranalyze` | [Profiling & Tracing](commands/profiling-tracing.md#argus-jfranalyze-filejfr) |
| `argus jmx` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-jmx-pid-subcommand) |
| `argus logger` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-logger-pid) |
| `argus mbean` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-mbean-pid) |
| `argus metaspace` | [Memory & GC](commands/memory-gc.md#argus-metaspace-pid) |
| `argus nmt` | [Memory & GC](commands/memory-gc.md#argus-nmt-pid) |
| `argus perfcounter` | [Monitoring](commands/monitoring.md#argus-perfcounter-pid) |
| `argus pool` | [Threads](commands/threads.md#argus-pool-pid) |
| `argus pool jdbc` | [Threads](commands/threads.md#argus-pool-jdbc-pid) |
| `argus pool advise` | [Threads](commands/threads.md#argus-pool-advise-pid) |
| `argus profile` | [Profiling & Tracing](commands/profiling-tracing.md#argus-profile-pid) |
| `argus profile-gate` | [Profiling & Tracing](commands/profiling-tracing.md#argus-profile-gate-before-after) |
| `argus ps` | [Monitoring](commands/monitoring.md#argus-ps) |
| `argus report` | [Monitoring](commands/monitoring.md#argus-report-pid) |
| `argus sc` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-sc-pid-pattern) |
| `argus slowlog` | [Profiling & Tracing](commands/profiling-tracing.md#argus-slowlog-pid) |
| `argus snapshot` | [Profiling & Tracing](commands/profiling-tracing.md#argus-snapshot-pid) |
| `argus spring` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-spring-pid) |
| `argus stringtable` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-stringtable-pid) |
| `argus suggest` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-suggest) |
| `argus symboltable` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-symboltable-pid) |
| `argus sysprops` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-sysprops-pid) |
| `argus threaddump` | [Threads](commands/threads.md#argus-threaddump-pid) |
| `argus threads` | [Threads](commands/threads.md#argus-threads-pid) |
| `argus top` | [Monitoring](commands/monitoring.md#argus-top) |
| `argus trace` | [Profiling & Tracing](commands/profiling-tracing.md#argus-trace-pid-classmethod) |
| `argus tui` | [Monitoring](commands/monitoring.md#argus-tui) |
| `argus vmflag` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-vmflag-pid) |
| `argus vmlog` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-vmlog-pid-options) |
| `argus vmset` | [Runtime & JVM Internals](commands/runtime-internals.md#argus-vmset-pid-flagvalue) |
| `argus watch` | [Monitoring](commands/monitoring.md#argus-watch) |
| `argus zgc` | [Memory & GC](commands/memory-gc.md#argus-zgc-pid) |
