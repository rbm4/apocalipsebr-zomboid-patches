<#
.SYNOPSIS
    Compiles patched BodyLocationGroup, WornItems, and SyncClothingPacket and
    deploys the .class files to Project Zomboid via classpath override.

.DESCRIPTION
    Null-Safety + Clothing Desync Fix - Three-class patch for Build 42.17.0.

    Null-Safety (BodyLocationGroup + WornItems):
      getLocation() can return null for modded/unregistered clothing slots.
      In the zombie death chain (Kill -> DoZombieInventory -> setFromItemVisuals),
      an NPE prevents isOnKillDone/isOnDeathDone from being set, causing the server
      to retry die() every tick in an infinite error loop.

    Clothing Desync (SyncClothingPacket):
      processServer() echoes the packet back to the SENDING client (passes null to
      sendToClients instead of excluding the sender). This self-echo carries stale
      clothing state which overwrites items added between the original send and the
      echo receipt, causing the "naked player" bug during bandaging/climbing/combat.

    This script:
      1. Locates or downloads a JDK 25+ compiler (javac)
      2. Backs up the original .class files from projectzomboid.jar
      3. Compiles the patched sources against projectzomboid.jar
      4. Deploys the resulting .class files to the PZ game directory
         (classpath override: loose .class files in game root take precedence over JAR)

.PARAMETER PZDir
    Path to the Project Zomboid installation directory.
    Default: Z:\SteamLibrary\steamapps\common\ProjectZomboid

.PARAMETER DryRun
    If set, shows what would be done without actually deploying.

.PARAMETER Revert
    If set, removes the deployed .class overrides (restoring original JAR behavior).

.NOTES
    PZ uses Azul Zulu JDK 25.0.1. The bundled JRE has no javac, so we need a full JDK.
    The script will auto-download Azul Zulu JDK 25 if no suitable compiler is found.

    Game version targeted: 42.17.0
#>
param(
    [string]$PZDir = "Z:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ToolsDir = $PSScriptRoot,
    [switch]$DryRun,
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

# --- Configuration ---
$PatchName       = "Null-Safety + Clothing Desync Fix (BodyLocationGroup + WornItems + SyncClothingPacket)"
$GameJar         = Join-Path $PZDir "projectzomboid.jar"
$DeployDirWorn   = Join-Path $PZDir "zombie\characters\WornItems"
$DeployDirNet    = Join-Path $PZDir "zombie\network\packets"
$BackupDir       = Join-Path $ToolsDir "backups"
$LocalJdkDir     = Join-Path $ToolsDir "jdk"
$WorkDir         = Join-Path $env:TEMP "pzpatch_nullsafety"
$OutputDir       = Join-Path $WorkDir "build"
$RequiredMajor   = 25

# Azul Zulu JDK 25 download (Windows x64 zip)
$ZuluApiUrl = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$RequiredMajor&os=windows&arch=x64&archive_type=zip&java_package_type=jdk&latest=true"

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
    param(
        [string]$JarEntry,   # e.g. "zombie/characters/WornItems/BodyLocationGroup.class"
        [string]$BackupFile, # e.g. "$BackupDir\BodyLocationGroup.class.original"
        [string]$Label       # human-readable name
    )

    if (Test-Path $BackupFile) {
        Write-Host "    Backup already exists: $BackupFile" -ForegroundColor Gray
        return
    }

    Write-Host "    Extracting original $Label.class from JAR..." -ForegroundColor Cyan
    if (-not (Test-Path $BackupDir)) {
        New-Item -Path $BackupDir -ItemType Directory -Force | Out-Null
    }

    $tempDir = Join-Path $ToolsDir "tmp-extract-ns"
    if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
    New-Item -Path $tempDir -ItemType Directory -Force | Out-Null

    Push-Location $tempDir
    try {
        $jarExe = Join-Path $PZDir "jre64\bin\jar.exe"
        if (-not (Test-Path $jarExe)) { $jarExe = "jar" }
        & $jarExe xf $GameJar $JarEntry
        $extracted = Join-Path $tempDir ($JarEntry -replace '/', '\')
        if (Test-Path $extracted) {
            Copy-Item $extracted $BackupFile
            Write-Host "    Backed up: $BackupFile" -ForegroundColor Green
        } else {
            Write-Host "    WARNING: Could not extract $Label (may not exist in JAR)." -ForegroundColor Yellow
        }
    } finally {
        Pop-Location
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# --- Main ---
Write-Host ""
Write-Host "=== $PatchName ===" -ForegroundColor White
Write-Host "=== Build 42.17.0 ===" -ForegroundColor White
Write-Host ""

# Handle --revert
if ($Revert) {
    $reverted = $false

    foreach ($entry in @(
        @{ Dir = $DeployDirWorn; Base = "BodyLocationGroup" },
        @{ Dir = $DeployDirWorn; Base = "WornItems" },
        @{ Dir = $DeployDirNet;  Base = "SyncClothingPacket" }
    )) {
        $main = Join-Path $entry.Dir "$($entry.Base).class"
        if (Test-Path $main) {
            Remove-Item $main -Force
            Write-Host "    Removed: $main" -ForegroundColor Green
            $reverted = $true
        }
        Get-ChildItem -Path $entry.Dir -Filter "$($entry.Base)`$*.class" -ErrorAction SilentlyContinue | ForEach-Object {
            Remove-Item $_.FullName -Force
            Write-Host "    Removed: $($_.Name)" -ForegroundColor Green
            $reverted = $true
        }
    }

    if ($reverted) {
        Write-Host ""
        Write-Host "=== Patch reverted ===" -ForegroundColor White
        Write-Host "Original classes from JAR will be used on next server start." -ForegroundColor Gray
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

# Step 1: Backup original classes from JAR
Write-Host "[*] Backing up original classes..." -ForegroundColor Cyan
Backup-OriginalClass "zombie/characters/WornItems/BodyLocationGroup.class" (Join-Path $BackupDir "BodyLocationGroup.class.original") "BodyLocationGroup"
Backup-OriginalClass "zombie/characters/WornItems/WornItems.class"         (Join-Path $BackupDir "WornItems.class.original")         "WornItems"
Backup-OriginalClass "zombie/network/packets/SyncClothingPacket.class"     (Join-Path $BackupDir "SyncClothingPacket.class.original") "SyncClothingPacket"

# Step 2: Find or install JDK
$javac = Find-Javac
if (-not $javac) {
    $javac = Install-Jdk
}

# Step 3: Write patched sources
Write-Host ""
Write-Host "[*] Writing patched Java sources..." -ForegroundColor Cyan

$SrcDirWorn = Join-Path $WorkDir "src\zombie\characters\WornItems"
$SrcDirNet  = Join-Path $WorkDir "src\zombie\network\packets"
New-Item -Path $SrcDirWorn -ItemType Directory -Force | Out-Null
New-Item -Path $SrcDirNet  -ItemType Directory -Force | Out-Null
New-Item -Path $OutputDir  -ItemType Directory -Force | Out-Null

# --- BodyLocationGroup.java ---
$SrcBodyLocationGroup = Join-Path $SrcDirWorn "BodyLocationGroup.java"
$JavaSourceBodyLocationGroup = @'
package zombie.characters.WornItems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import zombie.UsedFromLua;
import zombie.scripting.objects.ItemBodyLocation;

@UsedFromLua
public class BodyLocationGroup {
    private final String id;
    private final List<BodyLocation> locations = new ArrayList<>();

    public BodyLocationGroup(String id) {
        if (id == null) {
            throw new NullPointerException("id is null");
        } else if (id.isEmpty()) {
            throw new IllegalArgumentException("id is empty");
        } else {
            this.id = id;
        }
    }

    public String getId() {
        return this.id;
    }

    public BodyLocation getLocation(ItemBodyLocation itemBodyLocation) {
        for (int i = 0; i < this.locations.size(); i++) {
            BodyLocation location = this.locations.get(i);
            if (location.isId(itemBodyLocation)) {
                return location;
            }
        }

        return null;
    }

    public BodyLocation getOrCreateLocation(ItemBodyLocation itemBodyLocation) {
        BodyLocation bodyLocation = this.getLocation(itemBodyLocation);
        if (bodyLocation == null) {
            bodyLocation = new BodyLocation(this, itemBodyLocation);
            this.locations.add(bodyLocation);
        }

        return bodyLocation;
    }

    public BodyLocation getLocationByIndex(int index) {
        return index >= 0 && index < this.size() ? this.locations.get(index) : null;
    }

    public void moveLocationToIndex(ItemBodyLocation itemBodyLocation, int index) {
        if (index >= 0 && index < this.size()) {
            for (int i = 0; i < this.locations.size(); i++) {
                BodyLocation location = this.locations.get(i);
                if (location.isId(itemBodyLocation)) {
                    this.locations.add(index, this.locations.remove(i));
                }
            }
        }
    }

    public int size() {
        return this.locations.size();
    }

    // --- PATCHED: null-guard - both locations must resolve before setting exclusive ---
    public void setExclusive(ItemBodyLocation firstId, ItemBodyLocation secondId) {
        BodyLocation first = this.getLocation(firstId);
        BodyLocation second = this.getLocation(secondId);
        if (first == null || second == null) {
            return;
        }
        first.setExclusive(secondId);
        second.setExclusive(firstId);
    }
    // --- END PATCHED ---

    // --- PATCHED: null-guard - return false if location not found ---
    public boolean isExclusive(ItemBodyLocation firstId, ItemBodyLocation secondId) {
        BodyLocation first = this.getLocation(firstId);
        if (first == null) {
            return false;
        }
        return first.isExclusive(secondId);
    }
    // --- END PATCHED ---

    // --- PATCHED: null-guard - return early if location not found ---
    public void setHideModel(ItemBodyLocation firstId, ItemBodyLocation secondId) {
        BodyLocation first = this.getLocation(firstId);
        if (first == null) {
            return;
        }
        first.setHideModel(secondId);
    }
    // --- END PATCHED ---

    // --- PATCHED: null-guard - return false if location not found ---
    public boolean isHideModel(ItemBodyLocation firstId, ItemBodyLocation secondId) {
        BodyLocation first = this.getLocation(firstId);
        if (first == null) {
            return false;
        }
        return first.isHideModel(secondId);
    }
    // --- END PATCHED ---

    // --- PATCHED: null-guard - return early if location not found ---
    public void setAltModel(ItemBodyLocation firstId, ItemBodyLocation secondId) {
        BodyLocation first = this.getLocation(firstId);
        if (first == null) {
            return;
        }
        first.setAltModel(secondId);
    }
    // --- END PATCHED ---

    // --- PATCHED: null-guard - return false if location not found ---
    public boolean isAltModel(ItemBodyLocation firstId, ItemBodyLocation secondId) {
        BodyLocation first = this.getLocation(firstId);
        if (first == null) {
            return false;
        }
        return first.isAltModel(secondId);
    }
    // --- END PATCHED ---

    public int indexOf(ItemBodyLocation locationId) {
        for (int i = 0; i < this.locations.size(); i++) {
            BodyLocation location = this.locations.get(i);
            if (location.isId(locationId)) {
                return i;
            }
        }

        return -1;
    }

    // --- PATCHED: null-guard - return early if location not found ---
    public void setMultiItem(ItemBodyLocation locationId, boolean bMultiItem) {
        BodyLocation location = this.getLocation(locationId);
        if (location == null) {
            return;
        }
        location.setMultiItem(bMultiItem);
    }
    // --- END PATCHED ---

    // --- PATCHED: null-guard - return false if location not found (was line 119 NPE) ---
    public boolean isMultiItem(ItemBodyLocation locationId) {
        BodyLocation location = this.getLocation(locationId);
        if (location == null) {
            return false;
        }
        return location.isMultiItem();
    }
    // --- END PATCHED ---

    public List<BodyLocation> getAllLocations() {
        return Collections.unmodifiableList(this.locations);
    }
}
'@
[System.IO.File]::WriteAllText($SrcBodyLocationGroup, $JavaSourceBodyLocationGroup, [System.Text.UTF8Encoding]::new($false))
Write-Host "    BodyLocationGroup.java" -ForegroundColor Gray

# --- WornItems.java ---
$SrcWornItems = Join-Path $SrcDirWorn "WornItems.java"
$JavaSourceWornItems = @'
package zombie.characters.WornItems;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import zombie.GameWindow;
import zombie.UsedFromLua;
import zombie.core.Color;
import zombie.core.ImmutableColor;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.core.textures.Texture;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Clothing;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ResourceLocation;

@UsedFromLua
public final class WornItems {
    private final BodyLocationGroup group;
    private final List<WornItem> items = new ArrayList<>();

    public WornItems(BodyLocationGroup group) {
        this.group = group;
    }

    public WornItems(WornItems other) {
        this.group = other.group;
        this.copyFrom(other);
    }

    public void copyFrom(WornItems other) {
        if (this.group != other.group) {
            throw new RuntimeException("group=" + this.group.getId() + " other.group=" + other.group.getId());
        } else {
            this.items.clear();
            this.items.addAll(other.items);
        }
    }

    public BodyLocationGroup getBodyLocationGroup() {
        return this.group;
    }

    public WornItem get(int index) {
        return this.items.get(index);
    }

    public void setItem(ItemBodyLocation location, InventoryItem item) {
        // --- PATCHED: null-guard on location parameter ---
        if (location == null) {
            return;
        }
        // --- END PATCHED ---

        if (!this.group.isMultiItem(location)) {
            int index = this.indexOf(location);
            if (index != -1) {
                this.items.remove(index);
            }
        }

        for (int i = 0; i < this.items.size(); i++) {
            WornItem wornItem = this.items.get(i);
            // --- PATCHED: null-guard on wornItem.getLocation() before exclusive check ---
            if (wornItem.getLocation() != null && this.group.isExclusive(location, wornItem.getLocation())) {
                this.items.remove(i--);
            }
            // --- END PATCHED ---
        }

        if (item != null) {
            this.remove(item);
            int insertAt = this.items.size();

            for (int ix = 0; ix < this.items.size(); ix++) {
                WornItem wornItem1 = this.items.get(ix);
                // --- PATCHED: null-guard on wornItem.getLocation() before indexOf comparison ---
                if (wornItem1.getLocation() != null && this.group.indexOf(wornItem1.getLocation()) > this.group.indexOf(location)) {
                    insertAt = ix;
                    break;
                }
                // --- END PATCHED ---
            }

            WornItem wornItem = new WornItem(location, item);
            this.items.add(insertAt, wornItem);
        }
    }

    public InventoryItem getItem(ItemBodyLocation location) {
        int index = this.indexOf(location);
        return index == -1 ? null : this.items.get(index).getItem();
    }

    public InventoryItem getItemById(int id) {
        int index = this.indexOf(id);
        return index == -1 ? null : this.items.get(index).getItem();
    }

    public InventoryItem getItemByIndex(int index) {
        return index >= 0 && index < this.items.size() ? this.items.get(index).getItem() : null;
    }

    public void remove(InventoryItem item) {
        int index = this.indexOf(item);
        if (index != -1) {
            this.items.remove(index);
        }
    }

    public void clear() {
        this.items.clear();
    }

    public ItemBodyLocation getLocation(InventoryItem item) {
        int index = this.indexOf(item);
        return index == -1 ? null : this.items.get(index).getLocation();
    }

    public boolean contains(InventoryItem item) {
        return this.indexOf(item) != -1;
    }

    public int size() {
        return this.items.size();
    }

    public boolean isEmpty() {
        return this.items.isEmpty();
    }

    public void forEach(Consumer<WornItem> c) {
        for (int i = 0; i < this.items.size(); i++) {
            c.accept(this.items.get(i));
        }
    }

    public void setFromItemVisuals(ItemVisuals itemVisuals) {
        this.clear();

        for (int i = 0; i < itemVisuals.size(); i++) {
            ItemVisual itemVisual = itemVisuals.get(i);
            String itemType = itemVisual.getItemType();
            InventoryItem item = InventoryItemFactory.CreateItem(itemType);
            if (item != null) {
                if (item.getVisual() != null) {
                    item.getVisual().copyFrom(itemVisual);
                    item.synchWithVisual();
                }

                // --- PATCHED: resolve body location first; null-check before setItem ---
                // Original called setItem(item.getBodyLocation(), item) directly, which NPEs
                // when getBodyLocation()/canBeEquipped() returns null for modded items.
                ItemBodyLocation bodyLoc;
                if (item instanceof Clothing) {
                    bodyLoc = item.getBodyLocation();
                } else {
                    bodyLoc = item.canBeEquipped();
                }
                if (bodyLoc != null) {
                    this.setItem(bodyLoc, item);
                }
                // --- END PATCHED ---
            }
        }
    }

    public void getItemVisuals(ItemVisuals itemVisuals) {
        itemVisuals.clear();

        for (int i = 0; i < this.items.size(); i++) {
            InventoryItem item = this.items.get(i).getItem();
            ItemVisual itemVisual = item.getVisual();
            if (itemVisual != null) {
                itemVisual.setInventoryItem(item);
                itemVisuals.add(itemVisual);
            }
        }
    }

    public void addItemsToItemContainer(ItemContainer container) {
        for (int i = 0; i < this.items.size(); i++) {
            InventoryItem item = this.items.get(i).getItem();
            int totalHoles = item.getVisual().getHolesNumber();
            item.setConditionNoSound(item.getConditionMax() - totalHoles * 3);
            container.AddItem(item);
        }
    }

    private int indexOf(ItemBodyLocation location) {
        // --- PATCHED: null-guard on location parameter and item.getLocation() ---
        if (location == null) {
            return -1;
        }

        for (int i = 0; i < this.items.size(); i++) {
            WornItem item = this.items.get(i);
            if (item.getLocation() != null && item.getLocation().equals(location)) {
                return i;
            }
        }
        // --- END PATCHED ---

        return -1;
    }

    private int indexOf(int id) {
        for (int i = 0; i < this.items.size(); i++) {
            WornItem item = this.items.get(i);
            if (item.getItem().id == id) {
                return i;
            }
        }

        return -1;
    }

    private int indexOf(InventoryItem item) {
        for (int i = 0; i < this.items.size(); i++) {
            WornItem wornItem = this.items.get(i);
            if (wornItem.getItem() == item) {
                return i;
            }
        }

        return -1;
    }

    public void save(ByteBuffer output) throws IOException {
        // --- PATCHED: count only items with non-null location for the size header ---
        short validCount = 0;
        for (int i = 0; i < this.items.size(); i++) {
            if (this.items.get(i).getLocation() != null) {
                validCount++;
            }
        }
        output.putShort(validCount);

        for (int i = 0; i < this.items.size(); i++) {
            WornItem wornItem = this.items.get(i);
            if (wornItem.getLocation() == null) {
                continue;
            }
            // --- END PATCHED ---
            GameWindow.WriteString(output, wornItem.getLocation().toString());
            GameWindow.WriteString(output, wornItem.getItem().getType());
            GameWindow.WriteString(output, wornItem.getItem().getTex().getName());
            wornItem.getItem().col.save(output);
            output.putInt(wornItem.getItem().getVisual().getBaseTexture());
            output.putInt(wornItem.getItem().getVisual().getTextureChoice());
            ImmutableColor colorTint = wornItem.getItem().getVisual().getTint();
            output.putFloat(colorTint.r);
            output.putFloat(colorTint.g);
            output.putFloat(colorTint.b);
            output.putFloat(colorTint.a);
        }
    }

    public void load(ByteBuffer input, int worldVersion) throws IOException {
        short size = input.getShort();
        this.items.clear();

        for (int i = 0; i < size; i++) {
            String location = GameWindow.ReadString(input);
            String type = GameWindow.ReadString(input);
            String tex = GameWindow.ReadString(input);
            Color color = new Color();
            color.load(input, worldVersion);
            int baseTexture = input.getInt();
            int textureChoice = input.getInt();
            ImmutableColor colorTint = new ImmutableColor(input.getFloat(), input.getFloat(), input.getFloat(), input.getFloat());
            InventoryItem item = InventoryItemFactory.CreateItem(type);
            if (item != null) {
                item.setTexture(Texture.trygetTexture(tex));
                if (item.getTex() == null) {
                    item.setTexture(Texture.getSharedTexture("media/inventory/Question_On.png"));
                }

                String worldTexture = tex.replace("Item_", "media/inventory/world/WItem_");
                worldTexture = worldTexture + ".png";
                item.setWorldTexture(worldTexture);
                item.setColor(color);
                item.getVisual().tint = new ImmutableColor(color);
                item.getVisual().setBaseTexture(baseTexture);
                item.getVisual().setTextureChoice(textureChoice);
                item.getVisual().setTint(colorTint);
                // --- PATCHED: null-guard on resolved body location before adding ---
                ItemBodyLocation bodyLoc = ItemBodyLocation.get(ResourceLocation.of(location));
                if (bodyLoc != null) {
                    this.items.add(new WornItem(bodyLoc, item));
                }
                // --- END PATCHED ---
            }
        }
    }
}
'@
[System.IO.File]::WriteAllText($SrcWornItems, $JavaSourceWornItems, [System.Text.UTF8Encoding]::new($false))
Write-Host "    WornItems.java" -ForegroundColor Gray

# --- SyncClothingPacket.java ---
$SrcSyncClothing = Join-Path $SrcDirNet "SyncClothingPacket.java"
$JavaSourceSyncClothing = @'
package zombie.network.packets;

import java.util.ArrayList;
import zombie.Lua.LuaEventManager;
import zombie.characterTextures.BloodBodyPartType;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.WornItems.WornItem;
import zombie.characters.animals.IsoAnimal;
import zombie.core.ImmutableColor;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.types.Clothing;
import zombie.network.GameClient;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.ServerGUI;
import zombie.network.fields.character.PlayerID;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ResourceLocation;
import zombie.util.Type;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class SyncClothingPacket implements INetworkPacket {
    @JSONField
    private final PlayerID playerId = new PlayerID();
    @JSONField
    private final ArrayList<SyncClothingPacket.ItemDescription> items = new ArrayList<>();

    @Override
    public void setData(Object... values) {
        if (values.length == 1 && values[0] instanceof IsoPlayer) {
            this.set((IsoPlayer)values[0]);
        } else {
            DebugType.Multiplayer.warn(this.getClass().getSimpleName() + ".set get invalid arguments");
        }
    }

    public void set(IsoPlayer player) {
        if (player instanceof IsoAnimal) {
            DebugType.General.printStackTrace("SyncClothingPacket.set receives IsoAnimal");
        }

        this.playerId.set(player);
        this.items.clear();
        this.playerId.getPlayer().getWornItems().forEach(item -> {
            // --- PATCHED: skip items with null location to prevent NPE in write() ---
            if (item != null && item.getItem() != null && item.getLocation() != null) {
                this.items.add(new SyncClothingPacket.ItemDescription(item));
            }
            // --- END PATCHED ---
        });
    }

    void parseClothing(ByteBufferReader b, int itemId) {
        IsoPlayer player = this.playerId.getPlayer();
        if (player != null) {
            Clothing clothing = Type.tryCastTo(player.getInventory().getItemWithID(itemId), Clothing.class);
            if (clothing != null) {
                clothing.removeAllPatches();
            }

            byte patchesNum = b.getByte();

            for (byte j = 0; j < patchesNum; j++) {
                byte bloodBodyPartTypeIdx = b.getByte();
                byte tailorLvl = b.getByte();
                byte fabricType = b.getByte();
                boolean hasHole = b.getBoolean();
                if (clothing != null) {
                    ItemVisual bloodBodyPartType = clothing.getVisual();
                    if (bloodBodyPartType instanceof ItemVisual) {
                        bloodBodyPartType.removeHole(bloodBodyPartTypeIdx);
                        BloodBodyPartType bloodBodyPartTypex = BloodBodyPartType.FromIndex(bloodBodyPartTypeIdx);
                        switch (Clothing.ClothingPatchFabricType.fromIndex(fabricType)) {
                            case null:
                                break;
                            case Cotton:
                                bloodBodyPartType.setBasicPatch(bloodBodyPartTypex);
                                break;
                            case Denim:
                                bloodBodyPartType.setDenimPatch(bloodBodyPartTypex);
                                break;
                            case Leather:
                                bloodBodyPartType.setLeatherPatch(bloodBodyPartTypex);
                                break;
                            default:
                                throw new MatchException(null, null);
                        }
                    }

                    clothing.addPatchForSync(bloodBodyPartTypeIdx, tailorLvl, fabricType, hasHole);
                }
            }
        }
    }

    void writeClothing(ByteBufferWriter b, int itemId) {
        IsoPlayer player = this.playerId.getPlayer();
        if (player == null) {
            b.putByte(0);
        } else {
            Clothing clothing = Type.tryCastTo(player.getInventory().getItemWithID(itemId), Clothing.class);
            if (clothing == null) {
                b.putByte(0);
            } else {
                b.putByte(clothing.getPatchesNumber());

                for (int i = 0; i < BloodBodyPartType.MAX.index(); i++) {
                    Clothing.ClothingPatch patch = clothing.getPatchType(BloodBodyPartType.FromIndex(i));
                    if (patch != null) {
                        b.putByte(i);
                        b.putByte(patch.tailorLvl);
                        b.putByte(patch.fabricType);
                        b.putBoolean(patch.hasHole);
                    }
                }
            }
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.playerId.parse(b, connection);
        IsoPlayer player = this.playerId.getPlayer();
        if (player != null) {
            this.items.clear();
            byte size = b.getByte();

            for (int i = 0; i < size; i++) {
                SyncClothingPacket.ItemDescription item = new SyncClothingPacket.ItemDescription();
                item.parse(b, connection);
                this.items.add(item);
                this.parseClothing(b, item.itemId);
            }
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.playerId.write(b);
        b.putByte(this.items.size());

        for (SyncClothingPacket.ItemDescription item : this.items) {
            item.write(b);
            this.writeClothing(b, item.itemId);
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.playerId.getPlayer() != null;
    }

    // --- PATCHED: null-guard on location param and item.location before equals() ---
    private boolean isItemsContains(int itemId, ItemBodyLocation location) {
        if (location == null) {
            return false;
        }
        for (SyncClothingPacket.ItemDescription item : this.items) {
            if (item.itemId == itemId && item.location != null && item.location.equals(location)) {
                return true;
            }
        }

        return false;
    }
    // --- END PATCHED ---

    private void process() {
        if (this.playerId.getPlayer().remote) {
            this.playerId.getPlayer().getItemVisuals().clear();
        }

        ArrayList<InventoryItem> itemsForDelete = new ArrayList<>();
        this.playerId.getPlayer().getWornItems().forEach(itemx -> {
            if (!this.isItemsContains(itemx.getItem().getID(), itemx.getLocation())) {
                itemsForDelete.add(itemx.getItem());
            }
        });

        for (InventoryItem item : itemsForDelete) {
            this.playerId.getPlayer().getWornItems().remove(item);
        }

        for (SyncClothingPacket.ItemDescription item : this.items) {
            // --- PATCHED: skip items with null location (unresolved body location from registry) ---
            if (item.location == null) {
                continue;
            }
            // --- END PATCHED ---
            Clothing wornItem = Type.tryCastTo(this.playerId.getPlayer().getWornItems().getItemById(item.itemId), Clothing.class);
            if (wornItem == null || !item.location.equals(wornItem.getBodyLocation())) {
                InventoryItem itemForAdd = this.playerId.getPlayer().getInventory().getItemWithID(item.itemId);
                if (itemForAdd == null) {
                    itemForAdd = InventoryItemFactory.CreateItem(item.itemType);
                    if (itemForAdd != null) {
                        itemForAdd.setID(item.itemId);
                    }
                }

                if (itemForAdd != null) {
                    this.playerId.getPlayer().getWornItems().setItem(item.location, itemForAdd);
                    if (this.playerId.getPlayer().remote) {
                        itemForAdd.getVisual().setTint(item.tint);
                        itemForAdd.getVisual().setBaseTexture(item.baseTexture);
                        itemForAdd.getVisual().setTextureChoice(item.textureChoice);
                        this.playerId.getPlayer().getItemVisuals().add(itemForAdd.getVisual());
                    }
                }
            } else if (this.playerId.getPlayer().remote) {
                this.playerId.getPlayer().getItemVisuals().add(wornItem.getVisual());
            }
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        if (GameClient.client) {
            // --- PATCHED: only apply the destructive delete-then-add process() on remote players.
            // The local player's worn items are authoritative - an echoed packet from the
            // server carries stale state and would delete items added since the original send. ---
            if (this.playerId.getPlayer().remote) {
                this.process();
            }
            // --- END PATCHED ---
            this.playerId.getPlayer().onWornItemsChanged();
        }

        this.playerId.getPlayer().resetModelNextFrame();
        LuaEventManager.triggerEvent("OnClothingUpdated", this.playerId.getPlayer());
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        this.process();
        if (ServerGUI.isCreated()) {
            this.playerId.getPlayer().resetModelNextFrame();
        }

        // --- PATCHED: exclude sender from relay. Original code passed null which echoed the
        // packet back to the sending client, causing stale state to overwrite newer items.
        // Every other packet (EquipPacket, GameCharacterAttachedItemPacket) correctly
        // passes the connection to exclude the sender. ---
        this.sendToClients(PacketTypes.PacketType.SyncClothing, connection);
        // --- END PATCHED ---
    }

    static class ItemDescription implements INetworkPacket {
        @JSONField
        int itemId;
        @JSONField
        String itemType;
        @JSONField
        ItemBodyLocation location;
        @JSONField
        ImmutableColor tint;
        @JSONField
        int textureChoice;
        @JSONField
        int baseTexture;

        public ItemDescription() {
        }

        public ItemDescription(WornItem item) {
            this.itemId = item.getItem().getID();
            this.itemType = item.getItem().getFullType();
            this.location = item.getLocation();
            this.baseTexture = item.getItem().getVisual() == null ? -1 : item.getItem().getVisual().getBaseTexture();
            this.textureChoice = item.getItem().getVisual() == null ? -1 : item.getItem().getVisual().getTextureChoice();
            this.tint = item.getItem().getVisual().getTint();
        }

        @Override
        public void write(ByteBufferWriter b) {
            b.putInt(this.itemId);
            b.putUTF(this.itemType);
            b.putUTF(this.location.toString());
            b.putInt(this.textureChoice);
            b.putInt(this.baseTexture);
            b.putFloat(this.tint.r);
            b.putFloat(this.tint.g);
            b.putFloat(this.tint.b);
            b.putFloat(this.tint.a);
        }

        @Override
        public void parse(ByteBufferReader b, IConnection connection) {
            this.itemId = b.getInt();
            this.itemType = b.getUTF();
            // --- PATCHED: ItemBodyLocation.get() returns null if the location string is not
            // registered in the registry. Store null and let process() skip it. ---
            this.location = ItemBodyLocation.get(ResourceLocation.of(b.getUTF()));
            // --- END PATCHED ---
            this.textureChoice = b.getInt();
            this.baseTexture = b.getInt();
            this.tint = new ImmutableColor(b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat());
        }
    }
}
'@
[System.IO.File]::WriteAllText($SrcSyncClothing, $JavaSourceSyncClothing, [System.Text.UTF8Encoding]::new($false))
Write-Host "    SyncClothingPacket.java" -ForegroundColor Gray

# Step 4: Compile all three sources together
Write-Host ""
Write-Host "[*] Compiling patched classes..." -ForegroundColor Cyan

$javacArgs = @(
    "-cp", $GameJar,
    "-d", $OutputDir,
    "-encoding", "UTF-8",
    "-source", "25",
    "-target", "25",
    $SrcBodyLocationGroup,
    $SrcWornItems,
    $SrcSyncClothing
)

Write-Host "    javac $($javacArgs -join ' ')" -ForegroundColor Gray
& $javac @javacArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: Compilation failed (exit code $LASTEXITCODE)." -ForegroundColor Red
    Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}

$compiled1 = Join-Path $OutputDir "zombie\characters\WornItems\BodyLocationGroup.class"
$compiled2 = Join-Path $OutputDir "zombie\characters\WornItems\WornItems.class"
$compiled3 = Join-Path $OutputDir "zombie\network\packets\SyncClothingPacket.class"
foreach ($c in @($compiled1, $compiled2, $compiled3)) {
    if (-not (Test-Path $c)) {
        Write-Host "ERROR: Expected output not found: $c" -ForegroundColor Red
        Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
        exit 1
    }
}

Write-Host "    Compiled successfully." -ForegroundColor Green

# Step 5: Deploy
Write-Host ""
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy to:" -ForegroundColor Yellow
    Write-Host "    $DeployDirWorn" -ForegroundColor Yellow
    Write-Host "    $DeployDirNet" -ForegroundColor Yellow
    $compiledDirWorn = Join-Path $OutputDir "zombie\characters\WornItems"
    $compiledDirNet  = Join-Path $OutputDir "zombie\network\packets"
    Get-ChildItem -Path $compiledDirWorn -Filter "BodyLocationGroup*.class","WornItems*.class" -ErrorAction SilentlyContinue | ForEach-Object {
        Write-Host "    Would copy: $($_.Name)" -ForegroundColor Yellow
    }
    Get-ChildItem -Path $compiledDirNet -Filter "SyncClothingPacket*.class" -ErrorAction SilentlyContinue | ForEach-Object {
        Write-Host "    Would copy: $($_.Name)" -ForegroundColor Yellow
    }
} else {
    Write-Host "[*] Deploying..." -ForegroundColor Cyan

    New-Item -Path $DeployDirWorn -ItemType Directory -Force | Out-Null
    New-Item -Path $DeployDirNet  -ItemType Directory -Force | Out-Null

    $compiledDirWorn = Join-Path $OutputDir "zombie\characters\WornItems"
    $compiledDirNet  = Join-Path $OutputDir "zombie\network\packets"

    foreach ($pattern in @("BodyLocationGroup*.class", "WornItems*.class")) {
        Get-ChildItem -Path $compiledDirWorn -Filter $pattern -ErrorAction SilentlyContinue | ForEach-Object {
            Copy-Item $_.FullName (Join-Path $DeployDirWorn $_.Name) -Force
            Write-Host "    Deployed: $($_.Name)" -ForegroundColor Green
        }
    }

    Get-ChildItem -Path $compiledDirNet -Filter "SyncClothingPacket*.class" -ErrorAction SilentlyContinue | ForEach-Object {
        Copy-Item $_.FullName (Join-Path $DeployDirNet $_.Name) -Force
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
Write-Host "  PZ classpath is ['.', 'projectzomboid.jar'], so loose .class files in" -ForegroundColor Gray
Write-Host "  '$PZDir' take precedence over those in the JAR." -ForegroundColor Gray
Write-Host ""
Write-Host "  BodyLocationGroup: all 8 mutation/query methods now return safely when" -ForegroundColor Gray
Write-Host "  a body location id is not registered in this group (e.g. modded slots)." -ForegroundColor Gray
Write-Host ""
Write-Host "  WornItems: setItem/indexOf/setFromItemVisuals/save/load all null-guard" -ForegroundColor Gray
Write-Host "  the body location so modded gear can't crash the zombie death chain." -ForegroundColor Gray
Write-Host ""
Write-Host "  SyncClothingPacket: sender is excluded from relay (self-echo fixed);" -ForegroundColor Gray
Write-Host "  local player is not processed by stale echo packets (naked-player fixed)." -ForegroundColor Gray
Write-Host ""
Write-Host "  To revert entirely:" -ForegroundColor Yellow
Write-Host "    .\patchNullSafety.ps1 -Revert" -ForegroundColor Yellow
Write-Host ""

# Cleanup
Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
