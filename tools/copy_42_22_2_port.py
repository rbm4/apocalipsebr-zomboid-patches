"""Copy audited 42.22.1 working-tree patch inputs to 42.22.2; never deploy.

Run from any directory: py -3 tools/copy_42_22_2_port.py
Existing differing destinations are refused. Run before applying target edits.
Vanilla, documentation, old artifacts, backups and Python caches are excluded.
"""

import hashlib
import json
from pathlib import Path
import shutil


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    old, new = root / "42.22.1", root / "42.22.2"
    assert (new / "decompiled/zombie/network/GameServer.java").is_file()
    files = [old / name for name in ("patchApocalipseBr.ps1", "patchApocalipseBr.sh")]
    for name in ("src", "lib", "tools"):
        files.extend(p for p in (old / name).rglob("*")
                     if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc")
    files.sort(key=lambda p: p.as_posix())
    # Preflight the whole copy before writing any destination.
    for source in files:
        target = new / source.relative_to(old)
        if target.exists() and sha(target) != sha(source):
            raise RuntimeError(f"Refusing to overwrite differing destination: {target}")
    vanilla = {p: sha(p) for p in (new / "decompiled").rglob("*") if p.is_file()}
    records = []
    for source in files:
        relative = source.relative_to(old)
        target = new / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        source_hash = sha(source)
        shutil.copy2(source, target)
        if sha(target) != source_hash or sha(source) != source_hash:
            raise RuntimeError(f"Copy verification failed: {relative}")
        records.append({"path": relative.as_posix(), "source_sha256": source_hash,
                        "copied_sha256": sha(target)})
    assert all(sha(p) == digest for p, digest in vanilla.items()), "Vanilla changed"
    receipt = new / "docs/reports/2026-11-08-port-copy-receipt.json"
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({
        "source": "42.22.1 current working tree", "target": "42.22.2",
        "stage": "Byte-identical copy before target GameServer/build-label edits",
        "excluded": ["decompiled", "docs", "backups", "artifacts", "__pycache__", "*.pyc"],
        "vanilla_files_verified_unchanged": len(vanilla), "files": records,
    }, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {len(records)} copied files; {len(vanilla)} target vanilla files unchanged.")


if __name__ == "__main__":
    main()
