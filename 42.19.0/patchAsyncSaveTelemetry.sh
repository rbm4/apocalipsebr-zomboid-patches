#!/usr/bin/env bash
# patchAsyncSaveTelemetry.sh
# Combined deploy for Async Background Save (ServerMap) + ApocBR Server Telemetry.
# Owns the shared class outputs together so patchAsyncSave and patchServerTelemetry
# do not overwrite each other's loose classpath overrides.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_NAME="Async Save + Server Telemetry Core Breakdown"
PZ_DIR="/opt/pzserver"
DRY_RUN=false
REVERT=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pz-dir) PZ_DIR="$2"; shift 2 ;;
        --dry-run) DRY_RUN=true; shift ;;
        --revert|-r) REVERT=true; shift ;;
        *) echo "Unknown option: $1" >&2; echo "Usage: $0 [--pz-dir PATH] [--dry-run] [--revert]" >&2; exit 1 ;;
    esac
done

if [[ -f "$PZ_DIR/java/projectzomboid.jar" ]]; then
    JAR_FILE="$PZ_DIR/java/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR/java"
else
    JAR_FILE="$PZ_DIR/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR"
fi

SRC_ROOT="$SCRIPT_DIR/src"
BACKUP_DIR="$SCRIPT_DIR/backups/AsyncSaveTelemetry"
WORK_DIR="$(mktemp -d /tmp/pzpatch_asyncsave_telemetry.XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT
OUTPUT_DIR="$WORK_DIR/classes"
DEPLOY_ZOMBIE="$DEPLOY_BASE/zombie"
DEPLOY_NET="$DEPLOY_BASE/zombie/network"
REQUIRED_MAJOR=25

SOURCES=(
    "$SRC_ROOT/zombie/ApocBRServerTelemetry.java"
    "$SRC_ROOT/zombie/network/GameServer.java"
    "$SRC_ROOT/zombie/network/PlayerDownloadServer.java"
    "$SRC_ROOT/zombie/network/ServerMap.java"
)

CLASSES=(
    "zombie/ApocBRServerTelemetry.class"
    "zombie/network/GameServer.class"
    'zombie/network/GameServer$1.class'
    'zombie/network/GameServer$2.class'
    'zombie/network/GameServer$CCFilter.class'
    'zombie/network/GameServer$DelayedConnection.class'
    'zombie/network/GameServer$MapRemotePlayerVisibility.class'
    'zombie/network/GameServer$s_performance.class'
    "zombie/network/PlayerDownloadServer.class"
    'zombie/network/PlayerDownloadServer$EThreadCommand.class'
    'zombie/network/PlayerDownloadServer$WorkerThread.class'
    'zombie/network/PlayerDownloadServer$WorkerThreadCommand.class'
    "zombie/network/ServerMap.class"
    'zombie/network/ServerMap$DistToCellComparator.class'
    'zombie/network/ServerMap$EThreadCommand.class'
    'zombie/network/ServerMap$ServerCell.class'
    'zombie/network/ServerMap$WorkerThread.class'
    'zombie/network/ServerMap$WorkerThreadCommand.class'
)

echo ""
echo "=== PZ Classpath Override Patch ==="
echo "=== $PATCH_NAME ==="
echo ""

if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting combined patch loose classes..."
    reverted=false
    for class_file in "${CLASSES[@]}"; do
        target="$DEPLOY_BASE/$class_file"
        if [[ -f "$target" ]]; then
            rm -f "$target"
            echo "    Removed: $class_file"
            reverted=true
        fi
    done
    if [[ "$reverted" == "true" ]]; then
        echo "=== Patch reverted. JAR originals restored on next server start. ==="
    else
        echo "    No patch files found to remove."
    fi
    exit 0
fi

if [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: projectzomboid.jar not found at $JAR_FILE" >&2
    echo "       Set --pz-dir to your Project Zomboid server directory." >&2
    exit 1
fi

for src in "${SOURCES[@]}"; do
    if [[ ! -f "$src" ]]; then
        echo "ERROR: Required patched source not found: $src" >&2
        exit 1
    fi
done

JAVAC=""
if command -v javac &>/dev/null; then
    ver=$(javac -version 2>&1 | grep -oE '[0-9]+' | head -1 || echo 0)
    if (( ver >= REQUIRED_MAJOR )); then
        JAVAC=$(command -v javac)
    fi
fi
if [[ -z "$JAVAC" ]]; then
    JAVAC="/usr/lib/jvm/java-${REQUIRED_MAJOR}-openjdk-amd64/bin/javac"
fi
if [[ ! -x "$JAVAC" ]]; then
    echo "ERROR: javac >= $REQUIRED_MAJOR not found." >&2
    echo "       Install: apt install openjdk-${REQUIRED_MAJOR}-jdk-headless" >&2
    exit 1
fi

echo "[*] PZ dir:  $PZ_DIR"
echo "[*] JAR:     $JAR_FILE"
echo "[*] Deploy:  $DEPLOY_BASE"
echo "[*] javac:   $JAVAC ($("$JAVAC" -version 2>&1 | head -1))"
echo ""

mkdir -p "$OUTPUT_DIR"

echo "[*] Compiling combined patched sources..."
JAVAC_LOG="$WORK_DIR/javac.log"
if ! "$JAVAC" --release 25 \
    -Xlint:none \
    -implicit:none \
    -cp "$JAR_FILE" \
    -sourcepath "$SRC_ROOT" \
    -d "$OUTPUT_DIR" \
    -encoding UTF-8 \
    "${SOURCES[@]}" >"$JAVAC_LOG" 2>&1; then
    cat "$JAVAC_LOG" >&2
    exit 1
fi
grep -Ev "^(Note:|Recompile with)" "$JAVAC_LOG" || true
echo "    Compiled successfully."

if [[ "$DRY_RUN" == "true" ]]; then
    echo "[*] DRY RUN: Would deploy combined classes to $DEPLOY_BASE"
    for class_file in "${CLASSES[@]}"; do
        if [[ -f "$OUTPUT_DIR/$class_file" ]]; then
            echo "    $class_file"
        fi
    done
else
    echo "[*] Deploying..."
    mkdir -p "$DEPLOY_ZOMBIE" "$DEPLOY_NET" "$BACKUP_DIR"
    ts=$(date +%Y%m%d_%H%M%S)
    for class_file in "${CLASSES[@]}"; do
        compiled="$OUTPUT_DIR/$class_file"
        [[ -f "$compiled" ]] || continue
        target="$DEPLOY_BASE/$class_file"
        if [[ -f "$target" ]]; then
            safe_name="${class_file//\//_}"
            cp "$target" "$BACKUP_DIR/${safe_name}.prev_$ts" 2>/dev/null || true
        fi
        cp "$compiled" "$target"
        echo "    Deployed: $class_file"
    done
fi

echo ""
echo "=== Done ==="
echo "Patch deployed: $PATCH_NAME"
echo "Config: -Dapocbr.telemetry.enabled=true -Dapocbr.telemetry.intervalMs=30000"
echo "To revert: ./patchAsyncSaveTelemetry.sh --revert"
