# Argus Commands — Monitoring

Commands for observing and inspecting running JVM processes in real time. This category covers process discovery, live dashboards, environment inspection, comprehensive reports, and multi-instance cluster monitoring. Use these commands first when you need situational awareness before diving into a specific subsystem.

## Table of Contents

- [argus ps](#argus-ps)
- [argus info \<pid\>](#argus-info-pid)
- [argus env \<pid\>](#argus-env-pid)
- [argus top](#argus-top)
- [argus watch](#argus-watch)
- [argus report \<pid\>](#argus-report-pid)
- [argus diff \<pid\> [interval]](#argus-diff-pid-interval)
- [argus alert \<target\>](#argus-alert-target)
- [argus cluster \<subcommand\> \<targets...\>](#argus-cluster-subcommand-targets)
- [argus perfcounter \<pid\>](#argus-perfcounter-pid)
- [argus tui](#argus-tui)

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

## argus alert \<target\>

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

## argus cluster \<subcommand\> \<targets...\>

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

## argus perfcounter \<pid\>

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

## argus tui

Launches the interactive terminal UI (k9s-style). Displays a JVM process list first; selecting a process shows the command list for that PID.

```bash
$ argus tui
```

No options. Press `q` to quit, arrow keys to navigate, Enter to select.

---

[Back to all commands](../cli-commands.md)
