#!/bin/bash
# filepath: patchEatFoodPacket.sh
# Patches EatFoodPacket.java to fix nutrition calorie corruption in multiplayer.
#
# Nutrition Calorie Corruption Fix:
#   Root cause: The server runs Nutrition.update() every tick to decay calories
#   and compute weight gain. The client does NOT decay calories (gated by
#   !GameClient.client). On every eat, the client sends EatFoodPacket containing
#   its local (non-decaying, inflated) calories. The original parse() called
#   nutrition.load() on the server unconditionally, overwriting the server's
#   correctly-decayed state with the client's stale higher value on every meal.
#   This caused the server to perpetually see a calorie surplus -> perpetual
#   weight gain regardless of how much or little the player ate.
#
#   Fix:
#     1. parse(): when running server-side, skip the client's nutrition blob
#        (advance the buffer past 20 bytes) instead of loading it.
#     2. processServer(): apply the food nutrition delta directly to the server's
#        nutrition object, mirroring the nutrition portion of IsoGameCharacter.Eat().
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
WORK_DIR="/tmp/pzpatch_eatfoodpacket"
DEPLOY_DIR="$CLASSPATH_DIR/zombie/network/packets/actions"

# --- Find javac (prefer PATH set by main script, fall back to known location) ---
JAVAC=""
if command -v javac &>/dev/null; then
    JAVAC=$(command -v javac)
fi
if [[ -z "$JAVAC" ]]; then
    JAVAC="/usr/lib/jvm/java-25-openjdk-amd64/bin/javac"
fi

echo "=== PZ Classpath Override Patch ==="
echo "=== EatFoodPacket: Nutrition Calorie Corruption Fix ==="

# --- Handle --revert ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting patch..."
    reverted=false
    if [ -f "$DEPLOY_DIR/EatFoodPacket.class" ]; then
        rm -f "$DEPLOY_DIR/EatFoodPacket.class"
        echo "    Removed EatFoodPacket.class"
        reverted=true
    fi
    if [[ "$reverted" == "true" ]]; then
        echo ""
        echo "=== Patch reverted ==="
        echo "Original EatFoodPacket from JAR will be used on next server start."
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
mkdir -p "$WORK_DIR/src/zombie/network/packets/actions"
mkdir -p "$WORK_DIR/build"

echo "[1/5] Writing patched EatFoodPacket.java..."
cat > "$WORK_DIR/src/zombie/network/packets/actions/EatFoodPacket.java" << 'JAVAEOF'
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
            // Client receiving server's authoritative nutrition -- sync client state.
            this.player.getPlayer().getNutrition().load(b.bb);
        } else {
            // PATCHED: Server receiving client's packet.
            // Skip the nutrition blob -- server maintains its own authoritative state.
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
JAVAEOF

echo "[2/5] Compiling patched EatFoodPacket.java..."
"$JAVAC" -cp "$JAR_FILE" \
    -d "$WORK_DIR/build" \
    -encoding UTF-8 \
    -source 25 \
    -target 25 \
    "$WORK_DIR/src/zombie/network/packets/actions/EatFoodPacket.java"

if [ $? -ne 0 ]; then
    echo "ERROR: Compilation failed."
    rm -rf "$WORK_DIR"
    exit 1
fi

echo "    Compiled successfully."

echo "[3/5] Deploying class to classpath override directory..."

if [[ "$DRY_RUN" == "true" ]]; then
    echo "    DRY RUN: would deploy to $DEPLOY_DIR/"
    echo "    Would deploy: EatFoodPacket.class"
else
    mkdir -p "$DEPLOY_DIR"
    cp "$WORK_DIR/build/zombie/network/packets/actions/EatFoodPacket.class" "$DEPLOY_DIR/"
    echo "    Deployed EatFoodPacket.class"
fi

echo "[4/5] Verifying deployment..."
if [[ "$DRY_RUN" != "true" ]]; then
    echo "  Checking classpath override files:"
    ls -la "$DEPLOY_DIR/EatFoodPacket.class"
else
    echo "  DRY RUN: skipping verification."
fi

echo ""
echo "[5/5] Cleanup..."
rm -rf "$WORK_DIR"

echo ""
echo "=== Classpath Override Patch deployed successfully ==="
echo ""
echo "Patch: Nutrition Calorie Corruption Fix - EatFoodPacket"
echo ""
echo "How it works:"
echo "  The server config classpath is: [\"java/.\", \"java/projectzomboid.jar\"]"
echo "  Since 'java/.' is listed first, the JVM loads .class files from the"
echo "  filesystem before looking inside the JAR. The original JAR is untouched."
echo ""
echo "  EatFoodPacket.parse() on the server now skips the client's nutrition blob"
echo "  instead of loading it. The server's decayed calories are no longer overwritten."
echo "  EatFoodPacket.processServer() applies the food delta directly to the server's"
echo "  authoritative nutrition, replacing the previously-corrupt data source."
echo ""
echo "  Client weight display still needs a Lua periodic sync to stay current."
echo "  Use sendPlayerNutrition(player) or syncPlayerStats(player, -1) server-side."
echo ""
echo "Deployed to:"
echo "  $DEPLOY_DIR/"
echo "    - EatFoodPacket.class"
echo ""
echo "  To revert: $0 --revert"
echo "  (or delete EatFoodPacket.class from: $DEPLOY_DIR)"
echo ""
echo "Restart the server to apply changes."
