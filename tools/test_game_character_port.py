"""Review25 inventory/spatial support fixture; full character lifecycle not executed."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_player_packet_algorithms import STUBS, HARNESS

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    with tempfile.TemporaryDirectory(prefix="pz-character-port-") as directory:
        work = Path(directory)
        target = work / "42.22.0/src/zombie/characters/IsoGameCharacter.java"
        target.parent.mkdir(parents=True)
        target.write_bytes((V / "decompiled/zombie/characters/IsoGameCharacter.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-25-iso-game-character.diff")], cwd=work, check=True)
        applied = V / "src/zombie/characters/IsoGameCharacter.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == target.read_text(encoding="utf-8-sig")
        harness = HARNESS[:HARNESS.index(" static float vanillaStress")]
        harness = harness.replace("import zombie.popman.*;", "")
        harness += 'public static void main(String[] args){inventory();spatial();System.out.println("Character inventory/spatial support: "+assertions+" assertions passed");}}'
        files = []
        for relative, content in STUBS.items():
            p = work / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            files.append(str(p))
        p = work / "PlayerPacketAlgorithmsTest.java"
        p.write_text(harness, encoding="utf-8")
        files.append(str(p))
        for relative in ["util/list/MutationTrackedArrayList.java", "inventory/ServerInventoryUpdateIndex.java", "ServerPlayerSpatialQueries.java"]:
            files.append(str(V / "src/zombie" / relative))
        subprocess.run(["javac", "-d", str(work), *files], check=True)
        subprocess.run(["java", "-cp", str(work), "PlayerPacketAlgorithmsTest"], check=True)
        print("Full character, backend hooks and zombie authority are not executed")


if __name__ == "__main__":
    main()
