#!/usr/bin/env bash
# patchApocalipseBr.sh
# Client-only patch script for Project Zomboid 42.19.
# Compiles every patched Java source under ./src/zombie and deploys the
# generated .class files as loose classpath overrides next to projectzomboid.jar.
# The JVM loads .class files from the filesystem before looking inside the JAR.
# The original JAR is untouched.
#
# Usage:
#   ./patchApocalipseBr.sh [--pz-dir PATH] [--dry-run] [--revert]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_NAME="ApocBR Client FPS Patch (Build 42.19)"
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

# Auto-detect JAR location (Linux client: PZ root; Linux server: java/ subdir)
if [[ -f "$PZ_DIR/java/projectzomboid.jar" ]]; then
    JAR_FILE="$PZ_DIR/java/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR/java"
else
    JAR_FILE="$PZ_DIR/projectzomboid.jar"
    DEPLOY_BASE="$PZ_DIR"
fi

SRC_ROOT="$SCRIPT_DIR/src"
BACKUP_DIR="$SCRIPT_DIR/backups/ApocalipseBrClient"
WORK_DIR="$(mktemp -d /tmp/pzpatch_apocbr_client.XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT
OUTPUT_DIR="$WORK_DIR/classes"

REQUIRED_MAJOR=25

echo ""
echo "=== $PATCH_NAME ==="
echo ""

if [[ ! -d "$SRC_ROOT" ]]; then
    echo "ERROR: Source root not found: $SRC_ROOT" >&2
    exit 1
fi

# --- Revert ---
if [[ "$REVERT" == "true" ]]; then
    echo "[*] Reverting client patch class overrides from $DEPLOY_BASE..."
    reverted=false
    while IFS= read -r -d '' src; do
        rel="${src#$SRC_ROOT/}"
        base="${rel%.java}"
        target="$DEPLOY_BASE/$base.class"
        if [[ -f "$target" ]]; then
            rm -f "$target"
            echo "    Removed: $base.class"
            reverted=true
        fi
        for inner in "$DEPLOY_BASE/$base"\$*.class; do
            if [[ -f "$inner" ]]; then
                rm -f "$inner"
                rel_inner="${inner#$DEPLOY_BASE/}"
                echo "    Removed: $rel_inner"
                reverted=true
            fi
        done
    done < <(find "$SRC_ROOT/zombie" -type f -name "*.java" -print0 | sort -z)
    if [[ "$reverted" == false ]]; then
        echo "    No client patch class overrides found."
    fi
    echo ""
    echo "=== Revert complete ==="
    exit 0
fi

if [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: projectzomboid.jar not found at $JAR_FILE" >&2
    echo "       Set --pz-dir to your Project Zomboid directory." >&2
    exit 1
fi

# Discover all patched sources
SOURCES=()
while IFS= read -r -d '' file; do
    SOURCES+=("$file")
done < <(find "$SRC_ROOT/zombie" -type f -name "*.java" -print0 | sort -z)

if [[ ${#SOURCES[@]} -eq 0 ]]; then
    echo "ERROR: No patched Java sources found under $SRC_ROOT/zombie" >&2
    exit 1
fi

# Find javac
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
echo "[*] Sources: ${#SOURCES[@]} client Java files"
echo "[*] javac:   $JAVAC ($("$JAVAC" -version 2>&1 | head -1))"
echo ""

# --- Compile ---
mkdir -p "$OUTPUT_DIR"
JAVAC_LOG="$WORK_DIR/javac.log"
echo "[*] Compiling client patched sources..."
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

# Discover all compiled classes
CLASSES=()
while IFS= read -r -d '' file; do
    rel="${file#$OUTPUT_DIR/}"
    CLASSES+=("$rel")
done < <(find "$OUTPUT_DIR" -type f -name "*.class" -print0 | sort -z)

if [[ ${#CLASSES[@]} -eq 0 ]]; then
    echo "ERROR: Compilation produced no class files." >&2
    exit 1
fi

# --- Deploy ---
echo ""
if [[ "$DRY_RUN" == "true" ]]; then
    echo "[*] DRY RUN: Would deploy ${#CLASSES[@]} client class files to $DEPLOY_BASE"
    for class_file in "${CLASSES[@]}"; do
        echo "    $class_file"
    done
    echo ""
    echo "=== Dry run complete. No files changed. ==="
else
    echo "[*] Deploying client class overrides..."
    mkdir -p "$BACKUP_DIR"
    ts=$(date +%Y%m%d_%H%M%S)
    deployed=0
    for class_file in "${CLASSES[@]}"; do
        compiled="$OUTPUT_DIR/$class_file"
        target="$DEPLOY_BASE/$class_file"
        target_dir=$(dirname "$target")
        mkdir -p "$target_dir"

        if [[ -f "$target" ]]; then
            safe_name="${class_file//\//_}"
            cp "$target" "$BACKUP_DIR/${safe_name}.prev_$ts" 2>/dev/null || true
        fi

        cp "$compiled" "$target"
        echo "    Deployed: $class_file"
        deployed=$((deployed + 1))
    done
    echo "    Deployed $deployed client class files."
fi

echo ""
echo "=== Done ==="
echo "Patch deployed: $PATCH_NAME"
echo ""
echo "To revert:"
echo "  ./patchApocalipseBr.sh --revert"
echo ""
