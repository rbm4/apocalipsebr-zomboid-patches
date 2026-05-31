<#
.SYNOPSIS
    Compiles patched Nutrition.java and deploys the .class to Project Zomboid.

.DESCRIPTION
    Nutrition Weight Gain Tuning - Nutrition

    Context: zombie.characters.BodyDamage.Nutrition.updateWeight() amplifies
    the base weight gain rate (1.3E-5F) by 3.0x when carbohydrates or lipids
    exceed 700, or by 2.0x when they exceed 400. On servers where the
    Nutrition sandbox option was previously off and re-enabled (or where
    EatFoodPacket nutrition corruption left players permanently stacked at
    maximum macros), these multipliers cause runaway weight gain.

    This patch makes the two amplifier constants configurable via JVM system
    properties, so you can lower (or raise) them per server without touching
    the data:

      -Dapocbr.nutrition.weightGainHighMult=1.5   (was 3.0)
      -Dapocbr.nutrition.weightGainMedMult=1.0    (was 2.0)

    Add those flags to the JVM section of ProjectZomboid64.json (or the
    server start script) to apply tuning at startup.

    Defaults match vanilla (3.0 / 2.0), so the patch is behaviourally inert
    until you set the properties.

    This script:
    1. Locates or downloads a JDK 25+ compiler (javac)
    2. Backs up the original Nutrition.class from projectzomboid.jar
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
$PatchName     = "Nutrition Weight Gain Tuning - Nutrition"
$GameJar       = Join-Path $PZDir "projectzomboid.jar"
$DeployDir     = Join-Path $PZDir "zombie\characters\BodyDamage"
$DeployClass   = Join-Path $DeployDir "Nutrition.class"
$BackupDir     = Join-Path $ToolsDir "backups"
$BackupClass   = Join-Path $BackupDir "Nutrition.class.original"
$LocalJdkDir   = Join-Path $ToolsDir "jdk"
$WorkDir       = Join-Path $env:TEMP "pzpatch_nutrition_weightgain"
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

    Write-Host "[*] Extracting original Nutrition.class from JAR..." -ForegroundColor Cyan
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
        & $javaExe xf $GameJar "zombie/characters/BodyDamage/Nutrition.class"
        $extracted = Join-Path $tempDir "zombie\characters\BodyDamage\Nutrition.class"
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
        Write-Host "Original Nutrition from JAR will be used on next server start." -ForegroundColor Gray
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
Write-Host "[*] Writing patched Nutrition.java..." -ForegroundColor Cyan
$TempSrcDir = Join-Path $WorkDir "src\zombie\characters\BodyDamage"
if (-not (Test-Path $TempSrcDir)) {
    New-Item -Path $TempSrcDir -ItemType Directory -Force | Out-Null
}
$TempSourceFile = Join-Path $TempSrcDir "Nutrition.java"

$JavaSource = @'
// Patched Nutrition.java - Weight gain multipliers configurable via JVM properties.
//
// Original updateWeight() amplifies the base weight gain (1.3E-5F) by:
//   3.0x when carbohydrates > 700 OR lipids > 700
//   2.0x when carbohydrates > 400 OR lipids > 400
//
// On servers where players are stacked at maximum macros (because of nutrition
// corruption from the unpatched EatFoodPacket, or sandbox toggles), these
// multipliers produce runaway weight gain. This patch makes them tunable at
// runtime without rebuilding:
//
//   -Dapocbr.nutrition.weightGainHighMult=1.5   (default 3.0)
//   -Dapocbr.nutrition.weightGainMedMult=1.0    (default 2.0)
//
// Place those flags in the JVM section of ProjectZomboid64.json (or the
// server start command). With no flags set, behaviour is identical to vanilla.
//
// Original: zombie.characters.BodyDamage.Nutrition (Build 42.18)
package zombie.characters.BodyDamage;

import java.nio.ByteBuffer;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.Lua.LuaEventManager;
import zombie.ai.states.ClimbOverFenceState;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.ai.states.SwipeStatePlayer;
import zombie.characters.IsoPlayer;
import zombie.characters.skills.PerkFactory;
import zombie.network.GameClient;
import zombie.scripting.objects.CharacterTrait;

@UsedFromLua
public final class Nutrition {
    // === ApocBR weight gain tuning ======================================
    private static final String SYSPROP_HIGH_MULT = "apocbr.nutrition.weightGainHighMult";
    private static final String SYSPROP_MED_MULT  = "apocbr.nutrition.weightGainMedMult";
    private static final float WEIGHT_GAIN_HIGH_MULT;
    private static final float WEIGHT_GAIN_MED_MULT;
    static {
        WEIGHT_GAIN_HIGH_MULT = parseFloatSysProp(SYSPROP_HIGH_MULT, 3.0F);
        WEIGHT_GAIN_MED_MULT  = parseFloatSysProp(SYSPROP_MED_MULT,  2.0F);
        System.out.println("[ApocBR] Nutrition weight gain multipliers: high="
            + WEIGHT_GAIN_HIGH_MULT + " med=" + WEIGHT_GAIN_MED_MULT);
    }
    private static float parseFloatSysProp(String key, float fallback) {
        try {
            String v = System.getProperty(key);
            if (v != null && !v.isEmpty()) {
                return Float.parseFloat(v);
            }
        } catch (Throwable t) {
            System.err.println("[ApocBR] Invalid float for " + key + ": " + t.getMessage()
                + " (using " + fallback + ")");
        }
        return fallback;
    }
    // =====================================================================

    private final IsoPlayer parent;
    private float carbohydrates;
    private float lipids;
    private float proteins;
    private float calories;
    private final float carbohydratesDecreraseFemale = 0.0035F;
    private final float carbohydratesDecreraseMale = 0.0035F;
    private final float lipidsDecreraseFemale = 0.00113F;
    private final float lipidsDecreraseMale = 0.00113F;
    private final float proteinsDecreraseFemale = 8.6E-4F;
    private final float proteinsDecreraseMale = 8.6E-4F;
    private final float caloriesDecreraseFemaleNormal = 0.016F;
    private final float caloriesDecreaseMaleNormal = 0.016F;
    private final float caloriesDecreraseFemaleExercise = 0.13F;
    private final float caloriesDecreaseMaleExercise = 0.13F;
    private final float caloriesDecreraseFemaleSleeping = 0.003F;
    private final float caloriesDecreaseMaleSleeping = 0.003F;
    private final int caloriesToGainWeightMale = 1000;
    private final int caloriesToGainWeightMaxMale = 4000;
    private final int caloriesToGainWeightFemale = 1000;
    private final int caloriesToGainWeightMaxFemale = 4000;
    private final int caloriesDecreaseMax = 2500;
    private final float weightGain = 1.3E-5F;
    private final float weightLoss = 8.5E-6F;
    private double weight = 60.0;
    private int updatedWeight;
    private final boolean isFemale = false;
    private float caloriesMax;
    private float caloriesMin;
    private boolean incWeight;
    private boolean incWeightLot;
    private boolean decWeight;

    public Nutrition(IsoPlayer parent) {
        this.parent = parent;
        this.setWeight(80.0);
        this.setCalories(800.0F);
    }

    public void update() {
        if (SandboxOptions.instance.nutrition.getValue()) {
            if (this.parent != null && !this.parent.isDead()) {
                if (!this.parent.isGodMod()) {
                    if (!GameClient.client) {
                        this.setCarbohydrates(this.getCarbohydrates() - 0.0035F * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
                        this.setLipids(this.getLipids() - 0.00113F * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
                        this.setProteins(this.getProteins() - 8.6E-4F * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
                        this.updateCalories();
                    }

                    this.updateWeight();
                }
            }
        }
    }

    private void updateCalories() {
        float modifier = 1.0F;
        if (!this.parent.getCharacterActions().isEmpty()) {
            modifier = this.parent.getCharacterActions().get(0).caloriesModifier;
        }

        if (this.parent.isCurrentState(SwipeStatePlayer.instance())
            || this.parent.isCurrentState(ClimbOverFenceState.instance())
            || this.parent.isCurrentState(ClimbThroughWindowState.instance())) {
            modifier = 8.0F;
        }

        float coldMulti = 1.0F;
        if (this.parent.getBodyDamage() != null && this.parent.getBodyDamage().getThermoregulator() != null) {
            coldMulti = (float)this.parent.getBodyDamage().getThermoregulator().getEnergyMultiplier();
        }

        float caloriesDelta = (float)(this.getWeight() / 80.0);
        if (this.parent.IsRunning() && this.parent.isPlayerMoving()) {
            modifier = 1.0F;
            this.setCalories(this.getCalories() - 0.13F * modifier * caloriesDelta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
        } else if (this.parent.isSprinting() && this.parent.isPlayerMoving()) {
            modifier = 1.3F;
            this.setCalories(this.getCalories() - 0.13F * modifier * caloriesDelta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
        } else if (this.parent.isPlayerMoving()) {
            modifier = 0.6F;
            this.setCalories(this.getCalories() - 0.13F * modifier * caloriesDelta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
        } else if (this.parent.isAsleep()) {
            this.setCalories(this.getCalories() - 0.003F * modifier * coldMulti * caloriesDelta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
        } else {
            this.setCalories(this.getCalories() - 0.016F * modifier * coldMulti * caloriesDelta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
        }

        if (this.getCalories() > this.caloriesMax) {
            this.caloriesMax = this.getCalories();
        }

        if (this.getCalories() < this.caloriesMin) {
            this.caloriesMin = this.getCalories();
        }
    }

    private void updateWeight() {
        this.setIncWeight(false);
        this.setIncWeightLot(false);
        this.setDecWeight(false);
        float caloriesToGainWeight = 1000.0F;
        float caloriesToGainWeightMax = 4000.0F;
        if (this.getWeight() < 90.0 && this.parent.hasTrait(CharacterTrait.WEIGHT_GAIN)) {
            caloriesToGainWeight = 700.0F;
        }

        if (this.getWeight() > 70.0 && this.parent.hasTrait(CharacterTrait.WEIGHT_LOSS)) {
            caloriesToGainWeight = 1800.0F;
        }

        float caloriesDiff = (float)((this.getWeight() - 80.0) * 40.0);
        caloriesToGainWeight += caloriesDiff;
        float caloriesToLoseWeight = (float)((this.getWeight() - 70.0) * 30.0);
        if (caloriesToLoseWeight > 0.0F) {
            caloriesToLoseWeight = 0.0F;
        }

        double weight;
        if (this.getCalories() > caloriesToGainWeight) {
            this.setIncWeight(true);
            float delta = this.getCalories() / caloriesToGainWeightMax;
            if (delta > 1.0F) {
                delta = 1.0F;
            }

            float realWeightGain = 1.3E-5F;
            if (this.getCarbohydrates() > 700.0F || this.getLipids() > 700.0F) {
                // PATCHED: was *= 3.0F; tunable via -Dapocbr.nutrition.weightGainHighMult
                realWeightGain *= WEIGHT_GAIN_HIGH_MULT;
                this.setIncWeightLot(true);
            } else if (this.getCarbohydrates() > 400.0F || this.getLipids() > 400.0F) {
                // PATCHED: was *= 2.0F; tunable via -Dapocbr.nutrition.weightGainMedMult
                realWeightGain *= WEIGHT_GAIN_MED_MULT;
                this.setIncWeightLot(true);
            }

            weight = this.getWeight() + realWeightGain * delta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate();
        } else if (this.getCalories() < caloriesToLoseWeight) {
            this.setDecWeight(true);
            float deltax = Math.abs(this.getCalories()) / 2500.0F;
            if (deltax > 1.0F) {
                deltax = 1.0F;
            }

            weight = this.getWeight() - 8.5E-6F * deltax * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate();
        } else {
            weight = this.getWeight();
        }

        if (!GameClient.client) {
            this.setWeight(weight);
            this.updatedWeight++;
            if (this.updatedWeight >= 2000) {
                this.applyTraitFromWeight();
                this.updatedWeight = 0;
            }
        }
    }

    public void save(ByteBuffer output) {
        output.putFloat(this.getCalories());
        output.putFloat(this.getProteins());
        output.putFloat(this.getLipids());
        output.putFloat(this.getCarbohydrates());
        output.putFloat((float)this.getWeight());
    }

    public void load(ByteBuffer input) {
        this.setCalories(input.getFloat());
        this.setProteins(input.getFloat());
        this.setLipids(input.getFloat());
        this.setCarbohydrates(input.getFloat());
        this.setWeight(input.getFloat());
    }

    public void applyWeightFromTraits() {
        if (this.parent.hasTrait(CharacterTrait.EMACIATED)) {
            this.setWeight(50.0);
        }

        if (this.parent.hasTrait(CharacterTrait.VERY_UNDERWEIGHT)) {
            this.setWeight(60.0);
        }

        if (this.parent.hasTrait(CharacterTrait.UNDERWEIGHT)) {
            this.setWeight(70.0);
        }

        if (this.parent.hasTrait(CharacterTrait.OVERWEIGHT)) {
            this.setWeight(95.0);
        }

        if (this.parent.hasTrait(CharacterTrait.OBESE)) {
            this.setWeight(105.0);
        }
    }

    public void applyTraitFromWeight() {
        this.parent.getCharacterTraits().remove(CharacterTrait.UNDERWEIGHT);
        this.parent.getCharacterTraits().remove(CharacterTrait.VERY_UNDERWEIGHT);
        this.parent.getCharacterTraits().remove(CharacterTrait.EMACIATED);
        this.parent.getCharacterTraits().remove(CharacterTrait.OVERWEIGHT);
        this.parent.getCharacterTraits().remove(CharacterTrait.OBESE);
        if (this.getWeight() >= 100.0) {
            this.parent.getCharacterTraits().add(CharacterTrait.OBESE);
        }

        if (this.getWeight() >= 85.0 && this.getWeight() < 100.0) {
            this.parent.getCharacterTraits().add(CharacterTrait.OVERWEIGHT);
        }

        if (this.getWeight() > 65.0 && this.getWeight() <= 75.0) {
            this.parent.getCharacterTraits().add(CharacterTrait.UNDERWEIGHT);
        }

        if (this.getWeight() > 50.0 && this.getWeight() <= 65.0) {
            this.parent.getCharacterTraits().add(CharacterTrait.VERY_UNDERWEIGHT);
        }

        if (this.getWeight() <= 50.0) {
            this.parent.getCharacterTraits().add(CharacterTrait.EMACIATED);
        }
    }

    public boolean characterHaveWeightTrouble() {
        return this.parent.hasTrait(CharacterTrait.EMACIATED)
            || this.parent.hasTrait(CharacterTrait.OBESE)
            || this.parent.hasTrait(CharacterTrait.VERY_UNDERWEIGHT)
            || this.parent.hasTrait(CharacterTrait.VERY_UNDERWEIGHT)
            || this.parent.hasTrait(CharacterTrait.OVERWEIGHT);
    }

    public boolean canAddFitnessXp() {
        if (this.parent.getPerkLevel(PerkFactory.Perks.Fitness) >= 9 && this.characterHaveWeightTrouble()) {
            return false;
        } else {
            return this.parent.getPerkLevel(PerkFactory.Perks.Fitness) < 6
                ? true
                : !this.parent.hasTrait(CharacterTrait.EMACIATED)
                    && !this.parent.hasTrait(CharacterTrait.OBESE)
                    && !this.parent.hasTrait(CharacterTrait.VERY_UNDERWEIGHT);
        }
    }

    public float getCarbohydrates() {
        return this.carbohydrates;
    }

    public void setCarbohydrates(float carbohydrates) {
        if (carbohydrates < -500.0F) {
            carbohydrates = -500.0F;
        }

        if (carbohydrates > 1000.0F) {
            carbohydrates = 1000.0F;
        }

        this.carbohydrates = carbohydrates;
    }

    public float getProteins() {
        return this.proteins;
    }

    public void setProteins(float proteins) {
        if (proteins < -500.0F) {
            proteins = -500.0F;
        }

        if (proteins > 1000.0F) {
            proteins = 1000.0F;
        }

        this.proteins = proteins;
    }

    public float getCalories() {
        return this.calories;
    }

    public void setCalories(float calories) {
        if (calories < -2200.0F) {
            calories = -2200.0F;
        }

        if (calories > 3700.0F) {
            calories = 3700.0F;
        }

        this.calories = calories;
    }

    public float getLipids() {
        return this.lipids;
    }

    public void setLipids(float lipids) {
        if (lipids < -500.0F) {
            lipids = -500.0F;
        }

        if (lipids > 1000.0F) {
            lipids = 1000.0F;
        }

        this.lipids = lipids;
    }

    public double getWeight() {
        return this.weight;
    }

    public void setWeight(double weight) {
        if (weight < 35.0) {
            weight = 35.0;
            float lowWeightDamage = this.parent.getBodyDamage().getHealthReductionFromSevereBadMoodles() * GameTime.instance.getMultiplier();
            this.parent.getBodyDamage().ReduceGeneralHealth(lowWeightDamage);
            LuaEventManager.triggerEvent("OnPlayerGetDamage", this.parent, "LOWWEIGHT", lowWeightDamage);
        }

        this.weight = weight;
    }

    public boolean isIncWeight() {
        return this.incWeight;
    }

    public void setIncWeight(boolean incWeight) {
        this.incWeight = incWeight;
    }

    public boolean isIncWeightLot() {
        return this.incWeightLot;
    }

    public void setIncWeightLot(boolean incWeightLot) {
        this.incWeightLot = incWeightLot;
    }

    public boolean isDecWeight() {
        return this.decWeight;
    }

    public void setDecWeight(boolean decWeight) {
        this.decWeight = decWeight;
    }
}
'@

[System.IO.File]::WriteAllText($TempSourceFile, $JavaSource, [System.Text.UTF8Encoding]::new($false))
Write-Host "    Written to: $TempSourceFile" -ForegroundColor Green

# Step 4: Compile
Write-Host ""
Write-Host "[*] Compiling patched Nutrition.java..." -ForegroundColor Cyan
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

$compiledClass = Join-Path $OutputDir "zombie\characters\BodyDamage\Nutrition.class"
if (-not (Test-Path $compiledClass)) {
    Write-Host "ERROR: Expected output not found: $compiledClass" -ForegroundColor Red
    exit 1
}

Write-Host "    Compiled successfully." -ForegroundColor Green

# Step 5: Deploy
Write-Host ""
if ($DryRun) {
    Write-Host "[*] DRY RUN: Would deploy to $DeployDir\" -ForegroundColor Yellow
    $compiledDir = Join-Path $OutputDir "zombie\characters\BodyDamage"
    Get-ChildItem -Path $compiledDir -Filter "Nutrition*.class" | ForEach-Object {
        Write-Host "    Would copy: $($_.Name)" -ForegroundColor Yellow
    }
} else {
    Write-Host "[*] Deploying..." -ForegroundColor Cyan

    if (-not (Test-Path $DeployDir)) {
        New-Item -Path $DeployDir -ItemType Directory -Force | Out-Null
    }

    if (Test-Path $DeployClass) {
        $ts = Get-Date -Format "yyyyMMdd_HHmmss"
        $prev = Join-Path $BackupDir "Nutrition.class.prev_$ts"
        Copy-Item $DeployClass $prev
        Write-Host "    Previous override backed up to: $prev" -ForegroundColor Gray
    }

    $compiledDir = Join-Path $OutputDir "zombie\characters\BodyDamage"
    Get-ChildItem -Path $compiledDir -Filter "Nutrition*.class" | ForEach-Object {
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
Write-Host "Tuning (add to ProjectZomboid64.json `"vmArgs`" / server start cmd):" -ForegroundColor Yellow
Write-Host "  -Dapocbr.nutrition.weightGainHighMult=1.5    (default 3.0)" -ForegroundColor Yellow
Write-Host "  -Dapocbr.nutrition.weightGainMedMult=1.0     (default 2.0)" -ForegroundColor Yellow
Write-Host ""
Write-Host "  Without flags, behaviour matches vanilla. The class prints the" -ForegroundColor Gray
Write-Host "  effective values once at startup:" -ForegroundColor Gray
Write-Host "    [ApocBR] Nutrition weight gain multipliers: high=1.5 med=1.0" -ForegroundColor Gray
Write-Host ""
Write-Host "  To revert entirely:" -ForegroundColor Yellow
Write-Host "    .\patchNutritionWeightGain.ps1 -Revert" -ForegroundColor Yellow
Write-Host "    (or delete Nutrition.class from: $DeployDir)" -ForegroundColor Yellow
Write-Host ""

Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
