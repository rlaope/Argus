_argus_completions() {
    local cur prev commands
    cur="${COMP_WORDS[COMP_CWORD]}"
    prev="${COMP_WORDS[COMP_CWORD-1]}"

<<<<<<< HEAD
    commands="alert init ps histo threads gc gcutil heap sysprops vmflag nmt classloader profile jfr jfranalyze diff report doctor gclog gclogdiff gcprofile flame suggest watch tui info heapdump heapanalyze deadlock threaddump buffers gcrun logger events compilerqueue sc env compiler finalizer stringtable pool gccause metaspace dynlibs vmset vmlog jmx classstat gcnew symboltable top perfcounter mbean ci compare slowlog explain trace"
=======
    commands="init ps histo threads gc gcutil heap sysprops vmflag nmt classloader profile jfr jfranalyze diff report doctor gclog gclogdiff gcprofile flame suggest watch tui info heapdump heapanalyze deadlock threaddump buffers gcrun logger events compilerqueue sc env compiler finalizer stringtable pool gccause metaspace dynlibs vmset vmlog jmx classstat gcnew symboltable top perfcounter mbean ci compare slowlog explain spring benchmark"
>>>>>>> e23934b (feat: argus spring + benchmark commands (#132, #133))

    if [ "$COMP_CWORD" -eq 1 ]; then
        COMPREPLY=($(compgen -W "$commands --help --version" -- "$cur"))
        return
    fi

    case "$prev" in
        --type)
            COMPREPLY=($(compgen -W "cpu alloc lock wall" -- "$cur"))
            ;;
        --source)
            COMPREPLY=($(compgen -W "auto agent jdk" -- "$cur"))
            ;;
        --format)
            COMPREPLY=($(compgen -W "table json" -- "$cur"))
            ;;
        --lang)
            COMPREPLY=($(compgen -W "en ko ja zh" -- "$cur"))
            ;;
        jfr)
            COMPREPLY=($(compgen -W "start stop check dump" -- "$cur"))
            ;;
        *)
            if [[ "$cur" == --* ]]; then
                local opts="--source= --no-color --lang= --format= --help"
                case "${COMP_WORDS[1]}" in
                    histo) opts="$opts --top=" ;;
                    profile) opts="$opts --type= --duration= --flame --file= --top=" ;;
                    gcutil) opts="$opts --watch=" ;;
                    sysprops|vmflag) opts="$opts --filter=" ;;
                    vmflag) opts="$opts --set=" ;;
                    heapdump) opts="$opts --file= --live --all --yes" ;;
                    pool) opts="$opts --top=" ;;
                    dynlibs) opts="$opts --filter=" ;;
                    vmset) opts="$opts --yes" ;;
                    jmx) opts="$opts" ;;
                    spring) opts="$opts --beans --datasource" ;;
                    benchmark) opts="$opts --iterations= --warmup= --duration=" ;;
                    diff) opts="$opts --top=" ;;
                    alert) opts="$opts --config= --gc-overhead= --leak --webhook= --interval=" ;;
                    top) opts="$opts --host= --port= --interval=" ;;
                    trace) opts="$opts --duration=" ;;
                esac
                COMPREPLY=($(compgen -W "$opts" -- "$cur"))
            fi
            ;;
    esac
}
complete -F _argus_completions argus
