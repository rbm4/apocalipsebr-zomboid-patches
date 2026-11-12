<# Compiles the client telemetry scaffold against the pinned 42.22.2 original JAR. Never deploys. #>
param(
    [string]$PZDir = 'Z:\SteamLibrary\steamapps\common\ProjectZomboid',
    [string]$ArtifactDir
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$javac = Join-Path $repoRoot 'jdk/bin/javac.exe'
$gameJar = Join-Path $PZDir 'projectzomboid.jar'
if (-not (Test-Path -LiteralPath $gameJar)) { $gameJar = Join-Path $PZDir 'java/projectzomboid.jar' }
if (-not (Test-Path -LiteralPath $gameJar)) { throw 'Original projectzomboid.jar not found.' }
if (-not (Test-Path -LiteralPath $javac)) { throw 'Repository JDK 25 required at jdk/bin/javac.exe.' }
$expectedHash = '3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287'
$actualHash = (Get-FileHash -LiteralPath $gameJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $expectedHash) { throw 'Game JAR differs from the verified 42.22.2 baseline. Refusing whole-class build.' }
if (-not $ArtifactDir) {
    $ArtifactDir = Join-Path $PSScriptRoot ('artifacts/build-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff'))
}
$ArtifactDir = [IO.Path]::GetFullPath($ArtifactDir)
if (Test-Path -LiteralPath $ArtifactDir) { throw "Artifact directory already exists: $ArtifactDir" }
$classes = Join-Path $ArtifactDir 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' -File | Select-Object -ExpandProperty FullName)
& $javac --release 25 -encoding UTF-8 -implicit:none -cp $gameJar -d $classes @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $ArtifactDir 'compiler.log')
if ($LASTEXITCODE -ne 0) { throw "Client telemetry compilation failed. See $ArtifactDir/compiler.log" }
$count = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' -File).Count
@{
    target = '42.22.2-client'; jar_sha256 = $actualHash; source_count = $sources.Count
    class_count = $count; deployment = 'not performed'; runtime_validation = 'not performed'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ArtifactDir 'receipt.json') -Encoding UTF8
Write-Output "Compiled $($sources.Count) sources / $count classes into $classes"
Write-Output 'Build only: no game files or launchers changed.'
