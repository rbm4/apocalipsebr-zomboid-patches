#!/usr/bin/env bash
# patchNullCraft.sh
# Compiles patched CompressIdenticalItems.java and deploys the .class files
# to Project Zomboid via classpath override.
#
# NullCraft Fix - CompressIdenticalItems.save() Null Guard (Build 42.19)
#
# Bug: when a drying/curing craft (DryingCraftLogic) is in progress and the
# referenced item becomes null (e.g. item despawned, player disconnected),
# the server throws NPE in CompressIdenticalItems.save(ByteBuffer, InventoryItem)
# during chunk serialization:
#
#   NullPointerException: Cannot invoke "InventoryItem.saveWithSize" because
#   "item" is null at CompressIdenticalItems.save(CompressIdenticalItems.java:343)
#
# This corrupts the chunk save, causing vehicles to vanish from vehicles.db
# on the next server boot.
#
# Strategy: classpath override via "java/." before "java/projectzomboid.jar".
# The JVM loads .class files from the filesystem before looking inside the JAR.
# The original JAR is untouched.
#
# Usage:
#   ./patchNullCraft.sh [--pz-dir PATH] [--dry-run] [--revert]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_NAME="NullCraft Fix - CompressIdenticalItems.save() Null Guard"
JAR_ENTRY="zombie/inventory/CompressIdenticalItems.class"
SOURCE_FILE="$SCRIPT_DIR/src/zombie/inventory/CompressIdenticalItems.java"

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
DEPLOY_DIR="$CLASSPATH_DIR/zombie/inventory"
BACKUP_DIR="$SCRIPT_DIR/backups"
WORK_DIR="/tmp/pzpatch_nullcraft"

echo ""
echo "=== $PATCH_NAME ==="
echo ""

# --- Handle --revert ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting patch..."
    reverted=false
    for f in "$DEPLOY_DIR"/CompressIdenticalItems*.class; do
        if [[ -f "$f" ]]; then
            rm -f "$f"
            echo "    Removed $(basename "$f")"
            reverted=true
        fi
    done
    if [[ "$reverted" == "true" ]]; then
        echo ""
        echo "=== Patch reverted ==="
        echo "Original CompressIdenticalItems from JAR will be used on next server start."
    else
        echo "    No patch files found to remove."
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
BACKUP_CLASS="$BACKUP_DIR/CompressIdenticalItems.class.original"
if [[ ! -f "$BACKUP_CLASS" ]]; then
    echo "[*] Extracting original CompressIdenticalItems.class from JAR..."
    EXTRACT_TMP="$SCRIPT_DIR/tmp-extract-nc"
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
echo "[*] Compiling patched CompressIdenticalItems.java..."
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/classes"

# Copy source to a temporary structure matching the package layout for compilation
mkdir -p "$WORK_DIR/src/zombie/inventory"
cp "$SOURCE_FILE" "$WORK_DIR/src/zombie/inventory/CompressIdenticalItems.java"

"$JAVAC" --release 25 \
    -cp "$JAR_FILE" \
    -d  "$WORK_DIR/classes" \
    -encoding UTF-8 \
    "$WORK_DIR/src/zombie/inventory/CompressIdenticalItems.java"

echo "    Compiled successfully."

# --- Deploy ---
echo ""
COMPILED_DIR="$WORK_DIR/classes/zombie/inventory"

if [[ "$DRY_RUN" == "true" ]]; then
    echo "[*] DRY RUN: Would deploy to: $DEPLOY_DIR/"
    for f in "$COMPILED_DIR"/CompressIdenticalItems*.class; do
        [[ -f "$f" ]] && echo "    Would copy: $(basename "$f")"
    done
else
    echo "[*] Deploying..."
    mkdir -p "$DEPLOY_DIR"

    # Back up any existing deployed override
    if [[ -f "$DEPLOY_DIR/CompressIdenticalItems.class" ]]; then
        ts=$(date +%Y%m%d_%H%M%S)
        cp "$DEPLOY_DIR/CompressIdenticalItems.class" "$BACKUP_DIR/CompressIdenticalItems.class.prev_$ts"
        echo "    Previous override backed up: CompressIdenticalItems.class.prev_$ts"
    fi

    for f in "$COMPILED_DIR"/CompressIdenticalItems*.class; do
        if [[ -f "$f" ]]; then
            cp "$f" "$DEPLOY_DIR/$(basename "$f")"
            echo "    Deployed: $(basename "$f")"
        fi
    done
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
echo "  loose .class files at '$DEPLOY_DIR' take precedence over the JAR."
echo ""
echo "To revert:"
echo "  ./patchNullCraft.sh --revert"
echo "  (or delete CompressIdenticalItems*.class from: $DEPLOY_DIR)"
echo ""
