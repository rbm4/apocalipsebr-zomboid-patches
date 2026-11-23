[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$StreamingRun,
    [Parameter(Mandatory)][string]$PostgresBin,
    [switch]$VerifyEngineMigration
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$run = (Resolve-Path -LiteralPath $StreamingRun).Path
$bin = (Resolve-Path -LiteralPath $PostgresBin).Path
if (!(Test-Path -LiteralPath (Join-Path $run 'streaming-complete') -PathType Leaf)) {
    throw "Expected a completed streaming integration run: $run"
}
$map = (Resolve-Path -LiteralPath (Join-Path $run 'cache/Saves/Multiplayer/agent-integration/map')).Path
[xml]$pom = Get-Content -Raw -LiteralPath (Join-Path $root 'pom.xml')
$version = $pom.project.properties.'postgresql.version'
$driver = (Resolve-Path -LiteralPath (Join-Path $env:USERPROFILE ".m2/repository/org/postgresql/postgresql/$version/postgresql-$version.jar")).Path
$java = (Resolve-Path -LiteralPath (Join-Path $root 'jdk/bin/java.exe')).Path
$source = Join-Path $root 'src/test/java/cloud/apocalipse/pz/agent/integration/replay/ChunkStorageReplay.java'
$output = Join-Path $root ('target/chunk-storage-replay/run-' + [guid]::NewGuid().ToString('N'))
New-Item -Path $output -ItemType Directory -Force | Out-Null
$data = Join-Path $output 'db'
$serverLog = Join-Path $output 'postgres.log'
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
try { $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }

$pgCtl = Join-Path $bin 'pg_ctl.exe'
Write-Host "Replay artifacts: $output"
& (Join-Path $bin 'initdb.exe') -D $data -U benchmark_migrator --locale=C --encoding=UTF8 --auth=trust > (Join-Path $output 'initdb.log')
if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL initialization failed' }
$isStarted = $false
try {
    # Wait for pg_ctl itself. A PowerShell native pipeline can wait on inherited
    # output handles held by the long-running Windows postmaster.
    $ctlArgs = @('-D', ('"' + $data + '"'), '-l', ('"' + $serverLog + '"'),
        '-o', ('"-h 127.0.0.1 -p {0} -c shared_buffers=256MB"' -f $port), '-t', '30', '-w', 'start')
    $ctl = Start-Process -FilePath $pgCtl -ArgumentList $ctlArgs -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $output 'pgctl-start.log') `
        -RedirectStandardError (Join-Path $output 'pgctl-start-error.log')
    if (!$ctl.WaitForExit(40000) -or $ctl.ExitCode -ne 0) {
        throw "Disposable PostgreSQL startup failed: $output"
    }
    $isStarted = $true
    & $java --class-path $driver $source $map $port | Tee-Object -FilePath (Join-Path $output 'replay.log')
    if ($LASTEXITCODE -ne 0) { throw 'Chunk replay failed' }
    if ($VerifyEngineMigration) {
        $psql = Join-Path $bin 'psql.exe'
        & $psql -X -h 127.0.0.1 -p $port -U benchmark_migrator -d postgres -v ON_ERROR_STOP=1 `
            -c 'CREATE ROLE chunk_migrator LOGIN' `
            -c 'CREATE ROLE chunk_engine LOGIN' `
            -c 'CREATE DATABASE chunk_migration OWNER chunk_migrator'
        if ($LASTEXITCODE -ne 0) { throw 'Isolated chunk migration database setup failed' }
        $previousJavaHome = $env:JAVA_HOME
        Push-Location -LiteralPath $root
        try {
            $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
            & mvn verify -Pserver-integration '-Dpz.it.streaming=true' '-Dpz.it.serverLogs=false' `
                "-Dpz.it.chunkMigrationSeed=$run" "-Dpz.it.chunkMigrationPort=$port"
            if ($LASTEXITCODE -ne 0) { throw 'Engine chunk migration integration failed' }
        } finally {
            $env:JAVA_HOME = $previousJavaHome
            Pop-Location
        }
    }
} finally {
    if ($isStarted -or (Test-Path -LiteralPath (Join-Path $data 'postmaster.pid'))) {
        & $pgCtl -D $data -m fast -w stop
        if ($LASTEXITCODE -ne 0) { throw "Disposable PostgreSQL stop failed: $data" }
    }
}
