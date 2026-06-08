#!/usr/bin/env bash
# patch-nullcraft.sh
# Patches CompressIdenticalItems.class to guard against a null item during
# DryingCraftLogic saves, preventing save corruption on the dedicated server.
#
# Bug: when a drying/curing craft (DryingCraftLogic) is in progress and the
# referenced item becomes null (e.g. item despawned, player disconnected),
# the server throws NPE in CompressIdenticalItems.save() during chunk
# serialization. This corrupts the chunk save, causing vehicles to vanish
# from vehicles.db on the next server boot.
#
# The patch inserts a null guard at the top of
# CompressIdenticalItems.save(ByteBuffer, InventoryItem):
#   aload_1; ifnull <return>  -> if (item == null) return;
#
# Binary in-place patch (class size increases by 13 bytes). Idempotent.
# Requires: python3 (available on all modern Linux distros).
#
# Original error in DebugLog-server.txt:
#   NullPointerException: Cannot invoke "InventoryItem.saveWithSize" because
#   "item" is null at CompressIdenticalItems.save(CompressIdenticalItems.java:343)
#
# Usage:
#   ./patch-nullcraft.sh [JAR_PATH] [--dry-run] [--revert]
#   ./patch-nullcraft.sh                          # uses java/projectzomboid.jar in CWD
#   ./patch-nullcraft.sh /opt/pzserver/java/projectzomboid.jar
#   ./patch-nullcraft.sh --revert
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_NAME="NullCraft Fix - CompressIdenticalItems.save() Null Guard"
JAR_ENTRY="zombie/inventory/CompressIdenticalItems.class"
BACKUP_DIR="$SCRIPT_DIR/backups/CompressIdenticalItems"

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
import os, sys, zipfile, struct

jar_path  = os.environ["JAR_PATH"]
jar_entry = os.environ["JAR_ENTRY"]
dry_run   = os.environ["DRY_RUN"] == "true"

# Original search pattern (31 bytes) - Code attribute of save(ByteBuffer, InventoryItem):
#   00 00 00 53  attr_length = 83
#   00 03 00 02  max_stack=3 max_locals=2
#   00 00 00 13  code_length = 19
#   2A 04 B6 00 AB 57  aload_0; iconst_1; invokevirtual putShort; pop
#   2A 04 B6 00 36 57  aload_0; iconst_1; invokevirtual putInt; pop
#   2B 2A 03 B6 00 BC  aload_1; aload_0; iconst_0; invokevirtual saveWithSize
#   B1                 return
SEARCH = bytes.fromhex(
    "0000005300030002000000132A04B600AB572A04B60036572B2A03B600BCB1"
)

# Already-patched signature: first 16 bytes of patched Code attribute header + null check opcode
PATCHED_CHECK = bytes.fromhex("0000006000030002000000172BC60015")

# Replacement parts:
# New Code attribute header (12 bytes): attr_length=96, max_stack=3, max_locals=2, code_length=23
NEW_HEADER = bytes.fromhex("000000600003000200000017")

# New bytecode (23 bytes): null check + original code
#   2B           aload_1 (item)
#   C6 00 15     ifnull -> offset 22 (return)
#   2A 04 B6 00 AB  aload_0; iconst_1; invokevirtual putShort
#   57           pop
#   2A 04 B6 00 36  aload_0; iconst_1; invokevirtual putInt
#   57           pop
#   2B 2A 03     aload_1; aload_0; iconst_0
#   B6 00 BC     invokevirtual saveWithSize
#   B1           return
NEW_CODE = bytes.fromhex("2BC600152A04B600AB572A04B60036572B2A03B600BCB1")

# StackMapTable sub-attr (9 bytes):
# name_idx=0x00E3(227), attr_len=3, count=1, same_frame(offset_delta=22)
SMT_ATTR = bytes.fromhex("00E30000000300" + "0116")

# Read class bytes from JAR
with zipfile.ZipFile(jar_path, 'r') as zf:
    try:
        class_bytes = zf.read(jar_entry)
    except KeyError:
        print(f"ERROR: Entry not found in jar: {jar_entry}", file=sys.stderr)
        sys.exit(1)

# Check already patched
if PATCHED_CHECK in class_bytes:
    print("    [!] Patch already applied (patched pattern detected).")
    sys.exit(0)

# Find original pattern
idx = class_bytes.find(SEARCH)
if idx < 0:
    print("    [!] Pattern not found - class may have changed in this PZ version.")
    sys.exit(1)

print(f"    Pattern found at offset {idx}")

if dry_run:
    print("    [DryRun] Skipping write.")
    sys.exit(0)

# Parse Code attribute trailing data (exception_table + sub_attrs)
# Layout after the 31-byte search block:
#   exc_table_len (2 bytes) = 0
#   sub_attrs_count (2 bytes) = 2
#   sub_attr[0]: name_idx(2) + attr_len(4) + data  -> LineNumberTable
#   sub_attr[1]: name_idx(2) + attr_len(4) + data  -> LocalVariableTable
orig_end          = idx + len(SEARCH)
sub_attrs_cnt_off = orig_end + 2    # skip exc_table_len(2)

orig_sub_attrs_count = struct.unpack_from(">H", class_bytes, sub_attrs_cnt_off)[0]
print(f"    Original sub_attrs_count: {orig_sub_attrs_count}")

# Walk and collect existing sub_attrs bytes (LineNumberTable + LocalVariableTable)
s_off = sub_attrs_cnt_off + 2
preserved = bytearray()
for _ in range(orig_sub_attrs_count):
    sa_len   = struct.unpack_from(">I", class_bytes, s_off + 2)[0]
    sa_total = 6 + sa_len
    preserved.extend(class_bytes[s_off : s_off + sa_total])
    s_off += sa_total

# Build replacement block:
# [NEW_HEADER(12)] [NEW_CODE(23)] [exc_table_len=0(2)] [sub_attrs_count=3(2)] [SMT_ATTR(9)] [preserved]
replacement = (
    NEW_HEADER
    + NEW_CODE
    + b'\x00\x00'       # exc_table_len = 0
    + b'\x00\x03'       # sub_attrs_count = 3
    + SMT_ATTR
    + bytes(preserved)
)

# Reassemble class file
patched    = class_bytes[:idx] + replacement + class_bytes[s_off:]
size_delta = len(patched) - len(class_bytes)

# Write back - rebuild the entire zip preserving all other entries
tmp_path = jar_path + ".tmp_nullcraft"
try:
    with zipfile.ZipFile(jar_path, 'r') as zin:
        with zipfile.ZipFile(tmp_path, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                if item.filename == jar_entry:
                    zout.writestr(item, patched)
                else:
                    zout.writestr(item, zin.read(item.filename))
    os.replace(tmp_path, jar_path)
    print(f"    Patch applied at offset {idx}: code 19 -> 23 bytes, class +{size_delta} bytes.")
    print(f"    Size: {len(class_bytes)} bytes -> {len(patched)} bytes (+{size_delta})")
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
