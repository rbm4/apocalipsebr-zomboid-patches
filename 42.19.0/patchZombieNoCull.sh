#!/usr/bin/env bash
# patchZombieNoCull.sh
# Compiles patched MovingObjectUpdateScheduler.java and deploys the .class
# to Project Zomboid via classpath override.
#
# Zombie NoCull Fix - MovingObjectUpdateScheduler.postupdate() (Build 42.19)
#
# Build 42.19 added a call to ZombieCountOptimiser.deleteZombies() at the
# start of MovingObjectUpdateScheduler.postupdate(), which runs every frame
# on the dedicated server. This aggressively culls zombie populations from
# ~5000 down to ~400 on servers with many connected players.
#
# In Build 42.18 this cull did not exist server-side. This patch restores
# the 42.18 behavior by compiling a version of the class with the
# deleteZombies() call removed from postupdate().
#
# Note: ZombieCountOptimiser.startCount() and incrementZombie() in
# startFrame() are NOT removed - only the actual deletion step is suppressed.
#
# Strategy: classpath override via "java/." before "java/projectzomboid.jar".
# The JVM loads .class files from the filesystem before looking inside the JAR.
# The original JAR is untouched.
#
# Usage:
#   ./patchZombieNoCull.sh [--pz-dir PATH] [--dry-run] [--revert]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_NAME="Zombie NoCull Fix - MovingObjectUpdateScheduler.postupdate()"
JAR_ENTRY="zombie/MovingObjectUpdateScheduler.class"
SOURCE_FILE="$SCRIPT_DIR/src/zombie/MovingObjectUpdateScheduler.java"

# --- Argument parsing ---
PZ_DIR="/opt/pzserver"
DRY_RUN=false
REVERT=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pz-dir)    PZ_DIR="$2";  shift 2 ;;
        --dry-run)   DRY_RUN=true; shift ;;
        --revert|-r) REVERT=true;  shift ;;
        *)
            echo "Unknown option: $1" >&2
            echo "Usage: $0 [--pz-dir PATH] [--dry-run] [--revert]" >&2
            exit 1
            ;;
    esac
done

JAR_FILE="$PZ_DIR/java/projectzomboid.jar"
CLASSPATH_DIR="$PZ_DIR/java"
DEPLOY_DIR="$CLASSPATH_DIR/zombie"
BACKUP_DIR="$SCRIPT_DIR/backups/MovingObjectUpdateScheduler"
WORK_DIR="/tmp/pzpatch_zombienocull"

echo ""
echo "=== $PATCH_NAME ==="
echo ""

# --- Handle --revert ---
if [[ "$REVERT" == "true" ]]; then
    if [[ -f "$DEPLOY_DIR/MovingObjectUpdateScheduler.class" ]]; then
        rm -f "$DEPLOY_DIR/MovingObjectUpdateScheduler.class"
        echo "    Removed: MovingObjectUpdateScheduler.class"
        echo ""
        echo "=== Patch reverted - original JAR behavior restored ==="
    else
        echo "    No patch file found to remove."
    fi
    exit 0
fi

# --- Find javac (prefer PATH set by main patch.sh, fall back to known location) ---
JAVAC=""
if command -v javac &>/dev/null; then
    JAVAC=$(command -v javac)
fi
if [[ -z "$JAVAC" ]]; then
    JAVAC="/usr/lib/jvm/java-25-openjdk-amd64/bin/javac"
fi

# --- Validate ---
if [[ ! -x "$JAVAC" ]]; then
    echo "ERROR: javac not found or not executable: $JAVAC" >&2
    echo "       Install JDK 25: apt install openjdk-25-jdk-headless" >&2
    echo "       Or run the main patch.sh which handles JDK setup automatically." >&2
    exit 1
fi

if [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: JAR not found at $JAR_FILE" >&2
    echo "       Set --pz-dir to your Project Zomboid server installation." >&2
    exit 1
fi

if [[ ! -f "$SOURCE_FILE" ]]; then
    echo "ERROR: Patched source not found: $SOURCE_FILE" >&2
    exit 1
fi

echo "[*] PZ dir:  $PZ_DIR"
echo "[*] JAR:     $JAR_FILE"
echo "[*] Deploy:  $DEPLOY_DIR"
echo "[*] javac:   $JAVAC ($("$JAVAC" -version 2>&1 | head -1))"
echo ""

# --- Backup original class ---
mkdir -p "$BACKUP_DIR"
BACKUP_CLASS="$BACKUP_DIR/MovingObjectUpdateScheduler.class.original"
if [[ ! -f "$BACKUP_CLASS" ]]; then
    echo "[*] Extracting original MovingObjectUpdateScheduler.class from JAR..."
    EXTRACT_TMP="$SCRIPT_DIR/tmp-extract-mous"
    rm -rf "$EXTRACT_TMP"
    mkdir -p "$EXTRACT_TMP"
    pushd "$EXTRACT_TMP" > /dev/null
    jar xf "$JAR_FILE" "$JAR_ENTRY" 2>/dev/null || true
    if [[ -f "$EXTRACT_TMP/$JAR_ENTRY" ]]; then
        cp "$EXTRACT_TMP/$JAR_ENTRY" "$BACKUP_CLASS"
        echo "    Backed up: $BACKUP_CLASS"
    else
        echo "    WARNING: Could not extract original class."
    fi
    popd > /dev/null
    rm -rf "$EXTRACT_TMP"
else
    echo "[*] Backup already exists: $BACKUP_CLASS"
fi

# --- Compile ---
echo ""
echo "[*] Compiling patched MovingObjectUpdateScheduler.java..."
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/classes"
mkdir -p "$WORK_DIR/src/zombie"

cp "$SOURCE_FILE" "$WORK_DIR/src/zombie/MovingObjectUpdateScheduler.java"

"$JAVAC" --release 25 \
    -cp "$JAR_FILE" \
    -d  "$WORK_DIR/classes" \
    -encoding UTF-8 \
    "$WORK_DIR/src/zombie/MovingObjectUpdateScheduler.java"

echo "    Compiled successfully."

# --- Deploy ---
echo ""
COMPILED_CLASS="$WORK_DIR/classes/zombie/MovingObjectUpdateScheduler.class"

if [[ "$DRY_RUN" == "true" ]]; then
    echo "[*] DRY RUN: Would deploy to: $DEPLOY_DIR/"
    echo "    Would copy: MovingObjectUpdateScheduler.class"
else
    echo "[*] Deploying..."
    mkdir -p "$DEPLOY_DIR"

    if [[ -f "$DEPLOY_DIR/MovingObjectUpdateScheduler.class" ]]; then
        ts=$(date +%Y%m%d_%H%M%S)
        cp "$DEPLOY_DIR/MovingObjectUpdateScheduler.class" "$BACKUP_DIR/MovingObjectUpdateScheduler.class.prev_$ts"
        echo "    Previous override backed up: MovingObjectUpdateScheduler.class.prev_$ts"
    fi

    cp "$COMPILED_CLASS" "$DEPLOY_DIR/MovingObjectUpdateScheduler.class"
    echo "    Deployed: MovingObjectUpdateScheduler.class"
fi

# Cleanup
rm -rf "$WORK_DIR"

echo ""
echo "=== Done ==="
echo ""
echo "Patch deployed: $PATCH_NAME"
echo ""
echo "How it works:"
echo "  PZ classpath has 'java/.' before 'java/projectzomboid.jar', so the"
echo "  loose .class file at '$DEPLOY_DIR' takes precedence over the JAR."
echo ""
echo "To revert:"
echo "  ./patchZombieNoCull.sh --revert"
echo "  (or delete MovingObjectUpdateScheduler.class from: $DEPLOY_DIR)"
echo ""
