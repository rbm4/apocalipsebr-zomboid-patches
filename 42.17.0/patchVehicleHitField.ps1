<#
.SYNOPSIS
    Compiles patched VehicleHitField.java and deploys the .class to Project Zomboid.

.DESCRIPTION
    Vehicle Damage MP Fix - Server-side deduplication for VehicleHitField.

    Prevents per-frame VehicleHitField packets from applying vehicle damage
    multiple times for a single collision event. Only vehicle HP damage is
    deduplicated; character damage (zombie death/knockdown) passes through.

    This script:
    1. Locates or downloads a JDK 25+ compiler (javac)
    2. Backs up the original VehicleHitField.class from projectzomboid.jar
    3. Compiles the patched source against projectzomboid.jar
    4. Deploys the resulting .class files to the PZ game directory
       (classpath override: loose .class files in game root take precedence over JAR)

.PARAMETER PZDir
    Path to the Project Zomboid installation directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER DryRun
    If set, shows what would be done without actually deploying.

.PARAMETER Revert
    If set, removes the deployed .class override (restoring original JAR behavior).

.NOTES
    PZ uses Azul Zulu JDK 25.0.1. The bundled JRE has no javac, so we need a full JDK.
    The script will auto-download Azul Zulu JDK 25 if no suitable compiler is found.

    The dedup cooldown is controlled at runtime by a system property:
      -Dpz.vehicle.hit.dedup.cooldown=3500  → 3500ms cooldown (default)
      -Dpz.vehicle.hit.dedup.cooldown=0     → disable dedup entirely
#>
param(
    [string]$PZDir = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

# --- Configuration ---
$PatchName     = "Vehicle Damage MP Fix - VehicleHitField Dedup"
$GameJar       = Join-Path $PZDir "projectzomboid.jar"
$DeployDir     = Join-Path $PZDir "zombie\network\fields\hit"
$DeployClass   = Join-Path $DeployDir "VehicleHitField.class"
$BackupDir     = Join-Path $ToolsDir "backups"
$BackupClass   = Join-Path $BackupDir "VehicleHitField.class.original"
$LocalJdkDir   = Join-Path $ToolsDir "jdk"
$WorkDir       = Join-Path $env:TEMP "pzpatch_vehiclehitfield"
$OutputDir     = Join-Path $WorkDir "classes"
$RequiredMajor = 25

# Inner class produced by compilation (if any)
$InnerClassPattern = 'VehicleHitField$*.class'

# Azul Zulu JDK 25 download (Windows x64 zip)
$ZuluApiUrl    = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$RequiredMajor&os=windows&arch=x64&archive_type=zip&java_package_type=jdk&latest=true"

# --- Functions ---
function Get-JavacVersion {
    param([string]$JavacPath)
    try {
        $output = & $JavacPath -version 2>&1 | Out-String
        if ($output -match "javac\s+(\d+)") {
            return [int]$Matches[1]
        }
    } catch {}
    return 0
}

function Find-Javac {
    Write-Host "[*] Searching for javac >= $RequiredMajor..." -ForegroundColor Cyan

    # 1. Check local JDK folder (from previous download)
    $localJavac = Join-Path $LocalJdkDir "bin\javac.exe"
    if (Test-Path $localJavac) {
        $ver = Get-JavacVersion $localJavac
        if ($ver -ge $RequiredMajor) {
            Write-Host "    Found local JDK: javac $ver at $localJavac" -ForegroundColor Green
            return $localJavac
        }
    }

    # 2. Check PATH
    $pathJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($pathJavac) {
        $ver = Get-JavacVersion $pathJavac.Source
        if ($ver -ge $RequiredMajor) {
            Write-Host "    Found in PATH: javac $ver at $($pathJavac.Source)" -ForegroundColor Green
            return $pathJavac.Source
        } else {
            Write-Host "    Found javac $ver in PATH (need >= $RequiredMajor, skipping)" -ForegroundColor Yellow
        }
    }

    # 3. Check common install locations
    $searchPaths = @(
        "C:\Program Files\Zulu\zulu-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Eclipse Adoptium\jdk-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Java\jdk-$RequiredMajor*\bin\javac.exe",
        "C:\Program Files\Microsoft\jdk-$RequiredMajor*\bin\javac.exe"
    )
    foreach ($pattern in $searchPaths) {
        $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            $ver = Get-JavacVersion $found.FullName
            if ($ver -ge $RequiredMajor) {
                Write-Host "    Found installed: javac $ver at $($found.FullName)" -ForegroundColor Green
                return $found.FullName
            }
        }
    }

    return $null
}

function Install-Jdk {
    Write-Host "[*] Downloading Azul Zulu JDK $RequiredMajor..." -ForegroundColor Cyan

    try {
        $response = Invoke-RestMethod -Uri $ZuluApiUrl -TimeoutSec 30
        $pkg = $response | Select-Object -First 1
        $downloadUrl = $pkg.download_url
    } catch {
        Write-Host "ERROR: Failed to query Azul API: $_" -ForegroundColor Red
        Write-Host "       Download JDK $RequiredMajor manually from https://www.azul.com/downloads/" -ForegroundColor Yellow
        exit 1
    }

    if (-not $downloadUrl) {
        Write-Host "ERROR: No JDK $RequiredMajor package found from Azul API." -ForegroundColor Red
        exit 1
    }

    Write-Host "    URL: $downloadUrl" -ForegroundColor Gray
    $zipPath = Join-Path $ToolsDir "jdk-download.zip"

    Write-Host "    Downloading..." -ForegroundColor Gray
    Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath -UseBasicParsing
    Write-Host "    Download complete: $([math]::Round((Get-Item $zipPath).Length / 1MB, 1)) MB" -ForegroundColor Gray

    Write-Host "    Extracting..." -ForegroundColor Gray
    $extractDir = Join-Path $ToolsDir "jdk-extract"
    if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

    $innerDir = Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1
    if (-not $innerDir) {
        Write-Host "ERROR: Extracted archive is empty." -ForegroundColor Red
        exit 1
    }

    if (Test-Path $LocalJdkDir) { Remove-Item $LocalJdkDir -Recurse -Force }
    Move-Item $innerDir.FullName $LocalJdkDir

    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue

    $javacPath = Join-Path $LocalJdkDir "bin\javac.exe"
    if (Test-Path $javacPath) {
        $ver = Get-JavacVersion $javacPath
        Write-Host "    Installed: javac $ver at $javacPath" -ForegroundColor Green
        return $javacPath
    } else {
        Write-Host "ERROR: javac not found in downloaded JDK." -ForegroundColor Red
        exit 1
    }
}

function Backup-OriginalClass {
    if (Test-Path $BackupClass) {
        Write-Host "[*] Backup already exists: $BackupClass" -ForegroundColor Gray
        return
    }

    Write-Host "[*] Extracting original VehicleHitField.class from JAR..." -ForegroundColor Cyan
    if (-not (Test-Path $BackupDir)) {
        New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null
    }

    $tempDir = Join-Path $ToolsDir "tmp-extract"
    if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
    New-Item -Path $tempDir -ItemType Directory -Force | Out-Null

    Push-Location $tempDir
    try {
        $javaExe = Join-Path $PZDir "jre64\bin\jar.exe"
        if (-not (Test-Path $javaExe)) {
            $javaExe = "jar"
        }
        & $javaExe xf $GameJar "zombie/network/fields/hit/VehicleHitField.class"
        $extracted = Join-Path $tempDir "zombie\network\fields\hit\VehicleHitField.class"
        if (Test-Path $extracted) {
            Copy-Item $extracted $BackupClass
            Write-Host "    Backed up original: $BackupClass" -ForegroundColor Green
        } else {
            Write-Host "    WARNING: Could not extract original class (may not exist in JAR)." -ForegroundColor Yellow
        }
    } finally {
        Pop-Location
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# --- Main ---
Write-Host ""
Write-Host "=== $PatchName - Build & Deploy ===" -ForegroundColor White
Write-Host ""

# Handle revert
if ($Revert) {
    $reverted = $false
    if (Test-Path $DeployClass) {
        Remove-Item $DeployClass -Force
        Write-Host "    Removed: $DeployClass" -ForegroundColor Green
        $reverted = $true
    }

    # Also remove inner class files
    $innerClasses = Get-ChildItem -Path $DeployDir -Filter "VehicleHitField`$*.class" -ErrorAction SilentlyContinue
    foreach ($ic in $innerClasses) {
        Remove-Item $ic.FullName -Force
        Write-Host "    Removed: $($ic.Name)" -ForegroundColor Green
        $reverted = $true
    }

    if ($reverted) {
        Write-Host ""
        Write-Host "=== Patch reverted ===" -ForegroundColor White
        Write-Host ""
        Write-Host "Original VehicleHitField from JAR will be used on next server start." -ForegroundColor Gray
    } else {
        Write-Host "    No patch files found to remove." -ForegroundColor Yellow
    }
    Write-Host ""
    exit 0
}

# Validate inputs
if (-not (Test-Path $GameJar)) {
    Write-Host "ERROR: Game JAR not found: $GameJar" -ForegroundColor Red
    Write-Host "       Set -PZDir to your ProjectZomboid installation" -ForegroundColor Yellow
    exit 1
}

# Step 1: Backup original class from JAR
Backup-OriginalClass

# Step 2: Find or install JDK
$javac = Find-Javac
if (-not $javac) {
    $javac = Install-Jdk
}

# Step 3: Write patched source to temp directory
Write-Host ""
Write-Host "[*] Writing patched VehicleHitField.java..." -ForegroundColor Cyan
$TempSrcDir = Join-Path $WorkDir "src\zombie\network\fields\hit"
if (-not (Test-Path $TempSrcDir)) {
    New-Item -Path $TempSrcDir -ItemType Directory -Force | Out-Null
}
$TempSourceFile = Join-Path $TempSrcDir "VehicleHitField.java"

$JavaSource = @'
// Patched VehicleHitField.java - Server-side vehicle damage deduplication
// Prevents per-frame packet spam from applying vehicle damage multiple times
// for a single collision event. Character damage (zombie death/knockdown) is
// NOT throttled - only the vehicle HP damage is deduplicated.
//
// Cooldown is configurable via JVM property:
//   -Dpz.vehicle.hit.dedup.cooldown=3500  (ms, default 3500)
//
// Original: zombie.network.fields.hit.VehicleHitField (Build 42)
package zombie.network.fields.hit;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.fields.IMovable;
import zombie.network.fields.INetworkPacketField;
import zombie.vehicles.BaseVehicle;

public class VehicleHitField extends Hit implements IMovable, INetworkPacketField {
    @JSONField
    public int vehicleDamage;
    @JSONField
    public float vehicleSpeed;
    @JSONField
    public boolean isVehicleHitFromBehind;
    @JSONField
    public boolean isTargetHitFromBehind;
    @JSONField
    public boolean isStaggerBack;
    @JSONField
    public boolean isKnockedDown;

    // --- Dedup state (server-side only) ---
    private static final long DEDUP_COOLDOWN_MS =
        Long.getLong("pz.vehicle.hit.dedup.cooldown", 3500L);
    private static final HashMap<Long, Long> recentVehicleDamage = new HashMap<>();
    private static long lastCleanupTime = 0L;
    private static final long CLEANUP_INTERVAL_MS = 10_000L;

    /**
     * Build a dedup key combining vehicle ID and target identity.
     * Upper 32 bits: vehicle network ID (truncated to int).
     * Lower 32 bits: System.identityHashCode of the target character.
     */
    private static long makeDedupKey(BaseVehicle vehicle, IsoGameCharacter target) {
        return ((long)(vehicle.getId() & 0xFFFF) << 32)
             | (System.identityHashCode(target) & 0xFFFFFFFFL);
    }

    /**
     * Returns true if vehicle damage for this (vehicle, target) pair should be
     * suppressed because a hit was already applied within the cooldown window.
     * If not suppressed, records the current timestamp for future checks.
     */
    private static boolean isVehicleDamageThrottled(BaseVehicle vehicle, IsoGameCharacter target) {
        if (DEDUP_COOLDOWN_MS <= 0L) {
            return false;   // dedup disabled
        }
        long now = System.currentTimeMillis();
        long key = makeDedupKey(vehicle, target);
        Long lastHit = recentVehicleDamage.get(key);
        if (lastHit != null && (now - lastHit) < DEDUP_COOLDOWN_MS) {
            return true;    // within cooldown - suppress
        }
        recentVehicleDamage.put(key, now);

        // Periodic cleanup of stale entries
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            lastCleanupTime = now;
            Iterator<Map.Entry<Long, Long>> it = recentVehicleDamage.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > DEDUP_COOLDOWN_MS * 2) {
                    it.remove();
                }
            }
        }
        return false;
    }
    // --- End dedup state ---

    public void set(
        boolean ignore,
        float damage,
        float hitForce,
        float hitDirectionX,
        float hitDirectionY,
        int vehicleDamage,
        float vehicleSpeed,
        boolean isVehicleHitFromBehind,
        boolean isTargetHitFromBehind,
        boolean isStaggerBack,
        boolean isKnockedDown
    ) {
        this.set(damage, hitForce, hitDirectionX, hitDirectionY);
        this.vehicleDamage = vehicleDamage;
        this.vehicleSpeed = vehicleSpeed;
        this.isVehicleHitFromBehind = isVehicleHitFromBehind;
        this.isTargetHitFromBehind = isTargetHitFromBehind;
        this.isStaggerBack = isStaggerBack;
        this.isKnockedDown = isKnockedDown;
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.vehicleDamage = b.getInt();
        this.vehicleSpeed = b.getFloat();
        this.isVehicleHitFromBehind = b.getBoolean();
        this.isTargetHitFromBehind = b.getBoolean();
        this.isStaggerBack = b.getBoolean();
        this.isKnockedDown = b.getBoolean();
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        b.putInt(this.vehicleDamage);
        b.putFloat(this.vehicleSpeed);
        b.putBoolean(this.isVehicleHitFromBehind);
        b.putBoolean(this.isTargetHitFromBehind);
        b.putBoolean(this.isStaggerBack);
        b.putBoolean(this.isKnockedDown);
    }

    public void process(IsoGameCharacter wielder, IsoGameCharacter target, BaseVehicle vehicle) {
        this.process(wielder, target);
        if (GameServer.server) {
            // --- PATCHED: vehicle damage dedup ---
            if (this.vehicleDamage != 0 && !isVehicleDamageThrottled(vehicle, target)) {
                if (this.isVehicleHitFromBehind) {
                    vehicle.addDamageFrontHitAChr(this.vehicleDamage);
                } else {
                    vehicle.addDamageRearHitAChr(this.vehicleDamage);
                }

                vehicle.transmitBlood();
            }
            // --- END PATCHED ---

            // Character damage is NOT throttled - zombie death/knockdown must apply
            if (target instanceof IsoAnimal isoAnimal) {
                isoAnimal.setHealth(0.0F);
            } else if (target instanceof IsoZombie isoZombie) {
                isoZombie.applyDamageFromVehicleHit(this.vehicleSpeed, this.damage);
                isoZombie.setKnockedDown(this.isKnockedDown);
                isoZombie.setStaggerBack(this.isStaggerBack);
            } else if (target instanceof IsoPlayer isoPlayer) {
                isoPlayer.applyDamageFromVehicleHit(this.vehicleSpeed, this.damage);
                isoPlayer.setKnockedDown(this.isKnockedDown);
            }
        } else if (GameClient.client && target instanceof IsoPlayer) {
            target.getActionContext().reportEvent("washit");
            target.setVariable("hitpvp", false);
        }
    }

    @Override
    public float getSpeed() {
        return this.vehicleSpeed;
    }

    @Override
    public boolean isVehicle() {
        return true;
    }
}
'@

[System.IO.File]::WriteAllText($TempSourceFile, $JavaSource, [System.Text.UTF8Encoding]::new($false))
Write-Host "    Written to: $TempSourceFile" -ForegroundColor Green

# Step 4: Compile
Write-Host ""
Write-Host "[*] Compiling patched VehicleHitField.java..." -ForegroundColor Cyan
if (-not (Test-Path $OutputDir)) {
    New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null
}

$javacArgs = @(
    "-cp", $GameJar,
    "-d", $OutputDir,
    "-encoding", "UTF-8",
    "-source", "25",
    "-target", "25",
    $TempSourceFile
)

Write-Host "    javac $($javacArgs -join ' ')" -ForegroundColor Gray
& $javac @javacArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: Compilation failed (exit code $LASTEXITCODE)." -ForegroundColor Red
    Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}

$compiledClass = Join-Path $OutputDir "zombie\network\fields\hit\VehicleHitField.class"
if (-not (Test-Path $compiledClass)) {
    Write-Host "ERROR: Expected output not found: $compiledClass" -ForegroundColor Red
    exit 1
}

Write-Host "    Compiled successfully." -ForegroundColor Green

# Step 5: Deploy
Write-Host ""
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy to $DeployDir\" -ForegroundColor Yellow
    $compiledDir = Join-Path $OutputDir "zombie\network\fields\hit"
    Get-ChildItem -Path $compiledDir -Filter "VehicleHitField*.class" | ForEach-Object {
        Write-Host "    Would copy: $($_.Name)" -ForegroundColor Yellow
    }
} else {
    Write-Host "[*] Deploying..." -ForegroundColor Cyan

    # Create target directory in PZ root
    if (-not (Test-Path $DeployDir)) {
        New-Item -Path $DeployDir -ItemType Directory -Force | Out-Null
    }

    # Backup existing override if present
    if (Test-Path $DeployClass) {
        $ts = Get-Date -Format "yyyyMMdd_HHmmss"
        $prev = Join-Path $BackupDir "VehicleHitField.class.prev_$ts"
        Copy-Item $DeployClass $prev
        Write-Host "    Previous override backed up to: $prev" -ForegroundColor Gray
    }

    # Deploy patched class and inner classes
    $compiledDir = Join-Path $OutputDir "zombie\network\fields\hit"
    Get-ChildItem -Path $compiledDir -Filter "VehicleHitField*.class" | ForEach-Object {
        Copy-Item $_.FullName (Join-Path $DeployDir $_.Name) -Force
        Write-Host "    Deployed: $($_.Name)" -ForegroundColor Green
    }
}

# Done
Write-Host ""
Write-Host "=== Done ===" -ForegroundColor White
Write-Host ""
Write-Host "Patch deployed: $PatchName" -ForegroundColor Green
Write-Host ""
Write-Host "How it works:" -ForegroundColor Gray
Write-Host "  PZ classpath is ['.', 'projectzomboid.jar'], so the loose .class" -ForegroundColor Gray
Write-Host "  at '$DeployDir' takes precedence over the one inside the JAR." -ForegroundColor Gray
Write-Host ""
Write-Host "  Vehicle damage from VehicleHitField packets is deduplicated with" -ForegroundColor Gray
Write-Host "  a 3500ms cooldown per (vehicle, target) pair. This prevents the" -ForegroundColor Gray
Write-Host "  per-frame packet spam from multiplying vehicle damage by 3-5x." -ForegroundColor Gray
Write-Host "  Character damage (zombie death/knockdown) is NOT affected." -ForegroundColor Gray
Write-Host ""
Write-Host "  To customize dedup cooldown at runtime, add to JVM args:" -ForegroundColor Yellow
Write-Host "    -Dpz.vehicle.hit.dedup.cooldown=3500   (ms, default 3500)" -ForegroundColor Yellow
Write-Host "    -Dpz.vehicle.hit.dedup.cooldown=0      (disable dedup entirely)" -ForegroundColor Yellow
Write-Host ""
Write-Host "  To revert entirely:" -ForegroundColor Yellow
Write-Host "    .\patchVehicleHitField.ps1 -Revert" -ForegroundColor Yellow
Write-Host "    (or delete all VehicleHitField*.class from: $DeployDir)" -ForegroundColor Yellow
Write-Host ""

# Cleanup
Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
