#!/usr/bin/env bash
# patchServerCellTick.sh - Compile and deploy patched ServerMap (parallel cell ticking).
#
# Server Cell Tick Parallelization (PATCH-F)
#
# Problem:
#   The dedicated server runs its main game loop entirely on one thread. Each
#   tick, ServerMap.postupdate() iterates every loaded cell sequentially and
#   calls ServerCell.update(), which in turn calls IsoChunk.update() for each
#   of the 64 chunks in the cell. With many loaded cells (large player count
#   or spread-out players), this loop becomes a CPU bottleneck while other
#   cores sit idle.
#
# Fix:
#   Split postupdate() into two phases:
#
#   Phase 1 (serial, main thread):
#     - Handle cancel-loading and cell unloads (unchanged, requires
#       ServerLOS.instance.suspend/resume and loadedCells list mutation).
#     - Collect loaded+relevant cells into a local list.
#
#   Phase 2 (parallel, PZForkJoinPool):
#     - Submit each cell's update() as a CompletableFuture to the existing
#       PZForkJoinPool (availableProcessors - 1 threads).
#     - Join all futures before returning, so the main-thread sequencing of
#       NetworkZombiePacker.postupdate() and chunkLoader.updateSaved() is
#       preserved.
#     - When only one cell needs updating the parallel path is skipped.
#
# Thread-safety analysis:
#   - ServerCell.update() iterates its own 8x8 chunk array exclusively; no two
#     cells share chunk references.
#   - IsoChunk.doAttachments is a read-only static field during ticking.
#   - IsoChunk.ragdollControllersForAddToWorld is always null on a dedicated
#     server (no code populates it server-side), so the Bullet addToWorld()
#     branch never executes.
#   - updateVehicleStory() reads IsoWorld.instance.getMetaChunk(wx, wy) keyed
#     on the chunk's own coordinates; different cells access different meta
#     chunks. The zone.hourLastSeen++ write is benign even with a race (at
#     worst a vehicle story fires twice in the same hour, not a crash).
#   - ServerLOS remains suspended for the full duration of postupdate(),
#     preventing any LOS-thread interference with chunk state.
#
# Usage:
#   ./patchServerCellTick.sh [--pz-dir /opt/pzserver] [--dry-run] [--revert]
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

PATCH_NAME="Server Cell Tick Parallelization (PATCH-F)"
# Auto-detect JAR location (Linux server: java/ subdir; Windows: PZ root)
if [[ -f "$PZ_DIR/java/projectzomboid.jar" ]]; then
    JAR_FILE="$PZ_DIR/java/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR/java"
else
    JAR_FILE="$PZ_DIR/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR"
fi
DEPLOY_DIR="$DEPLOY_BASE/zombie/network"
BACKUP_DIR="$SCRIPT_DIR/backups/ServerCellTick"
WORK_DIR="/tmp/pzpatch_servercellick"
OUTPUT_DIR="$WORK_DIR/classes"

SRC_SERVER_MAP="$SCRIPT_DIR/src/zombie/network/ServerMap.java"

REQUIRED_MAJOR=25

echo ""
echo "=== PZ Classpath Override Patch ==="
echo "=== $PATCH_NAME ==="
echo ""

# --- Revert mode ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting patch..."
    reverted=false
    target="$DEPLOY_DIR/ServerMap.class"
    if [ -f "$target" ]; then
        rm -f "$target"
        echo "    Removed ServerMap.class"
        reverted=true
    fi
    # Remove inner classes
    for f in "$DEPLOY_DIR"/ServerMap\$*.class; do
        if [ -f "$f" ]; then
            rm -f "$f"
            echo "    Removed $(basename "$f")"
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
if [ ! -f "$SRC_SERVER_MAP" ]; then
    echo "ERROR: Source not found: $SRC_SERVER_MAP"
    exit 1
fi

echo "[*] PZ dir: $PZ_DIR"
echo ""

# --- Backup originals from JAR ---
echo "[1/4] Backing up originals from JAR..."
mkdir -p "$BACKUP_DIR"
backup_target="$BACKUP_DIR/ServerMap.class.bak"
if [ ! -f "$backup_target" ]; then
    if unzip -p "$JAR_FILE" "zombie/network/ServerMap.class" > "$backup_target" 2>/dev/null; then
        echo "    Backed up: ServerMap.class"
    else
        echo "    Warning: could not extract ServerMap.class from JAR (may be OK if already deployed)"
        rm -f "$backup_target"
    fi
else
    echo "    Already backed up: ServerMap.class"
fi

# --- Compile ---
echo ""
echo "[2/4] Compiling patched sources..."
rm -rf "$WORK_DIR"
mkdir -p "$OUTPUT_DIR"

if [[ "$DRY_RUN" == "true" ]]; then
    echo "    [DRY RUN] Would compile:"
    echo "      $SRC_SERVER_MAP"
    echo "    [DRY RUN] classpath: $JAR_FILE"
    echo ""
    echo "=== Dry run complete. No files changed. ==="
    exit 0
fi

"$JAVAC" \
    -cp "$JAR_FILE" \
    -d "$OUTPUT_DIR" \
    --release 17 \
    "$SRC_SERVER_MAP"

echo "    Compilation successful."

# --- Deploy ---
echo ""
echo "[3/4] Deploying .class files..."
mkdir -p "$DEPLOY_DIR"

deployed=0
for cls_file in "$OUTPUT_DIR/zombie/network/ServerMap.class" \
                "$OUTPUT_DIR/zombie/network/ServerMap"\$*.class; do
    if [ -f "$cls_file" ]; then
        cp "$cls_file" "$DEPLOY_DIR/"
        echo "    Deployed: zombie/network/$(basename "$cls_file")"
        deployed=$((deployed + 1))
    fi
done

if [ "$deployed" -eq 0 ]; then
    echo "ERROR: No compiled ServerMap classes found in $OUTPUT_DIR/zombie/network/"
    exit 1
fi

# --- Summary ---
echo ""
echo "[4/4] Verifying deployment..."
ok=true
for f in "$DEPLOY_DIR/ServerMap.class" "$DEPLOY_DIR"/ServerMap\$*.class; do
    if [ -f "$f" ]; then
        echo "    OK: $f"
    fi
done
if [ ! -f "$DEPLOY_DIR/ServerMap.class" ]; then
    echo "    MISSING: $DEPLOY_DIR/ServerMap.class"
    ok=false
fi

if [[ "$ok" == "true" ]]; then
    echo ""
    echo "=== $PATCH_NAME applied successfully ==="
    echo ""
    echo "ServerMap.postupdate() now dispatches cell.update() calls in parallel"
    echo "across PZForkJoinPool ($(nproc) CPUs → $(($(nproc) - 1)) worker threads)."
    echo "To revert:  $0 --pz-dir $PZ_DIR --revert"
else
    echo ""
    echo "=== Deployment verification FAILED ==="
    exit 1
fi
echo ""
