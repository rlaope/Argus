# Argus Commands — Threads

Commands for inspecting thread state, capturing thread dumps, detecting deadlocks, and analyzing thread pool distribution. Use `argus threads` for a quick state summary, `argus threaddump` for full stack traces, `argus deadlock` for automated deadlock detection, and `argus pool` to understand which thread pools are busy.

## Table of Contents

- [argus threads \<pid\>](#argus-threads-pid)
- [argus threaddump \<pid\>](#argus-threaddump-pid)
- [argus deadlock \<pid\>](#argus-deadlock-pid)
- [argus pool \<pid\>](#argus-pool-pid)
- [argus pool jdbc \<pid\>](#argus-pool-jdbc-pid)
- [argus pool advise \<pid\>](#argus-pool-advise-pid)

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

## argus threaddump \<pid\>

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

The `pool` namespace also has two subcommands for deeper diagnostics: [`argus pool jdbc`](#argus-pool-jdbc-pid) for connection pools and [`argus pool advise`](#argus-pool-advise-pid) for sizing recommendations.

---

## argus pool jdbc \<pid\>

Reports the state of JDBC connection pools (HikariCP and Tomcat JDBC) by reading their JMX MBeans. Use this when a pool exhaustion alert fires and you need to know which pool is busy, how many threads are waiting on a connection, and how close you are to the configured `maximumPoolSize`.

```bash
$ argus pool jdbc 39113
```

```
JDBC pools for PID 39113:
  pool                              active    idle   total   waiting   verdict
  ArgusQaPool                           14       4      18         0   OK
verdict: 0 CRIT / 0 WARN / 1 pool(s) total
```

### Verdict

| Verdict | Condition |
|---|---|
| `CRIT` | Any threads currently waiting for a connection (`waiting > 0`) |
| `WARN` | `max > 0` and `active / max >= 0.85` |
| `OK` | Everything else (including when `max` is not reported by the pool) |

### Detected MBeans

- HikariCP: `com.zaxxer.hikari:type=Pool*` (also reads the matching `type=PoolConfig (...)` MBean for `MaximumPoolSize`). Requires `HikariConfig.setRegisterMbeans(true)` on the application side.
- Tomcat JDBC: `tomcat.jdbc:type=ConnectionPool,*` — attributes `Active`, `Idle`, `MaxActive`, `WaitCount`.

If neither family is registered, the command prints a friendly message and exits with code 0 — not having JDBC pools is not an error.

### Options

- `<pid>` — Target JVM PID (required).
- `--format=json` — Emit a parseable JSON document with the `pools` array, each entry carrying `name`, `kind` (`hikari` or `tomcat-jdbc`), `active`, `idle`, `total`, `max`, `waiting`, `verdict`.
- `--help`, `-h` — Print usage.

### JSON example

```json
{
  "pid": 39113,
  "pools": [
    {"name": "ArgusQaPool", "kind": "hikari", "active": 14, "idle": 4,
     "total": 18, "max": 20, "waiting": 0, "verdict": "OK"}
  ]
}
```

### Limitations / explicitly deferred

- **Leak-thread attribution** — knowing *which thread frame* holds a leaked connection requires HikariCP's `leakDetectionThreshold` opt-in or instrumentation. The current command shows pool state and verdict only. This is tracked as a follow-up.
- No DoctorEngine rule integration yet (the `JdbcPoolExhaustion` and `JdbcConnectionLeak` rules in the design notes are a separate PR).

---

## argus pool advise \<pid\>

Recommends a sizing for thread pools in the target JVM, based on a short sampling window of `ThreadMXBean`. Use this when you suspect a pool is over- or under-provisioned and you want a starting number plus the parameters behind it.

```bash
$ argus pool advise 39113 --window=3s
```

```
Thread-pool sizing advisor for PID 39113 (window=3000ms):
  group                           p99Active  blocking%   configured  recommended
  argus-qa-worker                        50     100.0%            -           75
  argus-qa-scheduler                      2     100.0%            -            4
Recommendation: ceil(p99Active * 1.5), min 4. Per-pool arrival rate is unavailable via standard JMX; this is a sizing-headroom heuristic, not a full Little's Law calculation.
```

### How it samples

- Polls `ThreadMXBean.dumpAllThreads(false, false)` every 250 ms over the window.
- Groups threads by name prefix:
  - `http-nio-PORT-exec-N` → `http-nio-PORT`
  - `ForkJoinPool*-worker-N` → `ForkJoinPool.*`
  - `pool-N-thread-M` → `pool-N`
  - `scheduling-N` → `scheduling`
  - Any name ending in `-<digits>` (including long suffixes from custom `ThreadFactory` implementations using `System.nanoTime()` or unix-millis) → the prefix.
- JDK/container-internal pools the user cannot resize are filtered out: `RMI TCP Accept`, `RMI Scheduler`, `GC Thread`, `Catalina-utility`, `G1 *`, `ParGC*`.
- For Tomcat connectors, also queries `Catalina:type=ThreadPool,*` for the configured `maxThreads` and shows it in the `configured` column.

### Verdict math

- `p99Active` is the 99th-percentile of per-sample thread count in the group across the window.
- `recommended = max(4, ceil(p99Active × 1.5))`.
- `blocking%` is the share of samples where the thread was in a non-`RUNNABLE` state (a rough idle-vs-busy signal).

### Options

- `<pid>` — Target JVM PID (required).
- `--window=Ns|Nms|Nm` — Sampling window. Default: `5s`. Garbage values fall back to the default; negative or zero produces no samples.
- `--format=json` — Emit structured JSON with the `pools` array (`group`, `p99Active`, `blockingRatio`, `configured`, `recommended`).
- `--help`, `-h` — Print usage.

### JSON example

```json
{
  "pid": 39113,
  "windowMs": 3000,
  "pools": [
    {"group": "argus-qa-worker",    "p99Active": 50, "blockingRatio": 1.0000,
     "configured": 0, "recommended": 75},
    {"group": "argus-qa-scheduler", "p99Active":  2, "blockingRatio": 1.0000,
     "configured": 0, "recommended":  4}
  ]
}
```

### Limitations / explicitly deferred

- `p99Active` is "p99 of threads in the named group", not "p99 of threads actively doing work". A 50-thread pool that is mostly idle will still show `p99Active=50` and recommend ~75, which may grow an oversized pool further. Treat the number as a sizing-headroom hint, not an authoritative answer; lean on the `blocking%` column for the idle-vs-busy signal.
- Per-pool arrival rate is not available via standard JMX, so full Little's Law (L = λ × W) is not computed. Where Tomcat exposes request rate via its connector MBean, that integration is a future enhancement.
- Custom `ExecutorService` instances without an MBean cannot report `configured` size — the column shows `-`.

---

[Back to all commands](../cli-commands.md)
