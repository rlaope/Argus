#compdef argus

_argus() {
    local -a commands
    commands=(
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
            esac
            ;;
    esac
}

_argus
