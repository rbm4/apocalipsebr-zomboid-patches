#!/usr/bin/env bash
# patchPathfindSafety.sh - Compile and deploy patched PathfindNative + ChunkUpdateTask.
#
# Pathfind Safety Patch - Stale-Chunk Guard
#
# Problem:
#   After several hours of uptime, the dedicated server crashes with a SIGSEGV
#   in libPZPathFind64.so at Square::init(int, int, int)+0xa. The 'this' pointer
#   (RDI register) = 0x30, which is not a valid heap address. This happens on the
#   PathfindNativeThread when PathfindNative.updateChunk() is called for a chunk
#   whose native state has already been freed or is in an inconsistent condition.
#
#   Root cause: a ChunkUpdateTask can remain in the chunkTaskQueue after the
#   corresponding chunk has been removed from native pathfind state. When it
#   eventually executes, the native Square[] array for that slot is gone, and
#   Square::init() receives a garbage pointer, crashing the JVM process.
#
# Fix (Java-level, two patched classes):
#   PathfindNative:
#     Adds a ConcurrentHashMap<Long, Short> activeChunkLoadIds.
#     addChunkToWorld()    → registers  (wx, wy) → loadId before queuing.
#     removeChunkFromWorld() → unregisters (wx, wy) before queuing the remove.
#     stop()               → clears the map on shutdown.
#
#   ChunkUpdateTask:
#     execute() checks activeChunkLoadIds before calling updateChunk(). If the
#     entry is absent (chunk removed) or has a different loadId (chunk reloaded),
#     the call is skipped, preventing the SIGSEGV.
#
# Limitations:
#   - A SIGSEGV inside JNI native code terminates the entire JVM; it cannot be
#     caught with try/catch. This patch eliminates the most common trigger (stale
#     queued tasks) but cannot rule out other internal bugs in libPZPathFind64.so.
#   - The patch is best-effort: if the main thread calls removeChunkFromWorld()
#     and the pathfind thread is already mid-way through updateChunk() for that
#     chunk, the native call still proceeds (the task passed the map check before
#     removal). This window is extremely narrow in practice.
#
# Usage:
#   ./patchPathfindSafety.sh [--pz-dir /opt/pzserver] [--dry-run] [--revert]
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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

PATCH_NAME="Pathfind Safety - Stale-Chunk Guard"
JAR_FILE="$PZ_DIR/projectzomboid.jar"
DEPLOY_DIR="$PZ_DIR/zombie/pathfind/nativeCode"
BACKUP_DIR="$SCRIPT_DIR/backups/PathfindSafety"
WORK_DIR="/tmp/pzpatch_pathfindsafety"
OUTPUT_DIR="$WORK_DIR/classes"

SRC_PATHFIND_NATIVE="$SCRIPT_DIR/src/zombie/pathfind/nativeCode/PathfindNative.java"
SRC_CHUNK_UPDATE_TASK="$SCRIPT_DIR/src/zombie/pathfind/nativeCode/ChunkUpdateTask.java"

REQUIRED_MAJOR=25

echo ""
echo "=== PZ Classpath Override Patch ==="
echo "=== $PATCH_NAME ==="
echo ""

# --- Revert mode ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting patch..."
    reverted=false
    for cls in PathfindNative ChunkUpdateTask; do
        target="$DEPLOY_DIR/${cls}.class"
        if [ -f "$target" ]; then
            rm -f "$target"
            echo "    Removed ${cls}.class"
            reverted=true
        fi
    done
    if [[ "$reverted" == "true" ]]; then
        echo ""
        echo "=== Patch reverted. Original classes from JAR will be used on next server start. ==="
    else
        echo "    No patch files found to remove."
    fi
    exit 0
fi

# --- Find javac ---
find_javac() {
    local candidates=("$SCRIPT_DIR/jdk/bin/javac")
    for jh in /usr/lib/jvm/java-${REQUIRED_MAJOR}*/bin/javac \
              /usr/local/lib/jvm/java-${REQUIRED_MAJOR}*/bin/javac \
              /usr/lib/jvm/zulu${REQUIRED_MAJOR}*/bin/javac; do
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
    echo "       On Ubuntu/Debian: sudo apt install openjdk-${REQUIRED_MAJOR}-jdk-headless"
    echo "       Or run the main patch.sh which handles JDK setup automatically."
    exit 1
fi
echo "[*] javac : $JAVAC ($($JAVAC -version 2>&1 | head -1))"

# --- Validate inputs ---
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: Game JAR not found: $JAR_FILE"
    echo "       Pass --pz-dir to your Project Zomboid server installation."
    exit 1
fi
if [ ! -f "$SRC_PATHFIND_NATIVE" ]; then
    echo "ERROR: Source not found: $SRC_PATHFIND_NATIVE"
    exit 1
fi
if [ ! -f "$SRC_CHUNK_UPDATE_TASK" ]; then
    echo "ERROR: Source not found: $SRC_CHUNK_UPDATE_TASK"
    exit 1
fi

echo "[*] PZ dir: $PZ_DIR"
echo ""

# --- Backup originals from JAR ---
echo "[1/4] Backing up originals from JAR..."
mkdir -p "$BACKUP_DIR"
for cls in PathfindNative ChunkUpdateTask; do
    backup_target="$BACKUP_DIR/${cls}.class.bak"
    if [ ! -f "$backup_target" ]; then
        if unzip -p "$JAR_FILE" "zombie/pathfind/nativeCode/${cls}.class" > "$backup_target" 2>/dev/null; then
            echo "    Backed up: ${cls}.class"
        else
            echo "    Warning: could not extract ${cls}.class from JAR (may be OK if already deployed)"
            rm -f "$backup_target"
        fi
    else
        echo "    Already backed up: ${cls}.class"
    fi
done

# --- Compile ---
echo ""
echo "[2/4] Compiling patched sources..."
rm -rf "$WORK_DIR"
mkdir -p "$OUTPUT_DIR"

if [[ "$DRY_RUN" == "true" ]]; then
    echo "    [DRY RUN] Would compile:"
    echo "      $SRC_PATHFIND_NATIVE"
    echo "      $SRC_CHUNK_UPDATE_TASK"
    echo "    [DRY RUN] classpath: $JAR_FILE"
    echo ""
    echo "=== Dry run complete. No files changed. ==="
    exit 0
fi

"$JAVAC" \
    -cp "$JAR_FILE" \
    -d "$OUTPUT_DIR" \
    --release 17 \
    "$SRC_PATHFIND_NATIVE" \
    "$SRC_CHUNK_UPDATE_TASK"

echo "    Compilation successful."

# --- Deploy ---
echo ""
echo "[3/4] Deploying .class files..."
mkdir -p "$DEPLOY_DIR"

for cls in PathfindNative ChunkUpdateTask; do
    src_class="$OUTPUT_DIR/zombie/pathfind/nativeCode/${cls}.class"
    dst_class="$DEPLOY_DIR/${cls}.class"
    if [ -f "$src_class" ]; then
        cp "$src_class" "$dst_class"
        echo "    Deployed: zombie/pathfind/nativeCode/${cls}.class"
    else
        echo "ERROR: Compiled class not found: $src_class"
        exit 1
    fi
done

# --- Summary ---
echo ""
echo "[4/4] Verifying deployment..."
for cls in PathfindNative ChunkUpdateTask; do
    if [ -f "$DEPLOY_DIR/${cls}.class" ]; then
        echo "    OK: $DEPLOY_DIR/${cls}.class"
    else
        echo "    MISSING: $DEPLOY_DIR/${cls}.class"
    fi
done

echo ""
echo "=== $PATCH_NAME applied successfully ==="
echo ""
echo "The patched classes are loaded by the JVM ahead of projectzomboid.jar."
echo "To revert:  $0 --pz-dir $PZ_DIR --revert"
echo ""
