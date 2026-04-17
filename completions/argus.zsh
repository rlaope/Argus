#compdef argus

_argus() {
    local -a commands
    commands=(
        'alert:Monitor JVM metrics and send webhook alerts on threshold breach'
        'init:Initialize CLI configuration'
        'ps:List running JVM processes'
        'histo:Heap object histogram'
        'threads:Thread dump summary'
        'gc:GC statistics'
        'gcutil:GC generation utilization'
        'heap:Heap memory usage'
        'sysprops:JVM system properties'
        'vmflag:VM flags'
        'nmt:Native memory tracking'
        'classloader:Class loader hierarchy'
        'profile:CPU/allocation/lock profiling'
        'jfr:Flight Recorder control'
        'diff:Heap snapshot diff'
        'report:Comprehensive diagnostic report'
        'info:JVM information'
        'heapdump:Generate heap dump'
        'deadlock:Detect Java-level deadlocks'
        'threaddump:Full thread dump with stack traces'
        'buffers:NIO buffer pool statistics'
        'gcrun:Trigger System.gc() on target JVM'
        'logger:View and change log levels at runtime'
        'events:VM internal event log'
        'compilerqueue:JIT compilation queue'
        'sc:Search loaded classes by pattern'
        'jfranalyze:Analyze a JFR recording file'
        'doctor:One-click JVM health diagnosis'
        'gclog:Analyze GC log file with tuning recommendations'
        'gclogdiff:Compare two GC log files with regression detection'
        'gcprofile:GC-aware allocation profiling via JFR'
        'gcscore:One-page GC Health Score Card (A-F grade from a GC log)'
        'flame:One-shot flame graph with browser open'
        'watch:Real-time terminal dashboard'
        'suggest:JVM flag optimization'
        'env:JVM launch environment'
        'compiler:JIT compiler and code cache stats'
        'finalizer:Finalizer queue status'
        'stringtable:String table statistics'
        'pool:Thread pool analysis'
        'gccause:GC cause with utilization stats'
        'metaspace:Detailed metaspace breakdown'
        'dynlibs:Loaded native libraries'
        'vmset:Set VM flag at runtime'
        'vmlog:JVM unified logging control'
        'jmx:JMX agent control'
        'classstat:Class loading statistics'
        'gcnew:Young generation GC detail'
        'symboltable:Symbol table statistics'
        'top:Real-time monitoring'
        'heapanalyze:Analyze heap dump (.hprof)'
        'perfcounter:JVM internal performance counters'
        'mbean:Browse JMX MBeans'
        'ci:CI/CD health gate'
        'compare:Compare two JVM snapshots'
        'slowlog:Real-time slow method detection'
        'explain:Explain JVM metrics, GC causes, and flags in plain English'
'trace:Method execution tracing via rapid thread sampling'
'spring:Inspect Spring Boot application via JMX'
        'benchmark:Sampling-based method benchmark'
    )

    _arguments -C \
        '--help[Show help]' \
        '--version[Show version]' \
        '--source=[Data source]:source:(auto agent jdk)' \
        '--no-color[Disable colors]' \
        '--lang=[Output language]:lang:(en ko ja zh)' \
        '--format=[Output format]:format:(table json)' \
        '1:command:->cmd' \
        '*::arg:->args'

    case "$state" in
        cmd)
            _describe 'command' commands
            ;;
        args)
            case "${words[1]}" in
                jfr)
                    _values 'subcommand' start stop check dump
                    ;;
                profile)
                    _arguments \
                        '--type=[Profiling type]:type:(cpu alloc lock wall)' \
                        '--duration=[Duration in seconds]' \
                        '--flame[Generate flame graph]' \
                        '--file=[Output file]' \
                        '--top=[Top N methods]'
                    ;;
                heapdump)
                    _arguments \
                        '--file=[Output file]' \
                        '--live[Live objects only]' \
                        '--all[All objects]' \
                        '--yes[Skip confirmation]'
                    ;;
                histo|diff)
                    _arguments '--top=[Top N entries]'
                    ;;
                sysprops|vmflag)
                    _arguments '--filter=[Filter pattern]'
                    ;;
                gcutil)
                    _arguments '--watch=[Refresh interval]'
                    ;;
trace)
                    _arguments '--duration=[Duration in seconds]'
spring)
                    _arguments \
                        '--beans[List Spring beans with count]' \
                        '--datasource[Show datasource connection pool stats]'
                    ;;
                benchmark)
                        '--iterations=[Approximate iteration count]' \
                        '--warmup=[Warmup iterations]' \
                        '--duration=[Measurement duration in seconds]'
                    ;;
            esac
            ;;
    esac
}

_argus
