#!/usr/bin/env bash
# patchServerTelemetry.sh
# Compiles and deploys ApocBR server responsiveness telemetry classes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_NAME="ApocBR Server Telemetry"
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

JAR_FILE="$PZ_DIR/java/projectzomboid.jar"
CLASSPATH_DIR="$PZ_DIR/java"
WORK_DIR="$(mktemp -d /tmp/pzpatch_servertelemetry.XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT
SRC_ROOT="$SCRIPT_DIR/src"
BACKUP_DIR="$SCRIPT_DIR/backups/ServerTelemetry"
CLASSES=(
  "zombie/ApocBRServerTelemetry.class"
  "zombie/network/GameServer.class"
  "zombie/network/PlayerDownloadServer.class"
  "zombie/network/PlayerDownloadServer\$EThreadCommand.class"
  "zombie/network/PlayerDownloadServer\$WorkerThread.class"
  "zombie/network/PlayerDownloadServer\$WorkerThreadCommand.class"
)

JAVAC=""
if command -v javac &>/dev/null; then JAVAC=$(command -v javac); fi
if [[ -z "$JAVAC" ]]; then JAVAC="/usr/lib/jvm/java-25-openjdk-amd64/bin/javac"; fi

echo ""
echo "=== $PATCH_NAME ==="
echo ""

if [[ "$REVERT" == "true" ]]; then
    for class_file in "${CLASSES[@]}"; do
        if [[ -f "$CLASSPATH_DIR/$class_file" ]]; then
            rm -f "$CLASSPATH_DIR/$class_file"
            echo "    Removed: $class_file"
        fi
    done
    find "$CLASSPATH_DIR/zombie/network" -maxdepth 1 -name 'GameServer$*.class' -delete 2>/dev/null || true
    echo "=== Patch reverted ==="
    exit 0
fi

if [[ ! -x "$JAVAC" ]]; then
    echo "ERROR: javac not found or not executable: $JAVAC" >&2
    exit 1
fi
if [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: JAR not found at $JAR_FILE" >&2
    exit 1
fi

echo "[*] PZ dir:  $PZ_DIR"
echo "[*] JAR:     $JAR_FILE"
echo "[*] Deploy:  $CLASSPATH_DIR"
echo "[*] javac:   $JAVAC ($("$JAVAC" -version 2>&1 | head -1))"
echo ""

mkdir -p "$WORK_DIR/classes"

echo "[*] Compiling telemetry patched sources..."
JAVAC_LOG="$WORK_DIR/javac.log"
if ! "$JAVAC" --release 25 \
    -Xlint:none \
    -implicit:none \
    -cp "$JAR_FILE" \
    -sourcepath "$SRC_ROOT" \
    -d "$WORK_DIR/classes" \
    -encoding UTF-8 \
    "$SRC_ROOT/zombie/ApocBRServerTelemetry.java" \
    "$SRC_ROOT/zombie/network/GameServer.java" \
    "$SRC_ROOT/zombie/network/PlayerDownloadServer.java" >"$JAVAC_LOG" 2>&1; then
    cat "$JAVAC_LOG" >&2
    exit 1
fi
grep -Ev "^(Note:|Recompile with)" "$JAVAC_LOG" || true
echo "    Compiled successfully."
if [[ "$DRY_RUN" == "true" ]]; then
    echo "[*] DRY RUN: Would deploy telemetry classes to $CLASSPATH_DIR"
else
    echo "[*] Deploying..."
    mkdir -p "$CLASSPATH_DIR/zombie/network"
    mkdir -p "$CLASSPATH_DIR/zombie"
    mkdir -p "$BACKUP_DIR"
    ts=$(date +%Y%m%d_%H%M%S)
    for class_file in "${CLASSES[@]}"; do
        if [[ -f "$CLASSPATH_DIR/$class_file" ]]; then
            safe_name="${class_file//\//_}"
            cp "$CLASSPATH_DIR/$class_file" "$BACKUP_DIR/${safe_name}.prev_$ts"
        fi
    done
    echo "    Existing override backed up when present: $BACKUP_DIR"
    cp "$WORK_DIR/classes/zombie/ApocBRServerTelemetry.class" "$CLASSPATH_DIR/zombie/"
    cp "$WORK_DIR/classes/zombie/network/GameServer"*.class "$CLASSPATH_DIR/zombie/network/" 2>/dev/null || true
    cp "$WORK_DIR/classes/zombie/network/PlayerDownloadServer"*.class "$CLASSPATH_DIR/zombie/network/"
    echo "    Deployed telemetry classes."
fi

echo ""
echo "=== Done ==="
echo "Config: -Dapocbr.telemetry.enabled=true -Dapocbr.telemetry.intervalMs=30000"
