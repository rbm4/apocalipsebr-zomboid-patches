#!/bin/bash
# filepath: patchNutritionWeightGain.sh
# Patches Nutrition.java to make weight-gain multipliers configurable via JVM properties.
#
# Nutrition Weight Gain Tuning:
#   Original updateWeight() amplifies the base weight gain (1.3E-5F) by:
#     3.0x when carbohydrates > 700 OR lipids > 700
#     2.0x when carbohydrates > 400 OR lipids > 400
#
#   On servers where players are stacked at maximum macros (because of nutrition
#   corruption from the unpatched EatFoodPacket, or sandbox toggles), these
#   multipliers produce runaway weight gain. This patch makes them tunable at
#   runtime without rebuilding:
#
#     -Dapocbr.nutrition.weightGainHighMult=1.5   (default 3.0)
#     -Dapocbr.nutrition.weightGainMedMult=1.0    (default 2.0)
#
#   Place those flags in the JVM section of ProjectZomboid64.json (or the
#   server start command). With no flags set, behaviour is identical to vanilla.
#
# Strategy: classpath override via "java/." before "java/projectzomboid.jar".
# The JVM loads .class files from the filesystem before looking inside the JAR.
# The original JAR is untouched.

set -e

# --- Argument parsing ---
PZ_DIR="/opt/pzserver"
DRY_RUN=false
REVERT=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pz-dir)    PZ_DIR="$2"; shift 2 ;;
        --dry-run)   DRY_RUN=true; shift ;;
        --revert|-r) REVERT=true; shift ;;
        *)
            echo "Unknown option: $1" >&2
            echo "Usage: $0 [--pz-dir PATH] [--dry-run] [--revert]" >&2
            exit 1
            ;;
    esac
done

JAR_FILE="$PZ_DIR/java/projectzomboid.jar"
CLASSPATH_DIR="$PZ_DIR/java"
WORK_DIR="/tmp/pzpatch_nutrition_weightgain"
DEPLOY_DIR="$CLASSPATH_DIR/zombie/characters/BodyDamage"

# --- Find javac (prefer PATH set by main script, fall back to known location) ---
JAVAC=""
if command -v javac &>/dev/null; then
    JAVAC=$(command -v javac)
fi
if [[ -z "$JAVAC" ]]; then
    JAVAC="/usr/lib/jvm/java-25-openjdk-amd64/bin/javac"
fi

echo "=== PZ Classpath Override Patch ==="
echo "=== Nutrition: Weight Gain Tuning ==="

# --- Handle --revert ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting patch..."
    reverted=false
    if [ -f "$DEPLOY_DIR/Nutrition.class" ]; then
        rm -f "$DEPLOY_DIR/Nutrition.class"
        echo "    Removed Nutrition.class"
        reverted=true
    fi
    if [[ "$reverted" == "true" ]]; then
        echo ""
        echo "=== Patch reverted ==="
        echo "Original Nutrition from JAR will be used on next server start."
    else
        echo "    No patch files found to remove."
    fi
    exit 0
fi

# --- Verify tools ---
if [[ ! -x "$JAVAC" ]]; then
    echo "ERROR: javac not found or not executable: $JAVAC"
    echo "       Install JDK 25: apt install openjdk-25-jdk-headless"
    echo "       Or run the main patch.sh which handles JDK setup automatically."
    exit 1
fi

if [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: JAR not found at $JAR_FILE"
    echo "       Set --pz-dir to your Project Zomboid server installation."
    exit 1
fi

# --- Clean work directory ---
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/src/zombie/characters/BodyDamage"
mkdir -p "$WORK_DIR/build"

echo "[1/5] Writing patched Nutrition.java..."
cat > "$WORK_DIR/src/zombie/characters/BodyDamage/Nutrition.java" << 'JAVAEOF'
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
JAVAEOF

echo "[2/5] Compiling patched Nutrition.java..."
"$JAVAC" -cp "$JAR_FILE" \
    -d "$WORK_DIR/build" \
    -encoding UTF-8 \
    -source 25 \
    -target 25 \
    "$WORK_DIR/src/zombie/characters/BodyDamage/Nutrition.java"

if [ $? -ne 0 ]; then
    echo "ERROR: Compilation failed."
    rm -rf "$WORK_DIR"
    exit 1
fi

echo "    Compiled successfully."

echo "[3/5] Deploying class to classpath override directory..."

if [[ "$DRY_RUN" == "true" ]]; then
    echo "    DRY RUN: would deploy to $DEPLOY_DIR/"
    echo "    Would deploy: Nutrition.class"
else
    mkdir -p "$DEPLOY_DIR"
    cp "$WORK_DIR/build/zombie/characters/BodyDamage/Nutrition.class" "$DEPLOY_DIR/"
    echo "    Deployed Nutrition.class"
fi

echo "[4/5] Verifying deployment..."
if [[ "$DRY_RUN" != "true" ]]; then
    echo "  Checking classpath override files:"
    ls -la "$DEPLOY_DIR/Nutrition.class"
else
    echo "  DRY RUN: skipping verification."
fi

echo ""
echo "[5/5] Cleanup..."
rm -rf "$WORK_DIR"

echo ""
echo "=== Classpath Override Patch deployed successfully ==="
echo ""
echo "Patch: Nutrition Weight Gain Tuning"
echo ""
echo "How it works:"
echo "  The server config classpath is: [\"java/.\", \"java/projectzomboid.jar\"]"
echo "  Since 'java/.' is listed first, the JVM loads .class files from the"
echo "  filesystem before looking inside the JAR. The original JAR is untouched."
echo ""
echo "Tuning (add to JVM args / server start command):"
echo "  -Dapocbr.nutrition.weightGainHighMult=1.5    (default 3.0)"
echo "  -Dapocbr.nutrition.weightGainMedMult=1.0     (default 2.0)"
echo ""
echo "  Without flags, behaviour matches vanilla. The class prints the"
echo "  effective values once at startup:"
echo "    [ApocBR] Nutrition weight gain multipliers: high=1.5 med=1.0"
echo ""
echo "  To revert entirely:"
echo "    ./patchNutritionWeightGain.sh --revert"
echo "    (or delete Nutrition.class from: $DEPLOY_DIR)"
echo ""
