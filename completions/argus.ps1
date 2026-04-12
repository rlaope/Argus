# PowerShell completions for argus

$ArgusCommands = @(
    @{ Name = 'init';        Desc = 'Initialize CLI configuration' }
    @{ Name = 'ps';          Desc = 'List running JVM processes' }
    @{ Name = 'histo';       Desc = 'Heap object histogram' }
    @{ Name = 'threads';     Desc = 'Thread dump summary' }
    @{ Name = 'gc';          Desc = 'GC statistics' }
    @{ Name = 'gcutil';      Desc = 'GC generation utilization' }
    @{ Name = 'heap';        Desc = 'Heap memory usage' }
    @{ Name = 'sysprops';    Desc = 'JVM system properties' }
    @{ Name = 'vmflag';      Desc = 'VM flags' }
    @{ Name = 'nmt';         Desc = 'Native memory tracking' }
    @{ Name = 'classloader'; Desc = 'Class loader hierarchy' }
    @{ Name = 'profile';     Desc = 'CPU/allocation/lock profiling' }
    @{ Name = 'jfr';         Desc = 'Flight Recorder control' }
    @{ Name = 'diff';        Desc = 'Heap snapshot diff' }
    @{ Name = 'report';      Desc = 'Comprehensive diagnostic report' }
    @{ Name = 'info';        Desc = 'JVM information' }
    @{ Name = 'heapdump';    Desc = 'Generate heap dump' }
    @{ Name = 'deadlock';    Desc = 'Detect Java-level deadlocks' }
    @{ Name = 'threaddump'; Desc = 'Full thread dump with stack traces' }
    @{ Name = 'buffers';    Desc = 'NIO buffer pool statistics' }
    @{ Name = 'gcrun';      Desc = 'Trigger System.gc() on target JVM' }
    @{ Name = 'logger';     Desc = 'View and change log levels at runtime' }
    @{ Name = 'events';     Desc = 'VM internal event log' }
    @{ Name = 'compilerqueue'; Desc = 'JIT compilation queue' }
    @{ Name = 'sc';          Desc = 'Search loaded classes by pattern' }
    @{ Name = 'jfranalyze'; Desc = 'Analyze a JFR recording file' }
    @{ Name = 'doctor';     Desc = 'One-click JVM health diagnosis' }
    @{ Name = 'gclog';      Desc = 'Analyze GC log file with tuning recommendations' }
    @{ Name = 'gclogdiff'; Desc = 'Compare two GC log files with regression detection' }
    @{ Name = 'flame';      Desc = 'One-shot flame graph with browser open' }
    @{ Name = 'watch';      Desc = 'Real-time terminal dashboard' }
    @{ Name = 'suggest';    Desc = 'JVM flag optimization' }
    @{ Name = 'env';         Desc = 'JVM launch environment' }
    @{ Name = 'compiler';    Desc = 'JIT compiler and code cache stats' }
    @{ Name = 'finalizer';   Desc = 'Finalizer queue status' }
    @{ Name = 'stringtable'; Desc = 'String table statistics' }
    @{ Name = 'pool';        Desc = 'Thread pool analysis' }
    @{ Name = 'gccause';     Desc = 'GC cause with utilization stats' }
    @{ Name = 'metaspace';   Desc = 'Detailed metaspace breakdown' }
    @{ Name = 'dynlibs';     Desc = 'Loaded native libraries' }
    @{ Name = 'vmset';       Desc = 'Set VM flag at runtime' }
    @{ Name = 'vmlog';       Desc = 'JVM unified logging control' }
    @{ Name = 'jmx';         Desc = 'JMX agent control' }
    @{ Name = 'classstat';   Desc = 'Class loading statistics' }
    @{ Name = 'gcnew';       Desc = 'Young generation GC detail' }
    @{ Name = 'symboltable'; Desc = 'Symbol table statistics' }
    @{ Name = 'top';         Desc = 'Real-time monitoring' }
)

Register-ArgumentCompleter -CommandName argus -Native -ScriptBlock {
    param($wordToComplete, $commandAst, $cursorPosition)

    $tokens = $commandAst.ToString() -split '\s+'
    $tokenCount = $tokens.Count

    # Complete command name (first argument after 'argus')
    if ($tokenCount -le 2) {
        $ArgusCommands | Where-Object { $_.Name -like "$wordToComplete*" } | ForEach-Object {
            [System.Management.Automation.CompletionResult]::new(
                $_.Name, $_.Name, 'ParameterValue', $_.Desc
            )
        }
        # Global options
        @('--help', '--version', '--source=', '--no-color', '--lang=', '--format=') |
            Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                [System.Management.Automation.CompletionResult]::new(
                    $_, $_, 'ParameterValue', $_
                )
            }
        return
    }

    $command = $tokens[1]

    # Complete options based on command
    if ($wordToComplete -like '--*') {
        $opts = @('--source=', '--no-color', '--lang=', '--format=', '--help')

        switch ($command) {
            'histo'   { $opts += '--top=' }
            'diff'    { $opts += '--top=' }
            'pool'    { $opts += '--top=' }
            'profile' { $opts += @('--type=', '--duration=', '--flame', '--file=', '--top=') }
            'gcutil'  { $opts += '--watch=' }
            'sysprops' { $opts += '--filter=' }
            'vmflag'  { $opts += @('--filter=', '--set=') }
            'dynlibs' { $opts += '--filter=' }
            'heapdump' { $opts += @('--file=', '--live', '--all', '--yes') }
            'vmset'   { $opts += '--yes' }
            'top'     { $opts += @('--host=', '--port=', '--interval=') }
        }

        $opts | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
            [System.Management.Automation.CompletionResult]::new(
                $_, $_, 'ParameterValue', $_
            )
        }
        return
    }

    # Complete values for specific options
    $prevToken = if ($tokenCount -ge 3) { $tokens[$tokenCount - 2] } else { '' }

    switch -Wildcard ($prevToken) {
        '--source*' {
            @('auto', 'agent', 'jdk') | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
            }
        }
        '--lang*' {
            @('en', 'ko', 'ja', 'zh') | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
            }
        }
        '--format*' {
            @('table', 'json') | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
            }
        }
        '--type*' {
            @('cpu', 'alloc', 'lock', 'wall') | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
            }
        }
    }

    # Subcommand completions
    switch ($command) {
        'jfr' {
            @('start', 'stop', 'check', 'dump') | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
            }
        }
        'jmx' {
            @('status', 'start', 'start-local', 'stop') | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
            }
        }
    }
}
