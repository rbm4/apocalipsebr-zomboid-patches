"""Read-only source audit; writes comparison artifacts, never patch sources.

Usage: python tools/compare_patch_versions.py 42.21.0 42.22.0 --output <directory>
Git merge-file results describe textual applicability only, not compatibility.
"""

import argparse
from collections import Counter
import difflib
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None


def lines(path):
    return path.read_text(encoding="utf-8-sig").splitlines(keepends=True)


def delta(before, after):
    edits = [op for op in difflib.SequenceMatcher(None, before, after).get_opcodes() if op[0] != "equal"]
    return {"hunks": len(edits), "removed": sum(j-i for _, i, j, _, _ in edits),
            "added": sum(j-i for _, _, _, i, j in edits)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("old")
    parser.add_argument("new")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    old, new = root / args.old, root / args.new
    for required in (old / "src/zombie", old / "decompiled/zombie", new / "decompiled/zombie"):
        if not required.is_dir():
            parser.error(f"Missing source tree: {required}")
    patches = sorted((old / "src").rglob("*.java"), key=lambda p: p.as_posix().lower())
    baseline_old = {p.relative_to(old / "decompiled").as_posix(): p for p in (old / "decompiled").rglob("*.java")}
    baseline_new = {p.relative_to(new / "decompiled").as_posix(): p for p in (new / "decompiled").rglob("*.java")}
    removed = sorted(baseline_old.keys() - baseline_new.keys())
    added = sorted(baseline_new.keys() - baseline_old.keys())
    changed = sorted(k for k in baseline_old.keys() & baseline_new.keys() if digest(baseline_old[k]) != digest(baseline_new[k]))
    results, upstream_diff, patch_diff = [], [], []
    with tempfile.TemporaryDirectory(prefix="pz-port-audit-") as tmp:
        temp = Path(tmp)
        for patched in patches:
            rel = patched.relative_to(old / "src").as_posix()
            a, b = old / "decompiled" / rel, new / "decompiled" / rel
            item = {"path": rel, "sha256": {"patched": digest(patched), "old_vanilla": digest(a), "new_vanilla": digest(b)}}
            patch_lines = lines(patched)
            # Missing old baseline is not automatically evidence of an ApocBR class.
            if not a.exists():
                item["status"] = "new_vanilla_collision" if b.exists() else "support_no_baseline"
            else:
                old_lines = lines(a)
                item["patch_delta"] = delta(old_lines, patch_lines)
                patch_diff.extend(difflib.unified_diff(old_lines, patch_lines,
                    fromfile=f"{args.old}/decompiled/{rel}", tofile=f"{args.old}/src/{rel}"))
                if not b.exists():
                    item["status"] = "missing_target"
                    upstream_diff.extend(difflib.unified_diff(old_lines, [], fromfile=f"{args.old}/decompiled/{rel}", tofile="/dev/null"))
                elif digest(a) == digest(b):
                    item["status"] = "identical_baseline"
                elif old_lines == lines(b):
                    item["status"] = "line_endings_only"
                else:
                    new_lines = lines(b)
                    item["upstream_delta"] = delta(old_lines, new_lines)
                    upstream_diff.extend(difflib.unified_diff(old_lines, new_lines,
                        fromfile=f"{args.old}/decompiled/{rel}", tofile=f"{args.new}/decompiled/{rel}"))
                    for name, content in (("ours", patch_lines), ("base", old_lines), ("theirs", new_lines)):
                        (temp / name).write_text("".join(content), encoding="utf-8", newline="\n")
                    merged = subprocess.run(["git", "merge-file", "--stdout", "--diff3", "ours", "base", "theirs"],
                                            cwd=temp, capture_output=True)
                    if merged.returncode < 0 or merged.returncode > 127:
                        raise RuntimeError(f"merge-file failed for {rel}: {merged.stderr.decode(errors='replace')}")
                    output = merged.stdout.decode("utf-8")
                    item["merge_conflicts"] = len(re.findall(r"^<<<<<<< ", output, re.MULTILINE))
                    if merged.returncode and not item["merge_conflicts"]:
                        raise RuntimeError(f"merge-file produced no conflict markers for {rel}")
                    item["status"] = "manual_rebase" if item["merge_conflicts"] else "clean_text_merge"
            # Qualified references avoid false positives such as a domain record
            # named Damage or a comment containing the English word "Trading".
            item["removed_type_references"] = sorted(p for p in removed
                if re.search(r"\b" + re.escape(p[:-5].replace("/", ".")) + r"\b", "".join(patch_lines)))
            results.append(item)
    report = {"old": args.old, "new": args.new, "counts": dict(Counter(p["status"] for p in results)),
              "baseline": {"old_total": len(baseline_old), "new_total": len(baseline_new),
                           "added": added, "removed": removed, "changed": changed}, "classes": results}
    args.output.mkdir(parents=True, exist_ok=True)
    for name, content in (("inventory.json", json.dumps(report, indent=2) + "\n"),
                          ("upstream.diff", "".join(upstream_diff)), ("patch-intent.diff", "".join(patch_diff))):
        (args.output / name).write_text(content, encoding="utf-8", newline="\n")
    print(json.dumps({"classes": len(results), "counts": report["counts"],
                      "baseline_old": len(baseline_old), "baseline_new": len(baseline_new),
                      "baseline_changed": len(changed), "baseline_added": len(added), "baseline_removed": len(removed)}, indent=2))


if __name__ == "__main__":
    main()
