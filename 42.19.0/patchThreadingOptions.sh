#!/usr/bin/env bash
# patchThreadingOptions.sh - Compile & deploy patched DebugOptions.class (Linux/macOS)
#
# Threading Optimization Patch - DebugOptions (Build 42.19)
#
# Context: Project Zomboid ships with three threading options that are built,
# tested, and wired up in the engine but default to false:
#
#   Threading.Animation  (DebugOptions.threadAnimation)
#     Offloads ALL AnimationPlayer.Update() calls for every moving object
#     (vehicles, zombies, characters) to the PZForkJoinPool. In IsoWorld,
#     MovingObjectUpdateScheduler.postupdate() runs concurrently via
#     CompletableFuture.runAsync() and is joined at FinishAnimation().
#     With many active zombies or parked vehicles this is a dominant CPU cost.
#
#   Threading.World  (DebugOptions.threadWorld)
#     Runs updateBuildings(), ObjectRenderEffects.updateStatic(), DB updates,
#     addCoopPlayers processing, and virtual animals concurrently with the
#     game thread's main update path (climate, pathfinding etc).
#
#   Threading.Ambient  (DebugOptions.threadAmbient)
#     Offloads ObjectAmbientEmitters.update() (FMOD ambient sound emitter
#     polling) to the ForkJoinPool concurrently with game logic.
#     Less impactful on dedicated servers (no audio), included for completeness.
#
# All three use newOption() (not newDebugOnlyOption()), meaning they are
# production-safe and just happen to default to false.
#
# PZForkJoinPool uses Runtime.getRuntime().availableProcessors() - 1 threads.
# On an 8-core machine this is 7 worker threads already standing by.
#
# The patched DebugOptions.java:
#   - Changes defaults from false → true for all three options
#   - Overrides load() to force them back to true after reading debug-options.ini,
#     so a pre-existing cached ini with old false values cannot re-disable them
#
# NOTE: threadAnimation and threadWorld are not gated by !GameServer.server
# in IsoWorld.updateWorld() / updateInternal(), so both fire on the dedicated
# server. This is verified against Build 42.19 IsoWorld.java decompiled source.
#
# Usage:
#   ./patchThreadingOptions.sh [--pz-dir /opt/pzserver] [--dry-run] [--revert]
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

PATCH_NAME="Threading Optimization - DebugOptions"
# Auto-detect JAR location (Linux server: java/ subdir; Windows: PZ root)
if [[ -f "$PZ_DIR/java/projectzomboid.jar" ]]; then
    JAR_FILE="$PZ_DIR/java/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR/java"
else
    JAR_FILE="$PZ_DIR/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR"
fi
DEPLOY_DIR="$DEPLOY_BASE/zombie/debug"
BACKUP_DIR="$SCRIPT_DIR/backups/ThreadingOptions"
WORK_DIR="/tmp/pzpatch_threadingoptions"
OUTPUT_DIR="$WORK_DIR/classes"

SOURCE_FILE="$SCRIPT_DIR/src/zombie/debug/DebugOptions.java"
REQUIRED_MAJOR=25

INNER_CLASSES=("DebugOptions\$Checks")

echo ""
echo "=== PZ Classpath Override Patch ==="
echo "=== $PATCH_NAME ==="
echo ""

# --- Revert mode ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting patch..."
    reverted=false
    if [[ -f "$DEPLOY_DIR/DebugOptions.class" ]]; then
        $DRY_RUN || rm -f "$DEPLOY_DIR/DebugOptions.class"
        echo "    Removed DebugOptions.class"
        reverted=true
    fi
    for inner in "${INNER_CLASSES[@]}"; do
        path="$DEPLOY_DIR/${inner}.class"
        if [[ -f "$path" ]]; then
            $DRY_RUN || rm -f "$path"
            echo "    Removed ${inner}.class"
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

# --- Validation ---
[[ -f "$JAR_FILE" ]]    || { echo "ERROR: Game JAR not found: $JAR_FILE"; exit 1; }
[[ -f "$SOURCE_FILE" ]] || { echo "ERROR: Patched source not found: $SOURCE_FILE"; exit 1; }

# --- Backup ---
if [[ ! -d "$BACKUP_DIR" ]] || [[ -z "$(ls -A "$BACKUP_DIR" 2>/dev/null)" ]]; then
    echo "[*] Backing up original DebugOptions classes..."
    mkdir -p "$BACKUP_DIR"
    tmpdir=$(mktemp -d)
    pushd "$tmpdir" > /dev/null
    jar xf "$JAR_FILE" zombie/debug/DebugOptions.class 2>/dev/null || true
    for inner in "${INNER_CLASSES[@]}"; do
        jar xf "$JAR_FILE" "zombie/debug/${inner}.class" 2>/dev/null || true
    done
    if [[ -d "$tmpdir/zombie/debug" ]]; then
        cp "$tmpdir"/zombie/debug/DebugOptions*.class "$BACKUP_DIR/" 2>/dev/null || true
        for f in "$BACKUP_DIR"/DebugOptions*.class; do
            [[ "$f" == *.original ]] || mv "$f" "${f}.original"
        done
        echo "    Backed up $(ls "$BACKUP_DIR" | wc -l) file(s)"
    else
        echo "    WARNING: Could not extract original classes."
    fi
    popd > /dev/null
    rm -rf "$tmpdir"
else
    echo "[*] Backup already exists: $BACKUP_DIR"
fi

# --- Find javac ---
find_javac() {
    local candidates=("$SCRIPT_DIR/jdk/bin/javac")
    for jh in /usr/lib/jvm/java-${REQUIRED_MAJOR}*/bin/javac \
              /usr/local/lib/jvm/java-${REQUIRED_MAJOR}*/bin/javac \
              /usr/lib/jvm/zulu${REQUIRED_MAJOR}*/bin/javac; do
        candidates+=("$jh")
    done
    which javac 2>/dev/null && candidates+=("$(which javac)")
    for c in "${candidates[@]}"; do
        if [[ -x "$c" ]]; then
            local ver; ver=$("$c" -version 2>&1 | grep -oP '(?<=javac )\d+' | head -1)
            if [[ "${ver:-0}" -ge "$REQUIRED_MAJOR" ]] 2>/dev/null; then
                echo "$c"; return 0
            fi
        fi
    done
    return 1
}

echo "[*] Searching for javac >= $REQUIRED_MAJOR..."
JAVAC=""
if JAVAC=$(find_javac); then
    echo "    Found: $JAVAC"
else
    echo "ERROR: No javac >= $REQUIRED_MAJOR found."
    echo "       Install OpenJDK $REQUIRED_MAJOR: sudo apt install openjdk-${REQUIRED_MAJOR}-jdk"
    exit 1
fi

# --- Compile ---
echo ""
echo "[*] Compiling patched DebugOptions.java..."
rm -rf "$WORK_DIR"
mkdir -p "$OUTPUT_DIR"

TEMP_SRC="$WORK_DIR/src/zombie/debug"
mkdir -p "$TEMP_SRC"
cp "$SOURCE_FILE" "$TEMP_SRC/DebugOptions.java"

if $DRY_RUN; then
    echo "    [DryRun] Would run: $JAVAC --release 25 -cp $JAR_FILE -d $OUTPUT_DIR $TEMP_SRC/DebugOptions.java"
else
    "$JAVAC" --release 25 -cp "$JAR_FILE" -d "$OUTPUT_DIR" "$TEMP_SRC/DebugOptions.java"
    echo "    Compiled successfully."
fi

# --- Deploy ---
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
    echo "Enabled threading options (Build 42.19):"
    echo "  Threading.Animation = true  (AnimationPlayer.Update on ForkJoinPool)"
    echo "  Threading.World     = true  (buildings/static/DB/animals concurrent)"
    echo "  Threading.Ambient   = true  (FMOD ambient emitters concurrent)"
    echo ""
    echo "Restart the server for changes to take effect."
fi
echo ""
