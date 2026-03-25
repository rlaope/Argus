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
