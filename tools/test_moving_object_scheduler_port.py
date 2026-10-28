"""Run shared lifecycle tests and native chase-count checks on review 16."""
from pathlib import Path
import subprocess
import tempfile
from test_moving_object_algorithms import STUBS, HARNESS

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    with tempfile.TemporaryDirectory(prefix="pz-scheduler-port-") as directory:
        work = Path(directory)
        target = work / "42.22.0/src/zombie/MovingObjectUpdateScheduler.java"
        target.parent.mkdir(parents=True)
        target.write_bytes((V / "decompiled/zombie/MovingObjectUpdateScheduler.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-16-moving-object-scheduler.diff")], cwd=work, check=True)
        applied = V / "src/zombie/MovingObjectUpdateScheduler.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == target.read_text(encoding="utf-8-sig")
        stubs = dict(STUBS)
        stubs["zombie/network/GameServer.java"] = "package zombie.network;public class GameServer {public static boolean server=true,guiCommandline=false;public static java.util.HashMap<Integer,zombie.characters.IsoPlayer> IDToPlayerMap=new java.util.HashMap<>();}"
        stubs["zombie/characters/IsoZombie.java"] = "package zombie.characters;public class IsoZombie extends IsoGameCharacter {public IsoGameCharacter target;public int gui;public void updateForServerGui(){gui++;}}"
        stubs["zombie/characters/IsoPlayer.java"] = "package zombie.characters;public class IsoPlayer extends IsoGameCharacter {public static int numPlayers=0;public static IsoPlayer[] players=new IsoPlayer[0];public boolean animal;public boolean isAnimal(){return animal;}public static class Stats{public int numChasingZombies;}private Stats stats=new Stats();public Stats getStats(){return stats;}}"
        sources = []
        for relative, content in stubs.items():
            p = work / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            sources.append(str(p))
        chase = r'''
    static void nativeChasingCounts() {
      GameServer.server=true;GameServer.guiCommandline=false;
      IsoCell cell=new IsoCell();IsoWorld.instance.setCell(cell);
      zombie.characters.IsoPlayer player=new zombie.characters.IsoPlayer(),animal=new zombie.characters.IsoPlayer();animal.animal=true;
      GameServer.IDToPlayerMap.clear();GameServer.IDToPlayerMap.put(1,player);GameServer.IDToPlayerMap.put(2,animal);
      zombie.characters.IsoZombie one=new zombie.characters.IsoZombie(),two=new zombie.characters.IsoZombie(),other=new zombie.characters.IsoZombie();
      one.target=player;two.target=player;other.target=animal;
      cell.getObjectList().add(one);cell.getObjectList().add(two);cell.getObjectList().add(other);
      player.getStats().numChasingZombies=99;animal.getStats().numChasingZombies=99;
      MovingObjectUpdateScheduler scheduler=MovingObjectUpdateScheduler.instance;scheduler.startFrame();
      check(player.getStats().numChasingZombies==2,"counts active zombie targets without GUI");check(animal.getStats().numChasingZombies==0,"animal target excluded");
      check(one.gui==0,"GUI remains optional");scheduler.startFrame();check(player.getStats().numChasingZombies==2,"counts reset each frame");
      cell.getObjectList().remove(two);scheduler.startFrame();check(player.getStats().numChasingZombies==1,"removal excluded from target count");
      GameServer.guiCommandline=true;scheduler.startFrame();check(one.gui==1&&other.gui==1,"GUI pass retained");
      GameServer.guiCommandline=false;GameServer.IDToPlayerMap.clear();
    }
'''
        harness = HARNESS.replace("public static void main(String[] args) throws Exception {", chase + "public static void main(String[] args) throws Exception {\nnativeChasingCounts();")
        # The shared engine double exposes its mutable cell directly.
        harness = harness.replace("IsoWorld.instance.setCell(cell);", "IsoWorld.instance.cell=cell;")
        p = work / "zombie/MovingObjectAlgorithmsTest.java"
        p.write_text(harness, encoding="utf-8")
        sources.append(str(p))
        sources.append(str(target))
        for name in ["MovingObjectUpdateSchedulerUpdateBucket.java", "ServerMovingObjectIndex.java", "ServerMovingObjectSet.java", "ServerAnimalPerceptionGrid.java", "vehicles/ServerVehicleUpdateTelemetry.java"]:
            sources.append(str(V / "src/zombie" / name))
        sources.append(str(V / "decompiled/zombie/UpdateSchedulerSimulationLevel.java"))
        args = work / "javac.args"
        args.write_text("\n".join('"' + s.replace("\\", "/") + '"' for s in sources), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(work / "classes"), "zombie.MovingObjectAlgorithmsTest"], check=True)


if __name__ == "__main__":
    main()
