param(
    [string]$AgentJar = (Join-Path $PSScriptRoot '..\target\apocbr-agent.jar'),
    [string]$GameJar = 'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar',
    [string]$GameHome = '',
    [string]$Java = 'java',
    [string]$Javac = 'javac'
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$original = (Resolve-Path -LiteralPath $GameJar).Path
$artifact = (Resolve-Path -LiteralPath $AgentJar).Path
if (-not $GameHome) { $GameHome = Split-Path -Parent $original }
$runtimeHome = (Resolve-Path -LiteralPath $GameHome).Path
$run = Join-Path $repository ('target\lua-validation\run-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$agentCopy = Join-Path $run 'agent.jar'
Copy-Item -LiteralPath $artifact -Destination $agentCopy
$testClasses = Join-Path $run 'test'
$classpath = $original + [IO.Path]::PathSeparator + $agentCopy
$testRoot = Join-Path $repository 'src\test\java\cloud\apocalipse\pz\agent\features\lua'
$sources = @(
    (Join-Path $testRoot 'LuaOriginalSmokeMain.java'),
    (Join-Path $testRoot 'LuaLifecycleFixture.java'),
    (Join-Path $testRoot 'api\ApiOriginalAssertions.java'),
    (Join-Path $testRoot 'events\EventOriginalAssertions.java'),
    (Join-Path $testRoot 'login\LoginOriginalSmokeMain.java')
)
& $Javac -cp $classpath -d $testClasses @sources
if ($LASTEXITCODE -ne 0) { throw 'Lua smoke fixture compilation failed' }
$classpath += [IO.Path]::PathSeparator + $testClasses
@{
    originalJar = $original
    originalSha256 = (Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash
    agentSha256 = (Get-FileHash -LiteralPath $agentCopy -Algorithm SHA256).Hash
    sources = @($sources | ForEach-Object { @{path=$_; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash} })
    scope = 'Original game classes with fake HTTP/JDBC/transport; no GameServer.main or real server startup'
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run 'evidence.json') -Encoding UTF8

function Invoke-Smoke([string]$Main, [bool]$Accounts, [string]$Marker, [string]$LogName) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Java
    $start.WorkingDirectory = $runtimeHome
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($key in @('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','APOCBR_POSTGRES_URL',
            'APOCBR_TELEMETRY_ENDPOINT','APOCBR_TELEMETRY_API_KEY','APOCBR_BRIDGE_ENDPOINT','APOCBR_BRIDGE_API_KEY',
            'PZMANAGER_MOD_UPDATE_TOKEN','PZMANAGER_RESTART_URL')) { $start.EnvironmentVariables.Remove($key) | Out-Null }
    $arguments = @(
            ('-Dapocbr.postgres.enabled=' + $Accounts.ToString().ToLowerInvariant()),
            '-Dapocbr.postgres.url=jdbc:postgresql://localhost/fixture',
            '-Dapocbr.postgres.serverDatabase.enabled=true',
            '-Dapocbr.postgres.async.enabled=true',
            '-Dapocbr.lua.enabled=true',
            '-Dapocbr.postgres.loginHistory.enabled=false',
            '-Dapocbr.events.enabled=false',
            '-Dapocbr.bridge.enabled=false',
            '-Dapocbr.bridge.api.enabled=false',
            '-Dapocbr.restart.enabled=false',
            '-Dapocbr.npc.enabled=false',
            ('-javaagent:' + $agentCopy),
            '-cp', $classpath, $Main)
    $quoted = foreach ($argument in $arguments) {
        # Windows argv quoting; UseShellExecute=false means no shell interprets these arguments.
        $escaped = [regex]::Replace([string]$argument, '(\\*)"', '$1$1\"')
        $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
        '"' + $escaped + '"'
    }
    $start.Arguments = $quoted -join ' '

    $child = [Diagnostics.Process]::new()
    $child.StartInfo = $start
    try {
        $child.Start() | Out-Null
        $stdout = $child.StandardOutput.ReadToEndAsync()
        $stderr = $child.StandardError.ReadToEndAsync()
        if (-not $child.WaitForExit(45000)) {
            $child.Kill()
            $child.WaitForExit()
            throw ('Smoke timed out: ' + $Main)
        }
        $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        [IO.File]::WriteAllText((Join-Path $run $LogName), $output)
        if ($child.ExitCode -ne 0 -or -not $output.Contains($Marker)) {
            throw ('Smoke failed; inspect ' + (Join-Path $run $LogName))
        }
        Write-Output $Marker
    } finally { $child.Dispose() }
}
Invoke-Smoke 'cloud.apocalipse.pz.agent.features.lua.LuaOriginalSmokeMain' $false 'ORIGINAL_LUA_SMOKE_PASS' 'lua.log'
Invoke-Smoke 'cloud.apocalipse.pz.agent.features.lua.login.LoginOriginalSmokeMain' $true 'ORIGINAL_LOGIN_SMOKE_PASS' 'login.log'
Write-Output ('Evidence: ' + $run)
