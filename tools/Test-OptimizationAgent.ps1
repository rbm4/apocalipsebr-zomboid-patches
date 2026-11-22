param(
    [string]$AgentJar = (Join-Path $PSScriptRoot '../target/apocbr-agent.jar'),
    [string]$GameJar = 'Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar',
    [string]$GameHome = '',
    [string]$Jdk = (Join-Path $PSScriptRoot '../jdk')
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$original = (Resolve-Path -LiteralPath $GameJar).Path
$jdkBin = (Resolve-Path -LiteralPath (Join-Path $Jdk 'bin')).Path
if (-not $GameHome) { $GameHome = Split-Path -Parent $original }
$runtimeHome = (Resolve-Path -LiteralPath $GameHome).Path
$run = Join-Path $repo ('target/optimization-validation/run-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$agent = Join-Path $run 'agent.jar'
Copy-Item -LiteralPath $AgentJar -Destination $agent
$classpath = $original + [IO.Path]::PathSeparator + $agent
$testRoot = Join-Path $repo 'src/test/java/cloud/apocalipse/pz/agent'
$probeMain = 'cloud.apocalipse.pz.agent.integration.probe.HookProbe'
$smokeMain = 'cloud.apocalipse.pz.agent.features.optimization.OptimizationOriginalSmokeMain'
& (Join-Path $jdkBin 'javac') -cp $classpath -d $classes `
    (Join-Path $testRoot 'features/optimization/OptimizationOriginalSmokeMain.java') `
    (Join-Path $testRoot 'integration/probe/HookProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Optimization fixture compilation failed' }

$probe = Join-Path $run 'probe.jar'
$manifest = Join-Path $run 'probe.mf'
[IO.File]::WriteAllText($manifest, "Premain-Class: $probeMain`r`n")
& (Join-Path $jdkBin 'jar') --create --file $probe --manifest $manifest `
    -C $classes 'cloud/apocalipse/pz/agent/integration/probe'
if ($LASTEXITCODE -ne 0) { throw 'Optimization hook observer packaging failed' }

@{
    originalJar = $original
    originalSha256 = (Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash
    agentSha256 = (Get-FileHash -LiteralPath $agent -Algorithm SHA256).Hash
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'evidence.json') -Encoding UTF8
Write-Output "Evidence directory $run"

function Invoke-OptimizationFixture([string]$State) {
    $evidence = Join-Path $run $State
    $cache = Join-Path $evidence 'cache'
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $jdkBin 'java'
    $start.WorkingDirectory = $runtimeHome
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($key in @($start.EnvironmentVariables.Keys)) {
        if ($key -match '^(APOCBR_|PZMANAGER_)' -or
            $key -in @('CLASSPATH', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) {
            $start.EnvironmentVariables.Remove($key)
        }
    }

    $arguments = @(
        '--enable-native-access=ALL-UNNAMED',
        '-Dapocbr.postgres.enabled=false',
        '-Dapocbr.npc.enabled=false',
        '-Dapocbr.restart.enabled=false',
        '-Dapocbr.telemetry.apiKey=',
        ('-javaagent:' + $agent),
        ('-javaagent:' + $probe + '=' + $evidence),
        '-cp', ($classpath + [IO.Path]::PathSeparator + $classes), $smokeMain,
        $original, $State, $cache, $evidence
    )
    if ($State -eq 'false') {
        $switches = @('zombieCulling', 'luaDispatch', 'playerNearVehicle', 'zombiePacket', 'thermalClothing') |
            ForEach-Object { "-Dapocbr.optimization.$_.enabled=false" }
        $arguments = $switches + $arguments
    }
    $start.Arguments = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '

    $child = [Diagnostics.Process]::new()
    $child.StartInfo = $start
    $log = Join-Path $evidence 'console.log'
    try {
        $child.Start() | Out-Null
        $stdout = $child.StandardOutput.ReadToEndAsync()
        $stderr = $child.StandardError.ReadToEndAsync()
        if (-not $child.WaitForExit(45000)) {
            $child.Kill()
            $child.WaitForExit()
            throw "Optimization fixture timed out ($State)"
        }
        $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        [IO.File]::WriteAllText($log, $output)
        if ($child.ExitCode -ne 0 -or -not $output.Contains("ORIGINAL_OPTIMIZATION_SMOKE_PASS enabled=$State")) {
            throw "Optimization fixture failed ($State). See $log"
        }
        return [regex]::Matches($output, '(?m)^OPTIMIZATION_PACKET_(?:true|false)=[0-9a-f]+') |
            ForEach-Object Value
    } finally {
        $child.Dispose()
    }
}

$vanillaPackets = @(Invoke-OptimizationFixture 'false')
$optimizedPackets = @(Invoke-OptimizationFixture 'true')
if ($vanillaPackets.Count -ne 2 -or ($vanillaPackets -join '|') -ne ($optimizedPackets -join '|')) {
    throw "Serialized zombie packets differ. See $run"
}
Write-Output 'Original-JAR optimization fixtures passed with defaults and with all five explicit opt-outs. Zombie packet bytes match.'
