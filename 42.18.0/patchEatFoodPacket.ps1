<#
.SYNOPSIS
    Compiles patched EatFoodPacket.java and deploys the .class to Project Zomboid.

.DESCRIPTION
    Nutrition Calorie Corruption Fix - EatFoodPacket

    Root cause: In multiplayer, the server runs Nutrition.update() every tick to decay
    calories and compute weight gain. The client does NOT decay calories (gated by
    !GameClient.client in Nutrition.update()). On every eat, the client sends
    EatFoodPacket containing its local (non-decaying, inflated) calories. The original
    parse() called nutrition.load() on the server unconditionally, overwriting the
    server's correctly-decayed state with the client's stale higher value on every
    meal. This caused the server to perpetually see a calorie surplus -> perpetual
    weight gain regardless of how much or little the player ate.

    This patch makes two minimal changes to EatFoodPacket:

    1. parse(): when running server-side, skip the client's nutrition blob (advance
       the buffer past the 20 bytes) instead of loading it. The server's authoritative
       nutrition is no longer corrupted by incoming client packets.

    2. processServer(): apply the food nutrition delta directly to the server's
       nutrition object, mirroring the nutrition portion of IsoGameCharacter.Eat().
       This replaces the data that was previously (incorrectly) sourced from the client.

    The write() / client-side parse() path is unchanged: when the server sends
    EatFoodPacket to clients it still serialises server nutrition, and clients still
    load it correctly. This means a Lua periodic sync (sendPlayerNutrition) can push
    server weight to clients without any additional Java changes.

    This script:
    1. Locates or downloads a JDK 25+ compiler (javac)
    2. Backs up the original EatFoodPacket.class from projectzomboid.jar
    3. Compiles the patched source against projectzomboid.jar
    4. Deploys the resulting .class file to the PZ game directory
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
#>
param(
    [string]$PZDir = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

# --- Configuration ---
$PatchName     = "Nutrition Calorie Corruption Fix - EatFoodPacket"
$GameJar       = Join-Path $PZDir "projectzomboid.jar"
$DeployDir     = Join-Path $PZDir "zombie\network\packets\actions"
$DeployClass   = Join-Path $DeployDir "EatFoodPacket.class"
$BackupDir     = Join-Path $ToolsDir "backups"
$BackupClass   = Join-Path $BackupDir "EatFoodPacket.class.original"
$LocalJdkDir   = Join-Path $ToolsDir "jdk"
$WorkDir       = Join-Path $env:TEMP "pzpatch_eatfoodpacket"
$OutputDir     = Join-Path $WorkDir "classes"
$RequiredMajor = 25

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

    Write-Host "[*] Extracting original EatFoodPacket.class from JAR..." -ForegroundColor Cyan
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
        & $javaExe xf $GameJar "zombie/network/packets/actions/EatFoodPacket.class"
        $extracted = Join-Path $tempDir "zombie\network\packets\actions\EatFoodPacket.class"
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

    if ($reverted) {
        Write-Host ""
        Write-Host "=== Patch reverted ===" -ForegroundColor White
        Write-Host ""
        Write-Host "Original EatFoodPacket from JAR will be used on next server start." -ForegroundColor Gray
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
Write-Host "[*] Writing patched EatFoodPacket.java..." -ForegroundColor Cyan
$TempSrcDir = Join-Path $WorkDir "src\zombie\network\packets\actions"
if (-not (Test-Path $TempSrcDir)) {
    New-Item -Path $TempSrcDir -ItemType Directory -Force | Out-Null
}
$TempSourceFile = Join-Path $TempSrcDir "EatFoodPacket.java"

$JavaSource = @'
// Patched EatFoodPacket.java - Nutrition calorie corruption fix.
//
// Root cause: In multiplayer, the server runs Nutrition.update() every tick to decay
// calories and compute weight gain. The client does NOT decay calories (gated by
// !GameClient.client in Nutrition.update()). On every eat, the client sends
// EatFoodPacket containing its local (non-decaying, inflated) calories. The original
// parse() called nutrition.load() on the server unconditionally, overwriting the
// server's correctly-decayed state with the client's stale higher value on every
// meal. This caused the server to perpetually see a calorie surplus -> perpetual
// weight gain regardless of how much or little the player ate.
//
// Fix:
//   1. parse(): when running server-side, skip the client's nutrition blob (advance
//      the buffer past the 20 bytes) instead of loading it. Server nutrition is preserved.
//   2. processServer(): apply the food nutrition delta directly to the server's
//      nutrition object, mirroring the nutrition portion of IsoGameCharacter.Eat().
//
// Original: zombie.network.packets.actions.EatFoodPacket (Build 42.17)
package zombie.network.packets.actions;

import java.io.IOException;
import zombie.SandboxOptions;
import zombie.characters.BodyDamage.Nutrition;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.Food;
import zombie.network.GameClient;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class EatFoodPacket implements INetworkPacket {
    // Nutrition.save()/load() serialises 5 floats: calories, proteins, lipids, carbs, weight.
    private static final int NUTRITION_BLOB_BYTES = 5 * Float.BYTES;

    @JSONField
    PlayerID player = new PlayerID();
    @JSONField
    float percentage;
    @JSONField
    Food food;

    @Override
    public void setData(Object... values) {
        if (values.length == 3 && values[0] instanceof IsoPlayer) {
            this.set((IsoPlayer)values[0], (Food)values[1], (Float)values[2]);
        } else {
            DebugType.Multiplayer.warn(this.getClass().getSimpleName() + ".set get invalid arguments");
        }
    }

    public void set(IsoPlayer player, Food food, float percentage) {
        this.player.set(player);
        this.percentage = percentage;
        this.food = food;
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        this.percentage = b.getFloat();
        if (GameClient.client) {
            // Client receiving server's authoritative nutrition — sync client state.
            this.player.getPlayer().getNutrition().load(b.bb);
        } else {
            // PATCHED: Server receiving client's packet.
            // Skip the nutrition blob — server maintains its own authoritative state.
            // Original: this.player.getPlayer().getNutrition().load(b.bb)
            // That overwrote the server's correctly-decayed calories with the client's
            // frozen (non-decaying) value on every eat event, causing perpetual weight gain.
            b.bb.position(b.bb.position() + NUTRITION_BLOB_BYTES);
        }

        try {
            this.food = (Food)InventoryItem.loadItem(b.bb, 245);
        } catch (Exception var4) {
            DebugType.General.printException(var4, LogSeverity.Error);
            this.food = null;
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        try {
            this.player.write(b);
            b.putFloat(this.percentage);
            this.player.getPlayer().getNutrition().save(b.bb);
            this.food.saveWithSize(b.bb, false);
        } catch (IOException var3) {
            DebugType.General.printException(var3, LogSeverity.Error);
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        this.player.getPlayer().EatOnClient(this.food, this.percentage);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (this.isConsistent(connection)) {
            // PATCHED: Apply food nutrition delta to the server's authoritative nutrition.
            // Original only called processClient() (EatOnClient -> Lua onEat callback only).
            // Without this, skipping nutrition.load() above would mean the server never
            // accumulates calories from eating at all.
            IsoPlayer player = this.player.getPlayer();
            if (player != null && this.food != null && SandboxOptions.instance.nutrition.getValue()) {
                Nutrition nutrition = player.getNutrition();
                float pct = Math.min(1.0F, Math.max(0.0F, this.percentage));
                if (!this.food.isBurnt()) {
                    nutrition.setCalories(nutrition.getCalories() + this.food.getCalories() * pct);
                    nutrition.setCarbohydrates(nutrition.getCarbohydrates() + this.food.getCarbohydrates() * pct);
                    nutrition.setProteins(nutrition.getProteins() + this.food.getProteins() * pct);
                    nutrition.setLipids(nutrition.getLipids() + this.food.getLipids() * pct);
                } else {
                    nutrition.setCalories(nutrition.getCalories() + this.food.getCalories() * pct / 5.0F);
                    nutrition.setCarbohydrates(nutrition.getCarbohydrates() + this.food.getCarbohydrates() * pct / 5.0F);
                    nutrition.setProteins(nutrition.getProteins() + this.food.getProteins() * pct / 5.0F);
                    nutrition.setLipids(nutrition.getLipids() + this.food.getLipids() * pct / 5.0F);
                }
            }
            this.processClient(connection);
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection) && this.food != null && this.percentage >= 0.0F && this.percentage < 100.0F;
    }
}
'@

[System.IO.File]::WriteAllText($TempSourceFile, $JavaSource, [System.Text.UTF8Encoding]::new($false))
Write-Host "    Written to: $TempSourceFile" -ForegroundColor Green

# Step 4: Compile
Write-Host ""
Write-Host "[*] Compiling patched EatFoodPacket.java..." -ForegroundColor Cyan
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

$compiledClass = Join-Path $OutputDir "zombie\network\packets\actions\EatFoodPacket.class"
if (-not (Test-Path $compiledClass)) {
    Write-Host "ERROR: Expected output not found: $compiledClass" -ForegroundColor Red
    exit 1
}

Write-Host "    Compiled successfully." -ForegroundColor Green

# Step 5: Deploy
Write-Host ""
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy to $DeployDir\" -ForegroundColor Yellow
    $compiledDir = Join-Path $OutputDir "zombie\network\packets\actions"
    Get-ChildItem -Path $compiledDir -Filter "EatFoodPacket*.class" | ForEach-Object {
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
        $prev = Join-Path $BackupDir "EatFoodPacket.class.prev_$ts"
        Copy-Item $DeployClass $prev
        Write-Host "    Previous override backed up to: $prev" -ForegroundColor Gray
    }

    # Deploy patched class
    $compiledDir = Join-Path $OutputDir "zombie\network\packets\actions"
    Get-ChildItem -Path $compiledDir -Filter "EatFoodPacket*.class" | ForEach-Object {
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
Write-Host "  EatFoodPacket.parse() on the server now skips the client's nutrition" -ForegroundColor Gray
Write-Host "  blob instead of loading it. The server's decayed calories are preserved." -ForegroundColor Gray
Write-Host "  EatFoodPacket.processServer() applies the food delta directly to the" -ForegroundColor Gray
Write-Host "  server's authoritative nutrition, replacing the previously-corrupt source." -ForegroundColor Gray
Write-Host ""
Write-Host "  Client weight display still needs a Lua periodic sync to stay current." -ForegroundColor Yellow
Write-Host "  Use sendPlayerNutrition(player) or syncPlayerStats(player, -1) server-side." -ForegroundColor Yellow
Write-Host ""
Write-Host "  To revert entirely:" -ForegroundColor Yellow
Write-Host "    .\patchEatFoodPacket.ps1 -Revert" -ForegroundColor Yellow
Write-Host "    (or delete EatFoodPacket.class from: $DeployDir)" -ForegroundColor Yellow
Write-Host ""

# Cleanup
Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
