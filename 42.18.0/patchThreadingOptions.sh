#!/usr/bin/env bash
# patchThreadingOptions.sh - Compile & deploy patched DebugOptions.class (Linux/macOS)
#
# Enables Threading.Animation, Threading.World, Threading.Ambient in DebugOptions.
# See patchThreadingOptions.ps1 for full explanation.
#
# Usage:
#   ./patchThreadingOptions.sh [PZ_DIR]
#   ./patchThreadingOptions.sh --revert [PZ_DIR]
#   ./patchThreadingOptions.sh --dry-run [PZ_DIR]
#
# Default PZ_DIR: /home/steam/pz  (or set env var PZ_DIR)

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
DEPLOY_DIR="$PZ_DIR/zombie/debug"
SOURCE_FILE="$SCRIPT_DIR/src/zombie/debug/DebugOptions.java"
BACKUP_DIR="$SCRIPT_DIR/backups/DebugOptions"
WORK_DIR="/tmp/pzpatch_debugoptions_threading"
OUTPUT_DIR="$WORK_DIR/classes"
REQUIRED_MAJOR=25

INNER_CLASSES=("DebugOptions\$Checks")

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
echo "=== Threading Optimization - DebugOptions ==="
echo ""

if $REVERT; then
    reverted=false
    if [[ -f "$DEPLOY_DIR/DebugOptions.class" ]]; then
        $DRY_RUN || rm -f "$DEPLOY_DIR/DebugOptions.class"
        echo "  Removed: DebugOptions.class"
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

[[ -f "$GAME_JAR" ]]    || { echo "ERROR: Game JAR not found: $GAME_JAR"; exit 1; }
[[ -f "$SOURCE_FILE" ]] || { echo "ERROR: Patched source not found: $SOURCE_FILE"; exit 1; }

# Backup
if [[ ! -d "$BACKUP_DIR" ]] || [[ -z "$(ls -A "$BACKUP_DIR" 2>/dev/null)" ]]; then
    echo "[*] Backing up original DebugOptions classes..."
    mkdir -p "$BACKUP_DIR"
    tmpdir=$(mktemp -d)
    pushd "$tmpdir" > /dev/null
    jar xf "$GAME_JAR" zombie/debug/DebugOptions.class 2>/dev/null || true
    for inner in "${INNER_CLASSES[@]}"; do
        jar xf "$GAME_JAR" "zombie/debug/${inner}.class" 2>/dev/null || true
    done
    if [[ -d "$tmpdir/zombie/debug" ]]; then
        cp "$tmpdir"/zombie/debug/DebugOptions*.class "$BACKUP_DIR/" 2>/dev/null || true
        for f in "$BACKUP_DIR"/DebugOptions*.class; do
            [[ "$f" == *.original ]] || mv "$f" "${f}.original"
        done
        echo "  Backed up $(ls "$BACKUP_DIR" | wc -l) file(s)"
    else
        echo "  WARNING: Could not extract original classes."
    fi
    popd > /dev/null
    rm -rf "$tmpdir"
else
    echo "[*] Backup already exists: $BACKUP_DIR"
fi

# Find javac
echo "[*] Searching for javac >= $REQUIRED_MAJOR..."
JAVAC=""
if JAVAC=$(find_javac); then
    echo "    Found: $JAVAC"
else
    echo "ERROR: No javac >= $REQUIRED_MAJOR found."
    echo "       Install OpenJDK $REQUIRED_MAJOR: sudo apt install openjdk-${REQUIRED_MAJOR}-jdk"
    exit 1
fi

# Compile
echo ""
echo "[*] Compiling patched DebugOptions.java..."
rm -rf "$WORK_DIR"
mkdir -p "$OUTPUT_DIR"

TEMP_SRC="$WORK_DIR/src/zombie/debug"
mkdir -p "$TEMP_SRC"
cp "$SOURCE_FILE" "$TEMP_SRC/DebugOptions.java"

if $DRY_RUN; then
    echo "    [DryRun] Would run: $JAVAC --release 25 -cp $GAME_JAR -d $OUTPUT_DIR $TEMP_SRC/DebugOptions.java"
else
    "$JAVAC" --release 25 -cp "$GAME_JAR" -d "$OUTPUT_DIR" "$TEMP_SRC/DebugOptions.java"
    echo "    Compiled successfully."
fi

# Deploy
echo ""
echo "[*] Deploying class files to: $DEPLOY_DIR"
$DRY_RUN || mkdir -p "$DEPLOY_DIR"

COMPILED_DIR="$OUTPUT_DIR/zombie/debug"

if $DRY_RUN; then
    echo "    [DryRun] Would deploy DebugOptions.class + ${#INNER_CLASSES[@]} inner class(es)"
else
    count=0
    for cf in "$COMPILED_DIR"/DebugOptions*.class; do
        [[ -f "$cf" ]] || continue
        cp "$cf" "$DEPLOY_DIR/$(basename "$cf")"
        echo "    Deployed: $(basename "$cf")"
        (( count++ )) || true
    done

    echo ""
    echo "=== Patch applied successfully ==="
    echo ""
    echo "Deployed $count class file(s) to $DEPLOY_DIR"
    echo ""
    echo "Enabled threading options:"
    echo "  Threading.Animation = true  (AnimationPlayer.Update on ForkJoinPool)"
    echo "  Threading.World     = true  (buildings/static/DB updates concurrent)"
    echo "  Threading.Ambient   = true  (FMOD ambient emitters concurrent)"
    echo ""
    echo "Expected: K-overlay 'GPU wait' drops as render thread no longer waits"
    echo "          on serial animation. Monitor for crash on vehicle collisions."
    echo ""
    echo "Restart the game/server for changes to take effect."
fi
echo ""
