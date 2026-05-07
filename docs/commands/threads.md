# Argus Commands — Threads

Commands for inspecting thread state, capturing thread dumps, detecting deadlocks, and analyzing thread pool distribution. Use `argus threads` for a quick state summary, `argus threaddump` for full stack traces, `argus deadlock` for automated deadlock detection, and `argus pool` to understand which thread pools are busy.

## Table of Contents

- [argus threads \<pid\>](#argus-threads-pid)
- [argus threaddump \<pid\>](#argus-threaddump-pid)
- [argus deadlock \<pid\>](#argus-deadlock-pid)
- [argus pool \<pid\>](#argus-pool-pid)

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

---

[Back to all commands](../cli-commands.md)
