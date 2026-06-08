#!/usr/bin/env bash
# patchScheduler.sh - Compile and deploy patched MovingObjectUpdateScheduler.java
# Patch D: QUARTER simulation tier for parked vehicles on the client.
# See patchScheduler.ps1 for full description.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PZ_DIR="${1:-/home/steam/pz}"
GAME_JAR="$PZ_DIR/projectzomboid.jar"
DEPLOY_DIR="$PZ_DIR/zombie"
BACKUP_DIR="$SCRIPT_DIR/backups/MovingObjectUpdateScheduler"
LOCAL_JDK_DIR="$SCRIPT_DIR/jdk"
WORK_DIR="/tmp/pzpatch_scheduler"
OUTPUT_DIR="$WORK_DIR/classes"
SOURCE_FILE="$SCRIPT_DIR/src/zombie/MovingObjectUpdateScheduler.java"
REQUIRED_MAJOR=25

echo ""
echo "=== Scheduler Optimization - Patch D ==="
echo ""

# Revert mode
if [[ "${2:-}" == "--revert" ]]; then
    if [ -f "$DEPLOY_DIR/MovingObjectUpdateScheduler.class" ]; then
        rm -f "$DEPLOY_DIR/MovingObjectUpdateScheduler.class"
        echo "    Removed: MovingObjectUpdateScheduler.class"
        echo ""
        echo "=== Patch reverted ==="
    else
        echo "    No patch file found to remove."
    fi
    exit 0
fi

# Checks
if [ ! -f "$GAME_JAR" ]; then
    echo "ERROR: Game JAR not found: $GAME_JAR"
    echo "       Usage: $0 /path/to/ProjectZomboid [--revert]"
    exit 1
fi
if [ ! -f "$SOURCE_FILE" ]; then
    echo "ERROR: Source file not found: $SOURCE_FILE"
    exit 1
fi

# Find javac
find_javac() {
    local candidates=("$LOCAL_JDK_DIR/bin/javac")
    for jh in /usr/lib/jvm/java-${REQUIRED_MAJOR}*/bin/javac \
              /usr/local/lib/jvm/java-${REQUIRED_MAJOR}*/bin/javac; do
        candidates+=("$jh")
    done
    for c in "${candidates[@]}"; do
        if [ -x "$c" ]; then
            local ver; ver=$("$c" -version 2>&1 | grep -oP '(?<=javac )\d+' | head -1)
            if [ "${ver:-0}" -ge "$REQUIRED_MAJOR" ] 2>/dev/null; then
                echo "$c"; return 0
            fi
        fi
    done
    local path_javac; path_javac=$(command -v javac 2>/dev/null || true)
    if [ -n "$path_javac" ]; then
        local ver; ver=$("$path_javac" -version 2>&1 | grep -oP '(?<=javac )\d+' | head -1)
        if [ "${ver:-0}" -ge "$REQUIRED_MAJOR" ] 2>/dev/null; then
            echo "$path_javac"; return 0
        fi
    fi
    return 1
}

JAVAC=$(find_javac || true)
if [ -z "$JAVAC" ]; then
    echo "ERROR: javac >= $REQUIRED_MAJOR not found."
    echo "       On Ubuntu/Debian: sudo apt install openjdk-${REQUIRED_MAJOR}-jdk"
    exit 1
fi
echo "[*] Using: $JAVAC ($($JAVAC -version 2>&1 | head -1))"

# Backup
mkdir -p "$BACKUP_DIR"
if [ ! -f "$BACKUP_DIR/MovingObjectUpdateScheduler.class.original" ]; then
    echo "[*] Backing up original MovingObjectUpdateScheduler.class..."
    cd /tmp && mkdir -p pzpatch_sched_backup
    cd pzpatch_sched_backup
    jar xf "$GAME_JAR" zombie/MovingObjectUpdateScheduler.class 2>/dev/null || true
    if [ -f zombie/MovingObjectUpdateScheduler.class ]; then
        cp zombie/MovingObjectUpdateScheduler.class "$BACKUP_DIR/MovingObjectUpdateScheduler.class.original"
        echo "    Backed up."
    else
        echo "    WARNING: Could not extract original class."
    fi
    cd /tmp && rm -rf pzpatch_sched_backup
fi

# Compile
echo ""
echo "[*] Compiling MovingObjectUpdateScheduler.java..."
rm -rf "$WORK_DIR"
mkdir -p "$OUTPUT_DIR"
TEMP_SRC="$WORK_DIR/src/zombie"
mkdir -p "$TEMP_SRC"
cp "$SOURCE_FILE" "$TEMP_SRC/MovingObjectUpdateScheduler.java"

"$JAVAC" --release 25 \
    -cp "$GAME_JAR" \
    -d "$OUTPUT_DIR" \
    "$TEMP_SRC/MovingObjectUpdateScheduler.java"

echo "    Compiled successfully."

# Deploy
echo ""
echo "[*] Deploying to: $DEPLOY_DIR"
mkdir -p "$DEPLOY_DIR"
cp "$OUTPUT_DIR/zombie/MovingObjectUpdateScheduler.class" "$DEPLOY_DIR/MovingObjectUpdateScheduler.class"
echo "    Deployed: MovingObjectUpdateScheduler.class"

echo ""
echo "=== Patch D applied successfully ==="
echo ""
echo "Effect: parked vehicles (no driver + engine idle) run update() every 4 frames"
echo "        instead of every frame. With 40 vehicles: max 10 per frame instead of 40."
echo ""
echo "Restart the game/server for changes to take effect."
echo ""
