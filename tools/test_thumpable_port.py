"""Target no-work gate differential fixture with native server update body."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_unload_simulation_algorithms import method, NO_WORK_TEST

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    native = (V / "decompiled/zombie/iso/objects/IsoThumpable.java").read_text(encoding="utf-8-sig")
    with tempfile.TemporaryDirectory(prefix="pz-thump-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/iso/objects/IsoThumpable.java"
        p.parent.mkdir(parents=True)
        p.write_text(native, encoding="utf-8", newline="\n")
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-19-iso-thumpable.diff")], cwd=work, check=True)
        candidate = p.read_text(encoding="utf-8")
        applied = V / "src/zombie/iso/objects/IsoThumpable.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate
        update = method(native, "public void update()")
        assert update == method(candidate, "public void update()")
        predicate = method(candidate, "public boolean apocbrNeedsServerUpdate()")
        # The entire candidate must be native source plus this one documented helper.
        addition = "    /** Exact server no-op/deadline predicate; subclasses retain their own update contract. */\n    " + predicate + "\n\n"
        assert candidate.replace(addition, "", 1) == native
        server_update = update.replace(method(update, "if (!GameServer.server)"), "").replace("public void update()", "public void vanillaUpdate()")
        thump = """package zombie.iso.objects;import zombie.GameTime;public class IsoThumpable {
          float lifeLeft,lastUpdateHours,updateAccumulator;boolean lightSourceOn;int objectIndex,writes;
          float getLifeLeft(){return lifeLeft;}boolean isLightSourceOn(){return lightSourceOn;}int getObjectIndex(){return objectIndex;}
          float getLifeDelta(){return 0.2f;}void setLifeLeft(float v){lifeLeft=v;writes++;}void toggleLightSource(boolean v){lightSourceOn=v;writes++;}
        """ + predicate + server_update + "}"
        sources = {
            "zombie/iso/objects/IsoThumpable.java": thump,
            "zombie/iso/objects/NoWorkTest.java": NO_WORK_TEST.replace("public class NoWorkTest {", "public class NoWorkTest {public static void main(String[] args){run();}"),
            "zombie/GameTime.java": "package zombie;public class GameTime {public static double hours;private static final GameTime instance=new GameTime();public static GameTime getInstance(){return instance;}public double getWorldAgeHours(){return hours;}}",
        }
        paths = []
        for relative, content in sources.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            paths.append(str(path))
        subprocess.run(["javac", "-d", str(work), *paths], check=True)
        subprocess.run(["java", "-cp", str(work), "zombie.iso.objects.NoWorkTest"], check=True)
        print("Candidate equals target vanilla plus predicate; native update and API changes preserved")


if __name__ == "__main__":
    main()
