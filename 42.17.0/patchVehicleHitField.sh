#!/bin/bash
# filepath: patchVehicleHitField.sh
# Patches VehicleHitField.java with server-side vehicle damage deduplication
# via classpath override.
#
# Vehicle Damage MP Fix:
#   Prevents per-frame VehicleHitField packets from applying vehicle damage
#   multiple times for a single collision event. Only vehicle HP damage is
#   deduplicated; character damage (zombie death/knockdown) passes through.
#
# Strategy: classpath override via "java/." before "java/projectzomboid.jar".
# The JVM loads .class files from the filesystem before looking inside the JAR.
# The original JAR is untouched.

set -e

# --- Argument parsing ---
# Accepts parameters passed by the main patch.sh entrypoint, or defaults
# can be overridden when running this script standalone.
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
WORK_DIR="/tmp/pzpatch_vehiclehitfield"
DEPLOY_DIR="$CLASSPATH_DIR/zombie/network/fields/hit"

# --- Find javac (prefer PATH set by main script, fall back to known location) ---
JAVAC=""
if command -v javac &>/dev/null; then
    JAVAC=$(command -v javac)
fi
if [[ -z "$JAVAC" ]]; then
    JAVAC="/usr/lib/jvm/java-25-openjdk-amd64/bin/javac"
fi

echo "=== PZ Classpath Override Patch ==="
echo "=== VehicleHitField: Vehicle Damage MP Fix (Dedup) ==="

# --- Handle --revert ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting patch..."
    reverted=false
    if [ -f "$DEPLOY_DIR/VehicleHitField.class" ]; then
        rm -f "$DEPLOY_DIR/VehicleHitField.class"
        echo "    Removed VehicleHitField.class"
        reverted=true
    fi
    # Remove inner class files
    for f in "$DEPLOY_DIR"/VehicleHitField\$*.class; do
        if [ -f "$f" ]; then
            rm -f "$f"
            echo "    Removed $(basename "$f")"
            reverted=true
        fi
    done
    if [[ "$reverted" == "true" ]]; then
        echo ""
        echo "=== Patch reverted ==="
        echo "Original VehicleHitField from JAR will be used on next server start."
    else
        echo "    No patch files found to remove."
    fi
    exit 0
fi

# Verify tools exist
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

# Clean work directory
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/src/zombie/network/fields/hit"
mkdir -p "$WORK_DIR/build"

echo "[1/5] Writing patched VehicleHitField.java..."
cat > "$WORK_DIR/src/zombie/network/fields/hit/VehicleHitField.java" << 'JAVAEOF'
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
JAVAEOF

echo "[2/5] Compiling patched VehicleHitField.java..."
"$JAVAC" -cp "$JAR_FILE" \
    -d "$WORK_DIR/build" \
    -encoding UTF-8 \
    -source 25 \
    -target 25 \
    "$WORK_DIR/src/zombie/network/fields/hit/VehicleHitField.java"

if [ $? -ne 0 ]; then
    echo "ERROR: Compilation failed."
    rm -rf "$WORK_DIR"
    exit 1
fi

echo "    Compiled successfully."

echo "[3/5] Deploying classes to classpath override directory..."

if [[ "$DRY_RUN" == "true" ]]; then
    echo "    DRY RUN: would deploy to $DEPLOY_DIR/"
    for f in "$WORK_DIR/build/zombie/network/fields/hit"/VehicleHitField*.class; do
        [[ -f "$f" ]] && echo "    Would deploy: $(basename "$f")"
    done
else
    mkdir -p "$DEPLOY_DIR"

    # Deploy main class and any inner classes
    cp "$WORK_DIR/build/zombie/network/fields/hit/VehicleHitField.class" "$DEPLOY_DIR/"
    echo "    Deployed VehicleHitField.class"

    for f in "$WORK_DIR/build/zombie/network/fields/hit"/VehicleHitField\$*.class; do
        if [[ -f "$f" ]]; then
            cp "$f" "$DEPLOY_DIR/"
            echo "    Deployed $(basename "$f")"
        fi
    done
fi

echo "[4/5] Verifying deployment..."
if [[ "$DRY_RUN" != "true" ]]; then
    echo "  Checking classpath override files:"
    ls -la "$DEPLOY_DIR"/VehicleHitField*.class
else
    echo "  DRY RUN: skipping verification."
fi

echo ""
echo "[5/5] Cleanup..."
rm -rf "$WORK_DIR"

echo ""
echo "=== Classpath Override Patch deployed successfully ==="
echo ""
echo "Patch: Vehicle Damage MP Fix - VehicleHitField Dedup"
echo ""
echo "How it works:"
echo "  The server config classpath is: [\"java/.\", \"java/projectzomboid.jar\"]"
echo "  Since 'java/.' is listed first, the JVM loads .class files from the"
echo "  filesystem before looking inside the JAR. The original JAR is untouched."
echo ""
echo "  Vehicle damage from VehicleHitField packets is deduplicated with a"
echo "  3500ms cooldown per (vehicle, target) pair. This prevents per-frame"
echo "  packet spam from multiplying vehicle damage by 3-5x."
echo "  Character damage (zombie death/knockdown) is NOT affected."
echo ""
echo "Deployed to:"
echo "  $DEPLOY_DIR/"
echo "    - VehicleHitField.class"
echo ""
echo "  To customize dedup cooldown, add to JVM args:"
echo "    -Dpz.vehicle.hit.dedup.cooldown=3500   (ms, default 3500)"
echo "    -Dpz.vehicle.hit.dedup.cooldown=0      (disable dedup entirely)"
echo ""
echo "  To revert: $0 --revert"
echo "  (or delete all VehicleHitField*.class from: $DEPLOY_DIR)"
echo ""
echo "Restart the server to apply changes."
