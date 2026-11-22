"""Build an isolated, opt-in 42.21.0 client capture overlay from verified vanilla sources.

Does not install classes or alter a game JAR. Generated whole-class sources live
only in a temporary build directory; the server behavior patches are not shipped.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.21.0"
CALL = "if (GameClient.client && ClientZombieSampler.enabled()) ClientZombieSampler."
BASELINES = {
    "zombie/characters/IsoGameCharacter": "29040e19c3e420499f73bc0ea0d5b86d7f7d38655acdc13d0107df736eb54c0b",
    "zombie/characters/IsoZombie": "9dd82f9e5022c27d64bb3c64ebc4a061f37393e1fc865f2919a18e1d07673bb2",
    "zombie/characters/NetworkZombieAI": "87e4f6e43d16dec23f685b4ebad68eb12eaea15ed87dce3fc3c3394f96cd1e3d",
    "zombie/network/packets/character/ZombiePacket": "b7f75b36c8ec41a72537d03fe8aa6d681c432b3454d12e5d092af5f1faa31756",
    "zombie/popman/NetworkZombieSimulator": "962b26ff39c12bbee442c6e6edf10245cf7eff8dbfdae06756f06fae2364da31",
    "zombie/network/packets/character/ZombieSynchronizationPacket": "c6d61c3ea29ae775e638c68e11c9fe98fac28106f3d892c02c4e48f93c4aa186",
}
NATIVE = list(BASELINES)


def replace_once(source, before, after):
    if source.count(before) != 1:
        raise ValueError(f"Hook anchor must match once: {before[:100]!r}")
    return source.replace(before, after, 1)


def instrument(name, source):
    """Only observational calls are added; every native operation retains its order."""
    package_end = source.index(";", source.index("package ")) + 1
    imports = "\nimport zombie.apocalipse.diagnostics.ClientZombieSampler;"
    if "import zombie.network.GameClient;" not in source:
        imports += "\nimport zombie.network.GameClient;"
    source = source[:package_end] + imports + source[package_end:]
    if name.endswith("/IsoGameCharacter"):
        before = "            this.animPlayer.getDeferredMovement(result, reset);"
        source = replace_once(source, before, before+"\n            "+CALL+"deferred(this, result.x, result.y, reset);")
        before = "            this.animEvent(this, sender, track, event);"
        source = replace_once(source, before, "            "+CALL+"animation(this, event);\n"+before)
    elif name.endswith("/IsoZombie"):
        before = "    public void moveUnmodded(float dirX, float dirY) {"
        source = replace_once(source, before, before+"\n        "+CALL+'movement(this, "move_requested", dirX, dirY, 1);')
        before = "            dirY *= movingScale;"
        source = replace_once(source, before, before+"\n            "+CALL+'movement(this, "remote_correction", dirX, dirY, movingScale);')
        before = "        super.moveUnmodded(dirX, dirY);"
        source = replace_once(source, before, "        "+CALL+'movement(this, "move_applied", dirX, dirY, 1);\n'+before)
        before = "            this.postUpdateInternal();"
        source = replace_once(source, before, before+"\n            "+CALL+"post(this);")
    elif name.endswith("/NetworkZombieAI"):
        before = "    public void parse(ZombiePacket packet) {"
        source = replace_once(source, before, before+"\n        "+CALL+'received(this.zombie, packet, "receive_before");')
        before = "            this.zombie.teleportTo(this.zombie.realx, this.zombie.realy, this.zombie.realz);\n        }\n    }"
        source = replace_once(source, before, before.rsplit("\n    }", 1)[0]+"\n        "+CALL+'received(this.zombie, packet, "receive_after");\n    }')
    elif name.endswith("/ZombiePacket"):
        before = "        this.skinTextureIndex = b.getInt();"
        source = replace_once(source, before, before+"\n        "+CALL+'packet(this, "packet_in");')
        before = "    public void write(ByteBuffer bb) {"
        source = replace_once(source, before, before+"\n        "+CALL+'packet(this, "packet_out");')
    elif name.endswith("/NetworkZombieSimulator"):
        before = "                packetType.doPacket(b);"
        source = replace_once(source, before, before+"\n                "+CALL+"transport(packetType.toString(), packet.sendQueue.size());")
        before = "                    PacketTypes.PacketType.ZombieSimulationReliable.doPacket(b);"
        source = replace_once(source, before, before+"\n                    "+CALL+'transport("ZombieSimulationReliable_extra", packet.sendQueue.size());')
    elif name.endswith("/ZombieSynchronizationPacket"):
        before = "        this.hasNeighborPlayer = b.getBoolean();"
        source = replace_once(source, before, before+"\n        "+CALL+"cadence(this.hasNeighborPlayer);")
    else:
        raise ValueError(name)
    return source


def build(game_jar, output):
    game_jar, output = Path(game_jar).resolve(), Path(output).resolve()
    if output == game_jar or output.suffix.lower() != ".zip":
        raise ValueError("Output must be a diagnostic .zip archive, separate from the game JAR")
    javac = ROOT / "jdk/bin/javac.exe"
    with zipfile.ZipFile(game_jar) as jar:
        for name, expected in BASELINES.items():
            actual = hashlib.sha256(jar.read(name+".class")).hexdigest()
            if actual != expected:
                raise ValueError(f"Game JAR differs from verified 42.21.0 baseline: {name}")
    with tempfile.TemporaryDirectory(prefix="apocbr-zombie-sampler-") as directory:
        work = Path(directory)
        sources = []
        for name in NATIVE:
            source = (VERSION / "decompiled" / (name+".java")).read_text(encoding="utf-8")
            path = work / "src" / (name+".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(instrument(name, source), encoding="utf-8")
            sources.append(path)
        sources.extend(VERSION / "src/zombie/apocalipse/diagnostics" / name
                       for name in ["ClientZombieSampler.java", "ZombieCaptureWriter.java"])
        args = work / "javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\", "/")+'"' for p in sources), encoding="utf-8")
        classes = work / "classes"
        subprocess.run([str(javac), "--release", "25", "-encoding", "UTF-8", "-implicit:none", "-cp", str(game_jar), "-d", str(classes), "@"+str(args)], check=True)
        output.parent.mkdir(parents=True, exist_ok=True)
        files = sorted(classes.rglob("*.class"))
        families = NATIVE + ["zombie/apocalipse/diagnostics/ClientZombieSampler", "zombie/apocalipse/diagnostics/ZombieCaptureWriter"]
        for path in files:
            relative = str(path.relative_to(classes)).replace("\\", "/")
            if not any(relative == family+".class" or relative.startswith(family+"$") for family in families):
                raise ValueError(f"Unexpected client class output: {relative}")
        manifest = {"build": "42.21.0", "kind": "client-zombie-sampler", "baselines": BASELINES,
                    "files": [str(p.relative_to(classes)).replace("\\", "/") for p in files]}
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in files:
                archive.write(path, "classes/"+str(path.relative_to(classes)).replace("\\", "/"))
            archive.writestr("manifest.json", json.dumps(manifest, indent=2))
            archive.write(VERSION / "CLIENT-ZOMBIE-SAMPLER.md", "README.md")
        print(f"Compiled {len(sources)} sources / {len(files)} class files. Client capture archive: {output}")
        print("No game files changed.")
        return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-jar", type=Path, default=Path(r"Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar"))
    parser.add_argument("--output", type=Path, default=Path(tempfile.gettempdir()) / "apocbr-zombie-sampler-client.zip")
    args = parser.parse_args()
    build(args.game_jar, args.output)


if __name__ == "__main__":
    main()
