#!/usr/bin/env bash
# patchVehicleOptimizations.sh - Compile & deploy patched BaseVehicle.class (Linux/macOS)
#
# Patch A: Round-robin updateSignalDevice() calls across DEVICE_UPDATE_SPREAD frames
# Patch B: Dirty-flag animation freeze for static parked vehicles
#
# Usage:
#   ./patchVehicleOptimizations.sh [PZ_DIR]
#   ./patchVehicleOptimizations.sh --revert [PZ_DIR]
#   ./patchVehicleOptimizations.sh --dry-run [PZ_DIR]
#
# Default PZ_DIR: /home/steam/pz  (or set env var PZ_DIR)
#
# Tuning JVM args (add to server start script):
#   -Dapocbr.vehicle.deviceUpdateSpread=4

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PZ_DIR="${1:-${PZ_DIR:-/home/steam/pz}}"
REVERT=false
DRY_RUN=false

for arg in "$@"; do
    case "$arg" in
        --revert)  REVERT=true ;;
        --dry-run) DRY_RUN=true ;;
        *)         PZ_DIR="$arg" ;;
    esac
done

GAME_JAR="$PZ_DIR/projectzomboid.jar"
DEPLOY_DIR="$PZ_DIR/zombie/vehicles"
SOURCE_FILE="$SCRIPT_DIR/src/zombie/vehicles/BaseVehicle.java"
BACKUP_DIR="$SCRIPT_DIR/backups/BaseVehicle"
WORK_DIR="/tmp/pzpatch_basevehicle_opt"
OUTPUT_DIR="$WORK_DIR/classes"
REQUIRED_MAJOR=25

INNER_CLASSES=(
    "BaseVehicle\$1"
    "BaseVehicle\$Authorization"
    "BaseVehicle\$engineStateTypes"
    "BaseVehicle\$HitVars"
    "BaseVehicle\$L_testCollisionWithVehicle"
    "BaseVehicle\$Matrix4fObjectPool"
    "BaseVehicle\$MinMaxPosition"
    "BaseVehicle\$ModelInfo"
    "BaseVehicle\$Passenger"
    "BaseVehicle\$QuaternionfObjectPool"
    "BaseVehicle\$ServerVehicleState"
    "BaseVehicle\$TransformPool"
    "BaseVehicle\$UpdateFlags"
    "BaseVehicle\$Vector2fObjectPool"
    "BaseVehicle\$Vector3fObjectPool"
    "BaseVehicle\$Vector3ObjectPool"
    "BaseVehicle\$Vector4fObjectPool"
    "BaseVehicle\$VehicleImpulse"
    "BaseVehicle\$WeightedVehiclePart"
    "BaseVehicle\$WheelInfo"
)

find_javac() {
    local candidate
    for candidate in \
        "$SCRIPT_DIR/jdk/bin/javac" \
        "$(command -v javac 2>/dev/null || true)" \
        /usr/lib/jvm/java-"$REQUIRED_MAJOR"-openjdk-amd64/bin/javac \
        /usr/lib/jvm/temurin-"$REQUIRED_MAJOR"/bin/javac; do
        if [[ -x "$candidate" ]]; then
            local ver
            ver=$("$candidate" -version 2>&1 | grep -oP '(?<=javac )\d+' || true)
            if [[ -n "$ver" ]] && (( ver >= REQUIRED_MAJOR )); then
                echo "$candidate"
                return 0
            fi
        fi
    done
    return 1
}

echo ""
echo "=== Vehicle Performance Optimizations - BaseVehicle ==="
echo ""

if $REVERT; then
    reverted=false
    if [[ -f "$DEPLOY_DIR/BaseVehicle.class" ]]; then
        $DRY_RUN || rm -f "$DEPLOY_DIR/BaseVehicle.class"
        echo "  Removed: BaseVehicle.class"
        reverted=true
    fi
    for inner in "${INNER_CLASSES[@]}"; do
        path="$DEPLOY_DIR/${inner}.class"
        if [[ -f "$path" ]]; then
            $DRY_RUN || rm -f "$path"
            echo "  Removed: ${inner}.class"
            reverted=true
        fi
    done
    if $reverted; then echo ""; echo "=== Patch reverted ==="; else echo "  Nothing to revert."; fi
    exit 0
fi

[[ -f "$GAME_JAR" ]]   || { echo "ERROR: Game JAR not found: $GAME_JAR"; exit 1; }
[[ -f "$SOURCE_FILE" ]] || { echo "ERROR: Patched source not found: $SOURCE_FILE"; exit 1; }

# Backup
if [[ ! -f "$BACKUP_DIR/BaseVehicle.class.original" ]]; then
    echo "[*] Backing up original classes..."
    mkdir -p "$BACKUP_DIR"
    tmpdir=$(mktemp -d)
    pushd "$tmpdir" > /dev/null
    jar xf "$GAME_JAR" zombie/vehicles/BaseVehicle.class 2>/dev/null || true
    for inner in "${INNER_CLASSES[@]}"; do
        jar xf "$GAME_JAR" "zombie/vehicles/${inner}.class" 2>/dev/null || true
    done
    if [[ -d "$tmpdir/zombie/vehicles" ]]; then
        cp "$tmpdir"/zombie/vehicles/BaseVehicle*.class "$BACKUP_DIR/" 2>/dev/null || true
        for f in "$BACKUP_DIR"/BaseVehicle*.class; do
            [[ "$f" == *.original ]] || mv "$f" "${f}.original"
        done
        echo "  Backed up $(ls "$BACKUP_DIR" | wc -l) files"
    else
        echo "  WARNING: Could not extract original classes."
    fi
    popd > /dev/null
    rm -rf "$tmpdir"
fi

# Find javac
JAVAC=$(find_javac) || { echo "ERROR: No suitable javac >= $REQUIRED_MAJOR found."; echo "Install OpenJDK $REQUIRED_MAJOR or Temurin $REQUIRED_MAJOR."; exit 1; }
echo "[*] Using javac: $JAVAC ($("$JAVAC" -version 2>&1))"

# Compile
echo "[*] Compiling patched BaseVehicle.java..."
rm -rf "$WORK_DIR"
mkdir -p "$OUTPUT_DIR"
TMPSRC="$WORK_DIR/src/zombie/vehicles"
mkdir -p "$TMPSRC"
cp "$SOURCE_FILE" "$TMPSRC/BaseVehicle.java"

if $DRY_RUN; then
    echo "  [DryRun] Would compile: $SOURCE_FILE"
else
    "$JAVAC" --release 25 -cp "$GAME_JAR" -d "$OUTPUT_DIR" "$TMPSRC/BaseVehicle.java"
    echo "  Compiled successfully."
fi

# Deploy
echo "[*] Deploying to: $DEPLOY_DIR"
mkdir -p "$DEPLOY_DIR"
for cf in "$OUTPUT_DIR"/zombie/vehicles/BaseVehicle*.class; do
    name=$(basename "$cf")
    if $DRY_RUN; then
        echo "  [DryRun] Would deploy: $name"
    else
        cp "$cf" "$DEPLOY_DIR/$name"
        echo "  Deployed: $name"
    fi
done

echo ""
if $DRY_RUN; then
    echo "=== Dry run complete ==="
else
    echo "=== Patch applied successfully ==="
    echo ""
    echo "Tuning (add to server start script JVM args):"
    echo "  -Dapocbr.vehicle.deviceUpdateSpread=4   (default 4; 1=disable Patch A)"
    echo ""
    echo "Restart the server for changes to take effect."
fi
echo ""
