"""Capture/verify a source-only migration baseline. Never edits game or patch sources."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import quote

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
VERSION = ROOT / "42.22.2"
SOURCE = VERSION / "src"
VANILLA = VERSION / "decompiled"
DATE = "2026-11-18"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def normalized(data):
    # Deliberately retain whitespace, BOMs and missing final newlines.
    return data.replace(b"\r\n", b"\n")


def run(*args, cwd=ROOT):
    result = subprocess.run(args, cwd=cwd, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]}")
    return result.stdout.decode("utf-8").strip()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


def tags(path):
    result = []
    if path.startswith(("zombie/apocalipse/humans/", "zombie/apocalipse/zombies/")) or "ApocBRNpc" in path:
        result.append("npc")
    if path.startswith("zombie/savefile/") or any(x in path for x in ("Postgres", "Persistence", "WorldDictionary", "VehiclesDB")):
        result.append("persistence")
    if "Telemetry" in path:
        result.append("telemetry")
    if path.startswith("zombie/apocalipse/events/"):
        result.append("events")
    if path.startswith("zombie/apocbr/api/"):
        result.append("api")
    return result or ["engine-or-other"]


def masked_java(text):
    # Lexical approximation, not a Java parser. Preserve line positions.
    pattern = r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
    return re.sub(pattern, lambda m: re.sub(r"[^\n]", " ", m.group()), text)


def capture():
    manifest_file = HERE / "manifest.json"
    if manifest_file.exists():
        raise RuntimeError("Snapshot already exists. Use --verify; create a new dated report for a fresh snapshot.")
    records, content = [], {}
    sources = sorted((SOURCE / "zombie").rglob("*.java"))
    with tempfile.TemporaryDirectory(prefix="apocbr-migration-diff-") as temp:
        temp = Path(temp)
        (temp / "vanilla").mkdir()
        (temp / "patched").mkdir()
        for source in sources:
            relative = source.relative_to(SOURCE).as_posix()
            raw = source.read_bytes()
            vanilla = VANILLA / relative
            original = vanilla.read_bytes() if vanilla.exists() else None
            content[relative] = normalized(raw).decode("utf-8")
            for tree, data in (("patched", raw), ("vanilla", original)):
                if data is not None:
                    target = temp / tree / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(normalized(data))
            kind = "added" if original is None else "unchanged" if normalized(original) == normalized(raw) else "modified"
            records.append({
                "path": relative, "kind": kind,
                "patched_source": source.relative_to(ROOT).as_posix(),
                "vanilla_source": vanilla.relative_to(ROOT).as_posix() if original is not None else None,
                "patched_sha256": digest(raw),
                "vanilla_sha256": digest(original) if original is not None else None,
                "patched_lf_sha256": digest(normalized(raw)),
                "vanilla_lf_sha256": digest(normalized(original)) if original is not None else None,
                "patched_lines": len(raw.splitlines()),
                "vanilla_lines": len(original.splitlines()) if original is not None else 0,
                "diff": "diffs/" + relative + ".diff", "path_tags": tags(relative),
            })
        result = subprocess.run([
            "git", "-c", "core.autocrlf=false", "diff", "--no-index", "--no-ext-diff",
            "--no-textconv", "--no-color", "--no-prefix", "--no-renames", "--text", "--unified=5",
            "vanilla", "patched",
        ], cwd=temp, capture_output=True)
        if result.returncode not in (0, 1):
            raise RuntimeError("Git source comparison failed")
        sections = {}
        for section in re.split(rb"(?m)(?=^diff --git )", result.stdout):
            if not section:
                continue
            lines = section.splitlines(keepends=True)
            # Every compared path is repository-controlled and has no spaces.
            match = re.match(rb"diff --git \S+ patched/(\S+)\n", lines[0])
            if not match:
                raise RuntimeError("Unexpected Git diff header")
            relative = match[1].decode("utf-8")
            rewritten = [f"diff --git a/{relative} b/{relative}\n".encode()]
            for line in lines[1:]:
                if line.startswith(b"--- "):
                    line = b"--- /dev/null\n" if line.startswith(b"--- /dev/null") else f"--- a/{relative}\n".encode()
                elif line.startswith(b"+++ "):
                    line = f"+++ b/{relative}\n".encode()
                rewritten.append(line)
            sections[relative] = b"".join(rewritten)
        for record in records:
            patch = sections.pop(record["path"], b"")
            if bool(patch) != (record["kind"] != "unchanged"):
                raise RuntimeError("Diff coverage mismatch")
            output = HERE / record["diff"]
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(patch)
            record.update({
                "diff_sha256": digest(patch),
                "hunks": len(re.findall(rb"(?m)^@@ ", patch)),
                "added_lines": sum(line.startswith(b"+") and not line.startswith(b"+++") for line in patch.splitlines()),
                "removed_lines": sum(line.startswith(b"-") and not line.startswith(b"---") for line in patch.splitlines()),
            })
        if sections:
            raise RuntimeError("Uninventoried diff sections")

    custom = defaultdict(list)
    for record in records:
        if record["kind"] == "added":
            custom[Path(record["path"]).stem].append(record["path"])
    matcher = re.compile(r"\b(?:" + "|".join(re.escape(name) for name in sorted(custom, key=len, reverse=True)) + r")\b")
    references = []
    imports = []
    for record in records:
        text = masked_java(content[record["path"]])
        for line_number, line in enumerate(text.splitlines(), 1):
            symbols = sorted(set(matcher.findall(line)))
            if symbols:
                references.append({"source": record["path"], "line": line_number,
                    "kind": "import" if line.lstrip().startswith("import ") else "identifier-reference",
                    "symbols": symbols, "candidate_sources": sorted({p for symbol in symbols for p in custom[symbol]})})
        imports.extend({"source": record["path"], "import": match.group(1)}
            for match in re.finditer(r"(?m)^\s*import\s+(?:static\s+)?([\w.*]+)\s*;", text))
    write_json(HERE / "custom-class-references.json", {
        "method": "Comment/string-masked simple-identifier matches to added source filenames. Includes imports/declarations; ambiguous names retain all candidates. Not a resolved call graph or proof of complete dependencies.",
        "references": references, "imports": imports,
    })
    context_paths = [ROOT / "pom.xml", VERSION / "patchApocalipseBr.ps1", VERSION / "patchApocalipseBr.sh",
        VERSION / "tools/retired-class-families.json"]
    context_paths += sorted((ROOT / ".vscode").glob("*.json"))
    context_paths += sorted((ROOT / "src").rglob("*.java"))
    context_paths += sorted((VERSION / "lib").glob("*.jar"))
    counts = dict(Counter(record["kind"] for record in records))
    manifest = {
        "schema_version": 1, "date": DATE, "target": "42.22.2 dedicated-server source tree",
        "git_head": run("git", "rev-parse", "HEAD"), "git_branch": run("git", "branch", "--show-current"),
        "preexisting_scope_status": run("git", "status", "--short", "--", "pom.xml", ".vscode", "src", "42.22.2/src", "42.22.2/decompiled"),
        "direction": "vanilla -> patched; additions use /dev/null; no vanilla-only file is treated as deleted",
        "comparison": "Git --no-index --no-renames --text --unified=5 over UTF-8 sources after CRLF-to-LF normalization. No whitespace or semantic filtering.",
        "counts": counts, "source_count": len(records),
        "vanilla_java_count": len(list((VANILLA / "zombie").rglob("*.java"))),
        "added_lines": sum(r["added_lines"] for r in records),
        "removed_lines": sum(r["removed_lines"] for r in records),
        "hunks": sum(r["hunks"] for r in records),
        "context_files": [{"path": p.relative_to(ROOT).as_posix(), "sha256": digest(p.read_bytes())} for p in context_paths if p.exists()],
        "records": records,
    }
    write_json(manifest_file, manifest)
    rows = ["# Class inventory: Byte Buddy migration baseline", "", f"Captured {DATE}. See the [owning report](2026-11-18-byte-buddy-migration-baseline.md) for scope, limitations and continuation steps.", "",
        "One row and one diff asset per patched Java source. Added classes have no vanilla counterpart; unchanged files have empty diff assets. Line counts describe textual deltas, not migration difficulty.", "",
        "| Source | Kind | + / - | Hunks | Diff |", "| --- | --- | ---: | ---: | --- |"]
    for record in records:
        source_link = "../../../../" + record["patched_source"]
        rows.append(f"| [{record['path']}]({quote(source_link)}) | {record['kind']} | {record['added_lines']} / {record['removed_lines']} | {record['hunks']} | [diff]({quote(record['diff'])}) |")
    (HERE / "2026-11-18-class-inventory.md").write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({key: manifest[key] for key in ("source_count", "counts", "added_lines", "removed_lines", "hunks")}, indent=2))


def verify():
    manifest = json.loads((HERE / "manifest.json").read_text(encoding="utf-8"))
    records = manifest["records"]
    current = {p.relative_to(SOURCE).as_posix() for p in (SOURCE / "zombie").rglob("*.java")}
    if current != {r["path"] for r in records}:
        raise RuntimeError("Patched source inventory drift")
    if {p.relative_to(HERE).as_posix() for p in (HERE / "diffs").rglob("*.diff")} != {r["diff"] for r in records}:
        raise RuntimeError("Diff asset coverage mismatch")
    with tempfile.TemporaryDirectory(prefix="apocbr-migration-verify-") as temp:
        temp = Path(temp)
        combined = bytearray()
        for record in records:
            raw = (ROOT / record["patched_source"]).read_bytes()
            if digest(raw) != record["patched_sha256"]:
                raise RuntimeError("Patched source drift: " + record["path"])
            if record["vanilla_source"]:
                original = (ROOT / record["vanilla_source"]).read_bytes()
                if digest(original) != record["vanilla_sha256"]:
                    raise RuntimeError("Vanilla source drift: " + record["path"])
                target = temp / record["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(normalized(original))
            elif (VANILLA / record["path"]).exists():
                raise RuntimeError("Added source now has a vanilla counterpart")
            patch = (HERE / record["diff"]).read_bytes()
            if digest(patch) != record["diff_sha256"]:
                raise RuntimeError("Diff asset drift: " + record["path"])
            combined.extend(patch)
        patch_file = temp / "all.diff"
        patch_file.write_bytes(combined)
        # Independently apply Git's artifacts to a disposable baseline, never to the workspace.
        run("git", "-c", "core.autocrlf=false", "apply", "--check", "all.diff", cwd=temp)
        run("git", "-c", "core.autocrlf=false", "apply", "all.diff", cwd=temp)
        for record in records:
            if digest((temp / record["path"]).read_bytes()) != record["patched_lf_sha256"]:
                raise RuntimeError("Diff reconstruction mismatch: " + record["path"])
    for context in manifest["context_files"]:
        if digest((ROOT / context["path"]).read_bytes()) != context["sha256"]:
            raise RuntimeError("Context file drift: " + context["path"])
    print(f"Verified {len(records)} source hashes, diff coverage and Git-apply reconstructions; context hashes match.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true")
    options = parser.parse_args()
    if not options.verify:
        capture()
    verify()
