param(
    [string]$JarPath = "Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar",
    [string]$PatchRoot = (Join-Path -Path $PSScriptRoot -ChildPath "42.19.0-client"),
    [string]$ClassesRoot = "",
    [switch]$DryRun,
    [switch]$OverwriteBackup,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

function Resolve-ExistingPath {
    param([string]$Path, [string]$Label)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "$Label path is empty."
    }

    if (!(Test-Path -LiteralPath $Path)) {
        throw "$Label not found: $Path"
    }

    return (Resolve-Path -LiteralPath $Path).Path
}

function Find-JavaTool {
    param([string]$ToolName, [string]$PatchRoot)

    $directCandidate = Join-Path -Path $PatchRoot -ChildPath "jdk\bin\$ToolName.exe"
    if (Test-Path -LiteralPath $directCandidate) {
        return (Resolve-Path -LiteralPath $directCandidate).Path
    }

    $nestedCandidate = Get-ChildItem -LiteralPath (Join-Path -Path $PatchRoot -ChildPath "jdk") -Recurse -Filter "$ToolName.exe" -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($nestedCandidate) {
        return $nestedCandidate.FullName
    }

    $pathCandidate = Get-Command "$ToolName.exe" -ErrorAction SilentlyContinue
    if ($pathCandidate) {
        return $pathCandidate.Source
    }

    return $null
}

function Compile-PatchedClasses {
    param([string]$JarPath, [string]$PatchRoot, [string]$OutputRoot)

    $srcRoot = Join-Path -Path $PatchRoot -ChildPath "src"
    $zombieSrcRoot = Join-Path -Path $srcRoot -ChildPath "zombie"
    $javac = Find-JavaTool -ToolName "javac" -PatchRoot $PatchRoot

    if (!$javac) {
        throw "javac was not found. Install JDK 25 or place it under $PatchRoot\jdk."
    }

    if (!(Test-Path -LiteralPath $zombieSrcRoot)) {
        throw "Patched Java source folder not found: $zombieSrcRoot"
    }

    $sources = @(Get-ChildItem -LiteralPath $zombieSrcRoot -Recurse -Filter "*.java" -File | Sort-Object FullName | ForEach-Object { $_.FullName })
    if ($sources.Count -eq 0) {
        throw "No patched Java sources found under $zombieSrcRoot"
    }

    New-Item -Path $OutputRoot -ItemType Directory -Force | Out-Null

    Write-Host "[*] Compiling $($sources.Count) patched Java source files..." -ForegroundColor Cyan
    $javacArgs = @(
        "--release", "25",
        "-Xlint:none",
        "-implicit:none",
        "-cp", $JarPath,
        "-sourcepath", $srcRoot,
        "-d", $OutputRoot,
        "-encoding", "UTF-8"
    ) + $sources

    & $javac @javacArgs
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE."
    }

    return $OutputRoot
}

function Get-ClassFiles {
    param([string]$ClassesRoot)

    $classesRootPath = Resolve-ExistingPath -Path $ClassesRoot -Label "ClassesRoot"
    $classFiles = @(Get-ChildItem -LiteralPath $classesRootPath -Recurse -Filter "*.class" -File | Sort-Object FullName)

    if ($classFiles.Count -eq 0) {
        throw "No .class files found under $classesRootPath"
    }

    foreach ($classFile in $classFiles) {
        $relative = $classFile.FullName.Substring($classesRootPath.Length).TrimStart('\', '/')
        if ($relative -notlike "zombie\*" -and $relative -notlike "zombie/*") {
            throw "Unexpected class outside the zombie package tree: $relative"
        }
    }

    return [pscustomobject]@{ ClassesRoot = $classesRootPath; ClassFiles = $classFiles }
}

function Update-JarWithClasses {
    param([string]$JarCopyPath, [string]$ClassesRoot, [object[]]$ClassFiles)

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $fileStream = [System.IO.File]::Open($JarCopyPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    try {
        $zip = New-Object System.IO.Compression.ZipArchive($fileStream, [System.IO.Compression.ZipArchiveMode]::Update, $false)
        try {
            foreach ($classFile in $ClassFiles) {
                $relative = $classFile.FullName.Substring($ClassesRoot.Length).TrimStart('\', '/')
                $entryName = $relative.Replace('\', '/')
                $existing = $zip.GetEntry($entryName)
                if ($existing) {
                    $existing.Delete()
                }

                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                    $zip,
                    $classFile.FullName,
                    $entryName,
                    [System.IO.Compression.CompressionLevel]::Optimal
                ) | Out-Null
            }
        } finally {
            $zip.Dispose()
        }
    } finally {
        $fileStream.Dispose()
    }
}

$jarPath = [System.IO.Path]::GetFullPath($JarPath)
$backupPath = "$jarPath.backup"

if ($Revert) {
    if (!(Test-Path -LiteralPath $backupPath)) {
        throw "Backup jar not found: $backupPath"
    }

    Write-Host "[*] Restoring backup jar..." -ForegroundColor Yellow
    Write-Host "    Backup: $backupPath" -ForegroundColor Gray
    Write-Host "    Target: $jarPath" -ForegroundColor Gray

    if ($DryRun) {
        Write-Host "[*] Dry run: no files changed." -ForegroundColor Yellow
        exit 0
    }

    if (Test-Path -LiteralPath $jarPath) {
        Remove-Item -LiteralPath $jarPath -Force
    }
    Move-Item -LiteralPath $backupPath -Destination $jarPath
    Write-Host "[OK] Restored original Project Zomboid jar." -ForegroundColor Green
    exit 0
}

$jarPath = Resolve-ExistingPath -Path $jarPath -Label "Project Zomboid jar"
$patchRoot = Resolve-ExistingPath -Path $PatchRoot -Label "PatchRoot"
$backupPath = "$jarPath.backup"

if ((Test-Path -LiteralPath $backupPath) -and !$OverwriteBackup) {
    throw "Backup already exists: $backupPath. Use -OverwriteBackup only if you intentionally want to replace it."
}

$tempRoot = Join-Path -Path ([System.IO.Path]::GetTempPath()) -ChildPath ("apocbr_pz_jar_patch_" + [System.Diagnostics.Process]::GetCurrentProcess().Id + "_" + [DateTime]::UtcNow.Ticks)
$patchedJarPath = Join-Path -Path $tempRoot -ChildPath "projectzomboid.jar.patched"
$compiledClassesRoot = Join-Path -Path $tempRoot -ChildPath "classes"

try {
    New-Item -Path $tempRoot -ItemType Directory -Force | Out-Null

    if ([string]::IsNullOrWhiteSpace($ClassesRoot)) {
        $effectiveClassesRoot = Compile-PatchedClasses -JarPath $jarPath -PatchRoot $patchRoot -OutputRoot $compiledClassesRoot
    } else {
        $effectiveClassesRoot = Resolve-ExistingPath -Path $ClassesRoot -Label "ClassesRoot"
    }

    $classResult = Get-ClassFiles -ClassesRoot $effectiveClassesRoot
    $effectiveClassesRoot = $classResult.ClassesRoot
    $classFiles = @($classResult.ClassFiles)

    Write-Host "[*] Preparing patched jar with $($classFiles.Count) class files..." -ForegroundColor Cyan
    Write-Host "    Original jar: $jarPath" -ForegroundColor Gray
    Write-Host "    Backup jar:   $backupPath" -ForegroundColor Gray
    Write-Host "    Classes root: $effectiveClassesRoot" -ForegroundColor Gray

    if ($DryRun) {
        Write-Host "" 
        Write-Host "Class entries that would be replaced/added:" -ForegroundColor Yellow
        foreach ($classFile in $classFiles) {
            $relative = $classFile.FullName.Substring($effectiveClassesRoot.Length).TrimStart('\', '/').Replace('\', '/')
            Write-Host "  - $relative" -ForegroundColor Gray
        }
        Write-Host "" 
        Write-Host "[*] Dry run: no files changed." -ForegroundColor Yellow
        exit 0
    }

    Copy-Item -LiteralPath $jarPath -Destination $patchedJarPath -Force
    Update-JarWithClasses -JarCopyPath $patchedJarPath -ClassesRoot $effectiveClassesRoot -ClassFiles $classFiles

    if (Test-Path -LiteralPath $backupPath) {
        Remove-Item -LiteralPath $backupPath -Force
    }

    Move-Item -LiteralPath $jarPath -Destination $backupPath
    Move-Item -LiteralPath $patchedJarPath -Destination $jarPath

    Write-Host "[OK] Patched Project Zomboid jar installed." -ForegroundColor Green
    Write-Host "     Original renamed to: $backupPath" -ForegroundColor Green
    Write-Host "     Patched jar written to: $jarPath" -ForegroundColor Green
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

