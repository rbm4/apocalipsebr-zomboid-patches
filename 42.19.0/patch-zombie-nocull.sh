#!/usr/bin/env bash
# patch-zombie-nocull.sh
# Removes the server-side zombie cull added in Build 42.19 by NOPing the
# ZombieCountOptimiser.deleteZombies() call in MovingObjectUpdateScheduler.postupdate().
#
# Change in 42.19: postupdate() started calling ZombieCountOptimiser.deleteZombies()
# on every frame on the dedicated server, reducing zombie populations from ~5000
# down to ~400 on servers with many connected players. In 42.18 this cull did not
# exist server-side - the code was client-only.
#
# The patch replaces the 9 bytes of the cull sequence with NOPs:
#   B2 00 2F   getstatic  GameServer.server
#   99 00 06   ifeq 9
#   B8 00 D7   invokestatic ZombieCountOptimiser.deleteZombies()
# ->
#   00 00 00 00 00 00 00 00 00   (9 x nop)
#
# Binary in-place patch (same class size). Idempotent.
# Requires: python3 (available on all modern Linux distros).
#
# Usage:
#   ./patch-zombie-nocull.sh [JAR_PATH] [--dry-run] [--revert]
#   ./patch-zombie-nocull.sh                          # uses java/projectzomboid.jar in CWD
#   ./patch-zombie-nocull.sh /opt/pzserver/java/projectzomboid.jar
#   ./patch-zombie-nocull.sh --revert
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_NAME="Zombie NoCull - MovingObjectUpdateScheduler.postupdate()"
JAR_ENTRY="zombie/MovingObjectUpdateScheduler.class"
BACKUP_DIR="$SCRIPT_DIR/backups/MovingObjectUpdateScheduler"

# --- Argument parsing ---
JAR_PATH="java/projectzomboid.jar"
DRY_RUN=false
REVERT=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)       DRY_RUN=true;  shift ;;
        --revert|-r)     REVERT=true;   shift ;;
        --jar)           JAR_PATH="$2"; shift 2 ;;
        -*)
            echo "Unknown option: $1" >&2
            echo "Usage: $0 [JAR_PATH] [--dry-run] [--revert]" >&2
            exit 1
            ;;
        *)
            JAR_PATH="$1"; shift ;;
    esac
done

echo ""
echo "=== $PATCH_NAME ==="
echo ""

# --- Revert ---
if [[ "$REVERT" == "true" ]]; then
    latest=$(ls -t "$BACKUP_DIR"/projectzomboid.jar.bak.* 2>/dev/null | head -1 || true)
    if [[ -z "$latest" ]]; then
        echo "[!] No backup found in: $BACKUP_DIR"
        exit 0
    fi
    echo "[*] Restoring from: $latest"
    if [[ "$DRY_RUN" == "false" ]]; then
        cp "$latest" "$JAR_PATH"
        echo "    Restored: $JAR_PATH"
    else
        echo "    [DryRun] Would restore: $JAR_PATH"
    fi
    echo ""
    echo "=== Patch reverted ==="
    exit 0
fi

# --- Validate ---
if [[ ! -f "$JAR_PATH" ]]; then
    echo "ERROR: Jar not found: $JAR_PATH" >&2
    echo "       Run from the PZ server root (where the java/ folder exists), or pass the jar path." >&2
    exit 1
fi
echo "[*] Target: $JAR_PATH"

# --- Backup ---
STAMP=$(date +%Y%m%d-%H%M%S)
BACKUP="$BACKUP_DIR/projectzomboid.jar.bak.$STAMP"
if [[ "$DRY_RUN" == "false" ]]; then
    mkdir -p "$BACKUP_DIR"
    cp "$JAR_PATH" "$BACKUP"
    echo "[*] Backup: $BACKUP"
else
    echo "[*] DryRun - jar will not be modified."
fi

# --- Patch ---
echo "[1/1] Patching: $JAR_ENTRY ..."

JAR_PATH="$JAR_PATH" \
JAR_ENTRY="$JAR_ENTRY" \
DRY_RUN="$DRY_RUN" \
python3 << 'PYEOF'
import os, sys, zipfile, tempfile, struct

jar_path  = os.environ["JAR_PATH"]
jar_entry = os.environ["JAR_ENTRY"]
dry_run   = os.environ["DRY_RUN"] == "true"

SEARCH  = bytes.fromhex("B2002F990006B800D7")
NOP_9   = bytes(9)

# Read class bytes from JAR
with zipfile.ZipFile(jar_path, 'r') as zf:
    try:
        class_bytes = zf.read(jar_entry)
    except KeyError:
        print(f"ERROR: Entry not found in jar: {jar_entry}", file=sys.stderr)
        sys.exit(1)

# Check for pattern
idx = class_bytes.find(SEARCH)
if idx < 0:
    print("    [!] Pattern not found - build may differ from 42.19 or patch is already applied.")
    sys.exit(0)

print(f"    Pattern found at offset {idx}")

if dry_run:
    print("    [DryRun] Skipping write.")
    sys.exit(0)

# Apply patch
patched = bytearray(class_bytes)
patched[idx:idx + 9] = NOP_9

# Write back - rebuild the entire zip preserving all other entries
tmp_path = jar_path + ".tmp_nocull"
try:
    with zipfile.ZipFile(jar_path, 'r') as zin:
        with zipfile.ZipFile(tmp_path, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                if item.filename == jar_entry:
                    zout.writestr(item, bytes(patched))
                else:
                    zout.writestr(item, zin.read(item.filename))
    os.replace(tmp_path, jar_path)
    print(f"    Patch applied at offset {idx}: deleteZombies() -> 9x nop.")
    print(f"    Size: {len(class_bytes)} bytes (unchanged)")
except Exception as e:
    if os.path.exists(tmp_path):
        os.remove(tmp_path)
    print(f"ERROR: {e}", file=sys.stderr)
    sys.exit(1)
PYEOF

echo ""
if [[ "$DRY_RUN" == "false" ]]; then
    echo "[+] Done. Restart the server to apply."
    echo "    Backup: $BACKUP"
else
    echo "[+] DryRun complete."
fi
echo ""
