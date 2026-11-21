"""Compile legacy custom-only sources against an original JAR, without patch deployment.

The default scope is a conservative lexical closure of custom savefile sources.
--all-custom expands the diagnostic audit to every custom-only source. Neither
mode migrates classes or proves runtime completeness. Output stays under target/.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-jar", type=Path, required=True)
    parser.add_argument("--javac", type=Path, required=True)
    parser.add_argument("--all-custom", action="store_true")
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    legacy, vanilla = root / "42.22.2/src", root / "42.22.2/decompiled"
    custom = {}
    for source in legacy.rglob("*.java"):
        if not (vanilla / source.relative_to(legacy)).exists():
            if source.stem in custom:
                raise RuntimeError("Ambiguous custom type: " + source.stem)
            custom[source.stem] = source
    seeds = {name for name, source in custom.items() if source.parent.name == "savefile"}
    # Navigation aid only: this is not Java type resolution or a hook inventory.
    mask = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
    references = {
        name: set(re.findall(r"\b[A-Za-z_$][\w$]*\b", mask.sub(" ", source.read_text(encoding="utf-8-sig")))) & custom.keys()
        for name, source in custom.items()
    }
    selected = set(custom) if options.all_custom else set(seeds)
    while True:
        expanded = selected | set().union(*(references[name] for name in selected))
        if expanded == selected:
            break
        selected = expanded
    mode = "all-custom" if options.all_custom else "savefile-closure"
    output = root / "target/postgres-migration-audit" / mode
    for folder in (output / "classes", output / "empty-sourcepath"):
        folder.mkdir(parents=True, exist_ok=True)
    classpath = [options.game_jar.resolve(), root / "42.22.2/lib/HikariCP-7.1.0.jar", root / "42.22.2/lib/postgresql-42.7.14.jar"]
    import os
    arguments = [str(options.javac.resolve()), "-encoding", "UTF-8", "-proc:none", "-implicit:none",
                 "-Xmaxerrs", "1000", "-classpath", os.pathsep.join(map(str, classpath)),
                 "-sourcepath", str(output / "empty-sourcepath"), "-d", str(output / "classes")]
    arguments.extend(str(custom[name]) for name in sorted(selected))
    result = subprocess.run(arguments, capture_output=True, text=True, encoding="utf-8", errors="replace")
    diagnostics = result.stdout + result.stderr
    (output / "diagnostics.txt").write_text(diagnostics, encoding="utf-8")
    with options.game_jar.open("rb") as jar:
        jar_hash = hashlib.file_digest(jar, "sha256").hexdigest()
    manifest = {
        "scope": mode,
        "limitations": "Lexical custom-source closure; not compiler-resolved or a complete hook inventory",
        "seedCount": len(seeds), "customSourceCount": len(selected),
        "originalJarSha256": jar_hash, "compileExitCode": result.returncode,
        "compilerErrorCount": len(re.findall(r": error:", diagnostics)),
        "sources": [{"path": custom[name].relative_to(root).as_posix(),
                     "sha256": hashlib.sha256(custom[name].read_bytes()).hexdigest()} for name in sorted(selected)],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in manifest.items() if key != "sources"}, indent=2))
    print("Diagnostics:", output / "diagnostics.txt")
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
