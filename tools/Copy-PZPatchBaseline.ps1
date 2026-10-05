<#
.SYNOPSIS
    Copies one version-specific vanilla Java source into src and commits that baseline.

.DESCRIPTION
    Resolves a Java source relative to <version>/decompiled/zombie, copies it to the
    identical path under <version>/src/zombie, verifies the copied bytes, stages only
    the destination, and creates a local git commit containing only that destination.

    An existing destination is accepted only when its SHA-256 hash still matches the
    vanilla source, allowing recovery after an interrupted commit. A changed destination
    is never overwritten. The script never pushes.

.PARAMETER Version
    Exact version directory, such as 42.21.0 or 42.19.0-client.

.PARAMETER RelativePath
    Java source path relative to decompiled/zombie, such as iso/IsoGridSquare.java.

.PARAMETER CommitMessage
    Optional commit subject. By default, the script identifies the version and source.

.EXAMPLE
    .\tools\Copy-PZPatchBaseline.ps1 -Version 42.21.0 -RelativePath "iso/IsoGridSquare.java"

.EXAMPLE
    .\tools\Copy-PZPatchBaseline.ps1 -Version 42.21.0 -RelativePath "network/GameServer.java" -CommitMessage "Add 42.21.0 GameServer baseline"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$RelativePath,

    [string]$CommitMessage = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$AllowOutput
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & git -C $script:RepoRoot @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        $rendered = $Arguments -join " "
        throw "git $rendered failed:`n$($output -join [Environment]::NewLine)"
    }

    if ($AllowOutput) {
        return @($output)
    }
}

function Resolve-PathInsideRoot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root,

        [Parameter(Mandatory = $true)]
        [string]$Child
    )

    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $rootFull $Child))
    $prefix = $rootFull + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes the allowed root: $Child"
    }

    return $candidate
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$gitRoot = (Invoke-Git -Arguments @("rev-parse", "--show-toplevel") -AllowOutput | Select-Object -First 1).ToString().Trim()
if ([System.IO.Path]::GetFullPath($gitRoot).TrimEnd('\', '/') -ne $RepoRoot.TrimEnd('\', '/')) {
    throw "Script repository root mismatch. Expected '$RepoRoot', git reported '$gitRoot'."
}

if ($Version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9._-]+)?$') {
    throw "Version must be an exact version directory name, such as 42.21.0 or 42.19.0-client."
}

$versionRoot = Resolve-PathInsideRoot -Root $RepoRoot -Child $Version
if (-not (Test-Path -LiteralPath $versionRoot -PathType Container)) {
    throw "Version directory not found: $versionRoot"
}

$normalizedRelativePath = $RelativePath.Replace('\', '/').TrimStart('/')
if ([System.IO.Path]::IsPathRooted($RelativePath) -or [string]::IsNullOrWhiteSpace($normalizedRelativePath)) {
    throw "RelativePath must be relative to <version>/decompiled/zombie."
}
if ([System.IO.Path]::GetExtension($normalizedRelativePath) -ine ".java") {
    throw "RelativePath must identify a .java source file."
}

$vanillaRoot = Join-Path $versionRoot "decompiled\zombie"
$patchRoot = Join-Path $versionRoot "src\zombie"
if (-not (Test-Path -LiteralPath $vanillaRoot -PathType Container)) {
    throw "Vanilla source root not found: $vanillaRoot"
}

$source = Resolve-PathInsideRoot -Root $vanillaRoot -Child $normalizedRelativePath
$destination = Resolve-PathInsideRoot -Root $patchRoot -Child $normalizedRelativePath
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    throw "Vanilla source file not found: $source"
}
$sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
if (Test-Path -LiteralPath $destination) {
    $destinationHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    if ($sourceHash -ne $destinationHash) {
        throw "Destination already exists with changes and will not be overwritten: $destination"
    }

    Write-Host "Destination already contains the exact vanilla baseline. Resuming staging and commit." -ForegroundColor Yellow
} else {
    $destinationDirectory = Split-Path -Parent $destination
    if (-not (Test-Path -LiteralPath $destinationDirectory -PathType Container)) {
        New-Item -Path $destinationDirectory -ItemType Directory -Force | Out-Null
    }

    Copy-Item -LiteralPath $source -Destination $destination
    $destinationHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    if ($sourceHash -ne $destinationHash) {
        Remove-Item -LiteralPath $destination -Force
        throw "Copied file hash does not match the vanilla source. The destination was removed."
    }
}

$repoPrefix = $RepoRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
$gitPath = $destination.Substring($repoPrefix.Length).Replace('\', '/')
Invoke-Git -Arguments @("add", "--", $gitPath)
$stagedForDestination = @(Invoke-Git -Arguments @("diff", "--cached", "--name-only", "--", $gitPath) -AllowOutput)
if ($stagedForDestination.Count -ne 1 -or $stagedForDestination[0].ToString().Trim() -ne $gitPath) {
    throw "Expected exactly one staged destination, but git did not report '$gitPath'."
}

if ([string]::IsNullOrWhiteSpace($CommitMessage)) {
    $CommitMessage = "Add $Version vanilla baseline for zombie/$normalizedRelativePath"
}
if ($CommitMessage.Contains("`r") -or $CommitMessage.Contains("`n")) {
    throw "CommitMessage must be a single-line subject."
}

Invoke-Git -Arguments @("commit", "--only", "-m", $CommitMessage, "--", $gitPath)
$committedFiles = @(Invoke-Git -Arguments @("show", "--pretty=format:", "--name-only", "HEAD") -AllowOutput |
    ForEach-Object { $_.ToString().Trim() } |
    Where-Object { $_ -ne "" })
if ($committedFiles.Count -ne 1 -or $committedFiles[0] -ne $gitPath) {
    throw "Commit was created, but HEAD does not contain exactly the expected file '$gitPath'. Inspect git history before continuing."
}

$commit = (Invoke-Git -Arguments @("rev-parse", "--short", "HEAD") -AllowOutput | Select-Object -First 1).ToString().Trim()
Write-Host "Copied and committed vanilla baseline." -ForegroundColor Green
Write-Host "  Source:      $source"
Write-Host "  Destination: $destination"
Write-Host "  SHA-256:     $destinationHash"
Write-Host "  Commit:      $commit"
Write-Host "No push was performed." -ForegroundColor Yellow
