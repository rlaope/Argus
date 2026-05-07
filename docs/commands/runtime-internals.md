# Argus Commands — Runtime & JVM Internals

Commands for inspecting and modifying JVM runtime internals without a restart. This category covers VM flags, unified logging control, JMX management, JIT compiler state, native library inspection, symbol and string table statistics, class search, Spring Boot integration, and operations tooling (doctor, suggest, ci, compare, explain, events, init). Use these commands to tune a live JVM, diagnose startup anomalies, or integrate Argus into CI/CD pipelines.

## Table of Contents

- [argus vmflag \<pid\>](#argus-vmflag-pid)
- [argus vmset \<pid\> Flag=value](#argus-vmset-pid-flagvalue)
- [argus vmlog \<pid\> [options]](#argus-vmlog-pid-options)
- [argus sysprops \<pid\>](#argus-sysprops-pid)
- [argus compiler \<pid\>](#argus-compiler-pid)
- [argus compilerqueue \<pid\>](#argus-compilerqueue-pid)
- [argus mbean \<pid\>](#argus-mbean-pid)
- [argus jmx \<pid\> [subcommand]](#argus-jmx-pid-subcommand)
- [argus dynlibs \<pid\>](#argus-dynlibs-pid)
- [argus stringtable \<pid\>](#argus-stringtable-pid)
- [argus symboltable \<pid\>](#argus-symboltable-pid)
- [argus sc \<pid\> \<pattern\>](#argus-sc-pid-pattern)
- [argus classloader \<pid\>](#argus-classloader-pid)
- [argus spring \<pid\>](#argus-spring-pid)
- [argus logger \<pid\>](#argus-logger-pid)
- [argus events \<pid\>](#argus-events-pid)
- [argus explain \<term\>](#argus-explain-term)
- [argus doctor](#argus-doctor)
- [argus suggest](#argus-suggest)
- [argus ci \[pid\]](#argus-ci-pid)
- [argus compare \<pid1\> \<pid2\>](#argus-compare-pid1-pid2)
- [argus init](#argus-init)

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

## argus compilerqueue \<pid\>

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

## argus mbean \<pid\>

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

## argus dynlibs \<pid\>

Lists native libraries loaded in the JVM process, grouped by category (JDK/App/System).

```bash
$ argus dynlibs 39113
```

Options:
- `--filter=<pattern>` — Filter library paths

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

## argus sc \<pid\> \<pattern\>

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

## argus spring \<pid\>

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

## argus logger \<pid\>

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

## argus events \<pid\>

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

## argus explain \<term\>

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

## argus ci \[pid\]

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

## argus compare \<pid1\> \<pid2\>

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

[Back to all commands](../cli-commands.md)
