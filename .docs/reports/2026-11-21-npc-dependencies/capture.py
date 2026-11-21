"""Capture or verify the report's source/JAR evidence. No game/server/database execution.

Compiler probes intentionally report missing legacy APIs. Their failure is evidence,
not a failure of capture. Existing snapshots are immutable; use --verify thereafter.
"""
import argparse
import collections
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
MASK = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
EXTRA_NATIVE = {
    "zombie/network/packets/hit/PlayerHitPacket.java", "zombie/network/packets/hit/HitCharacter.java",
    "zombie/network/packets/PlayerRequestPacket.java", "zombie/network/PacketTypes.java",
    "zombie/network/chat/ChatServer.java", "zombie/iso/IsoObject.java", "zombie/iso/IsoCell.java",
    "zombie/iso/IsoMovingObject.java", "zombie/iso/IsoGridSquare.java", "zombie/iso/IsoWorld.java",
    "zombie/characters/IsoPlayer.java", "zombie/characters/NetworkZombieAI.java",
    "zombie/network/packets/character/ZombiePacket.java", "zombie/pathfind/PolygonalMap2.java",
    "zombie/pathfind/nativeCode/PathfindNative.java", "zombie/Lua/LuaManager.java",
    "zombie/Lua/LuaEventManager.java", "zombie/savefile/ServerPlayerDB.java",
    "zombie/network/ServerWorldDatabase.java", "zombie/network/packets/actions/StatePacket.java",
}


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def read_csv(path):
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source))


def save_csv(path, fields, rows):
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fields)
        writer.writeheader()
        writer.writerows(rows)


def class_shape(data):
    """Read declared members, superclass and interfaces from original class bytes."""
    position = 8
    def u2():
        nonlocal position
        value = struct.unpack_from(">H", data, position)[0]; position += 2
        return value
    count = u2(); pool = [None] * count; index = 1
    while index < count:
        tag = data[position]; position += 1
        if tag == 1:
            size = u2(); pool[index] = data[position:position+size].decode("utf-8", errors="replace"); position += size
        elif tag == 7:
            pool[index] = u2()
        elif tag in (8,16,19,20): position += 2
        elif tag in (3,4,9,10,11,12,17,18): position += 4
        elif tag in (5,6): position += 8; index += 1
        elif tag == 15: position += 3
        else: raise ValueError("Unsupported constant-pool tag")
        index += 1
    def name(index): return pool[pool[index]].replace("/", ".") if index else None
    access, this, parent = u2(), u2(), u2()
    interfaces = [name(u2()) for _ in range(u2())]
    def members():
        nonlocal position
        result = []
        for _ in range(u2()):
            flags, member, descriptor = u2(), u2(), u2()
            for _ in range(u2()):
                position += 2
                size = struct.unpack_from(">I", data, position)[0]; position += 4 + size
            result.append({"name": pool[member], "descriptor": pool[descriptor], "access": flags})
        return result
    return {"name": name(this), "super": name(parent), "interfaces": interfaces, "access": access,
            "fields": members(), "methods": members(), "sha256": hashlib.sha256(data).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-jar", type=Path, required=True)
    parser.add_argument("--javac", type=Path, default=ROOT / "jdk/bin" / ("javac.exe" if os.name == "nt" else "javac"))
    parser.add_argument("--java", type=Path, default=ROOT / "jdk/bin" / ("java.exe" if os.name == "nt" else "java"))
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    manifest_path = HERE / "manifest.json"
    if args.verify:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for entry in manifest["sourceFiles"] + manifest["artifacts"]:
            if digest(ROOT / entry["path"]) != entry["sha256"]:
                raise RuntimeError("Snapshot drift: " + entry["path"])
        if digest(args.game_jar) != manifest["originalJarSha256"]:
            raise RuntimeError("Original JAR fingerprint drift")
        print("Source, artifact and original-JAR fingerprints verified.")
        return
    if manifest_path.exists():
        raise RuntimeError("Snapshot already exists; use --verify or create a separately dated report")
    source, vanilla = ROOT / "42.22.2/src", ROOT / "42.22.2/decompiled"
    files = {p.relative_to(source).as_posix(): p for p in source.rglob("*.java")}
    custom = {}
    for relative, path in files.items():
        if not (vanilla / relative).exists():
            if path.stem in custom: raise RuntimeError("Ambiguous custom type: " + path.stem)
            custom[path.stem] = relative
    masked = {relative: MASK.sub(lambda m: "\n" * m.group().count("\n"), path.read_text(encoding="utf-8-sig")) for relative,path in files.items()}
    references = {relative: {custom[name] for name in set(re.findall(r"\b[A-Za-z_$][\w$]*\b", text)) & custom.keys()} - {relative}
                  for relative,text in masked.items()}
    seeds = {relative for relative in files if relative.startswith(("zombie/apocalipse/humans/", "zombie/apocalipse/zombies/", "zombie/apocalipse/pathfind/"))
             or re.match(r"zombie/savefile/ApocBRNpc[^/]*\.java$", relative) or relative == "zombie/savefile/ApocBRHumanSales.java"}
    closure = set(seeds)
    while True:
        expanded = closure | set().union(*(references[relative] for relative in closure))
        if expanded == closure: break
        closure = expanded
    inbound = {relative: sorted(references[relative] & seeds) for relative in files
               if (vanilla / relative).exists() and references[relative] & seeds}
    save_json(HERE / "custom-dependencies.json", {"method": "Conservative lexical custom-type closure, not resolved runtime ownership",
        "seeds": sorted(seeds), "closure": sorted(closure), "inboundNativeOwners": inbound,
        "references": {relative: sorted(references[relative]) for relative in sorted(closure)}})
    inbound_rows = []
    for relative in sorted(inbound):
        for number,line in enumerate(masked[relative].splitlines(), 1):
            found = set(re.findall(r"\b[A-Za-z_$][\w$]*\b", line)) & custom.keys()
            for name in sorted(found):
                if custom[name] in seeds:
                    inbound_rows.append({"source": "42.22.2/src/"+relative, "line": number, "symbol": name,
                                         "target": "42.22.2/src/"+custom[name], "kind": "import" if line.strip().startswith("import ") else "reference"})
    save_csv(HERE / "native-inbound.csv", ["source","line","symbol","target","kind"], inbound_rows)
    temp = ROOT / "target/npc-dependency-audit/capture"
    for folder in (temp / "classes", temp / "driver", temp / "empty-sourcepath"): folder.mkdir(parents=True, exist_ok=True)
    inputs = temp / "inputs.txt"; inputs.write_text("\n".join(str(source / relative) for relative in sorted(closure)), encoding="utf-8")
    modified = sorted(relative for relative in files if (vanilla / relative).exists())
    native_inputs = temp / "modified-native.txt"; native_inputs.write_text("\n".join(modified), encoding="utf-8")
    classpath = os.pathsep.join(map(str,[args.game_jar.resolve(),ROOT / "42.22.2/lib/HikariCP-7.1.0.jar",ROOT / "42.22.2/lib/postgresql-42.7.14.jar"]))
    command = [str(args.javac.resolve()), "-proc:none", "-implicit:none", "-encoding", "UTF-8", "-Xmaxerrs", "1000",
               "-classpath", classpath, "-sourcepath", str(temp / "empty-sourcepath"), "-d", str(temp / "classes")]
    compile_result = subprocess.run(command + [str(source / p) for p in sorted(closure)], capture_output=True, text=True, encoding="utf-8", errors="replace")
    (HERE / "javac-diagnostics.txt").write_text(compile_result.stdout + compile_result.stderr, encoding="utf-8")
    subprocess.run([str(args.javac.resolve()), "-encoding", "UTF-8", "-d", str(temp / "driver"),
                    str(HERE / "NativeDependencyScan.java"), str(HERE / "NativeDeltaScan.java")], check=True)
    subprocess.run([str(args.java.resolve()), "-cp", str(temp / "driver"), "NativeDependencyScan", str(ROOT), str(inputs), str(HERE), classpath], check=True)
    subprocess.run([str(args.java.resolve()), "-cp", str(temp / "driver"), "NativeDeltaScan", str(ROOT), str(native_inputs), str(HERE / "native-member-deltas.csv")], check=True)
    api = read_csv(HERE / "native-api.csv")
    core = [row for row in api if row["source"] in {"42.22.2/src/"+p for p in seeds}]
    native_candidates = set(inbound) | EXTRA_NATIVE
    native_candidates |= {row["vanillaSource"].removeprefix("42.22.2/decompiled/") for row in core
                          if (source / row["vanillaSource"].removeprefix("42.22.2/decompiled/")).exists()}
    with zipfile.ZipFile(args.game_jar) as jar:
        shapes = {}
        for relative in sorted(native_candidates):
            name = relative.removesuffix(".java")
            if name+".class" in jar.namelist(): shapes[name.replace("/", ".")] = class_shape(jar.read(name+".class"))
        signing = [n for n in jar.namelist() if n.startswith("META-INF/") and n.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))]
    save_json(HERE / "original-jar-boundaries.json", {"classes": shapes, "signingEntries": signing})
    native_paths = sorted({row["vanillaSource"] for row in api} | {"42.22.2/decompiled/"+p for p in native_candidates if (vanilla / p).exists()})
    source_paths = {"42.22.2/src/"+p for p in closure} | set(native_paths)
    source_paths |= {p.replace("/decompiled/", "/src/") for p in native_paths if (ROOT / p.replace("/decompiled/", "/src/")).exists()}
    source_paths |= {"src/main/java/cloud/apocalipse/pz/agent/AgentRuntime.java", "src/main/java/cloud/apocalipse/pz/agent/ApocBrAgent.java",
                     "src/main/java/cloud/apocalipse/pz/agent/features/postgres/PostgresModule.java", "pom.xml", "AGENTS.md"}
    mutation_sites = []
    for path in vanilla.rglob("*.java"):
        for number,line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            if "getObjectList()" in line or (path.name == "IsoCell.java" and re.search(r"\bobjectList\.(add|remove|clear|iterator|retainAll)", line)):
                mutation_sites.append({"source": path.relative_to(ROOT).as_posix(), "line": number, "text": line.strip()})
                source_paths.add(path.relative_to(ROOT).as_posix())
    save_csv(HERE / "vanilla-object-list-sites.csv", ["source","line","text"], mutation_sites)
    lines = ["# Native API census", "", "Compiler-attributed references from the 191 bot seed sources, using the original JAR. Imports/type uses are separate from executable member use. See native-api.csv for every reference and shared-closure rows. Compilation still has the recorded errors; this is not a runtime call graph.", ""]
    for owner in sorted({row["owner"] for row in core}):
        selected = [row for row in core if row["owner"] == owner]
        lines += ["## `"+owner+"`", "", "Vanilla source: ["+selected[0]["vanillaSource"]+"](../../../"+selected[0]["vanillaSource"]+").", ""]
        members = sorted({(row["kind"],row["member"],row["access"]) for row in selected if row["kind"] != "TYPE"})
        lines += ["| Kind | Member | Access |", "| --- | --- | --- |"]
        lines += ["| "+kind+" | `"+member.replace("|", "\\|")+"` | `"+access+"` |" for kind,member,access in members]
        if not members: lines += ["| TYPE | Type-only reference | See CSV |"]
        lines += [""]
    (HERE / "native-api-catalog.md").write_text("\n".join(lines), encoding="utf-8")
    summary = {"seedSources":len(seeds), "customClosureSources":len(closure), "directNativeInboundOwners":len(inbound),
               "nativeApiRows":len(api), "seedNativeApiRows":len(core), "seedNativeOwnerTypes":len({r["owner"] for r in core}),
               "seedNativeSourceFiles":len({r["vanillaSource"] for r in core}),
               "distinctSeedNativeMembers":len({(r["kind"],r["owner"],r["member"]) for r in core if r["kind"] != "TYPE"}),
               "originalJarBoundaryClasses":len(shapes), "javacExitCode":compile_result.returncode,
               "compilerErrors":len(re.findall(r": ERROR:", (HERE / "compiler-diagnostics.txt").read_text(encoding="utf-8")))}
    artifacts = sorted(p for p in HERE.iterdir() if p.is_file() and p.name != "manifest.json" and p.name != "README.md")
    save_json(manifest_path, {"date":"2026-11-21", "scope":"NPC dependency report only; no feature port or runtime execution",
        "summary":summary, "gitHead":subprocess.check_output(["git","rev-parse","HEAD"],cwd=ROOT,text=True).strip(),
        "originalJarSha256":digest(args.game_jar), "compilerVersion":subprocess.check_output([str(args.javac.resolve()),"-version"],text=True).strip(),
        "sourceFiles":[{"path":p,"sha256":digest(ROOT / p)} for p in sorted(source_paths)],
        "artifacts":[{"path":p.relative_to(ROOT).as_posix(),"sha256":digest(p)} for p in artifacts]})
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
