"""Review24 extracted mechanics expiry and server LOS bookkeeping checks."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_unload_simulation_algorithms import method

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    with tempfile.TemporaryDirectory(prefix="pz-player-port-") as directory:
        work = Path(directory)
        target = work / "42.22.0/src/zombie/characters/IsoPlayer.java"
        target.parent.mkdir(parents=True)
        target.write_bytes((V / "decompiled/zombie/characters/IsoPlayer.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-24-iso-player.diff")], cwd=work, check=True)
        candidate = target.read_text(encoding="utf-8-sig")
        applied = V / "src/zombie/characters/IsoPlayer.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate
        helper = method(candidate, "private void updateServerZombieAuthorityLOS()")
        assert "this.stats.numChasingZombies = 0" not in helper
        assert "setLastNumberChasingZombies" not in helper
        # Execute bookkeeping before native square/world access; remainder needs engine integration.
        bookkeeping = helper[helper.index("{") + 1:helper.index("        IsoGridSquare currentSquare")]
        expiry = method(candidate, "private void updateMechanicsItems()")
        java = r'''
import java.util.*;
class PlayerFixture {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static class GameTime {static final GameTime instance=new GameTime();Calendar calendar=Calendar.getInstance();static GameTime getInstance(){return instance;}Calendar getCalender(){return calendar;}}
 HashMap<Long,Long> mechanicsItem=new HashMap<>();long apocbrMechanicsLastTime=Long.MIN_VALUE,apocbrMechanicsNextExpiry=Long.MIN_VALUE;
 ArrayList<Object> spottedList=new ArrayList<>(),lastSpotted=new ArrayList<>();int numSurvivorsInVicinity;float closestZombie;
 static class Stats {int numVisibleZombies,numChasingZombies,lastNumberChasingZombies,musicZombiesTargetingDistantNotMoving,musicZombiesTargetingNearbyNotMoving,musicZombiesTargetingDistantMoving,musicZombiesTargetingNearbyMoving,musicZombiesVisible,lastNumVisibleZombies,lastVeryCloseZombies;}
 Stats stats=new Stats();void bookkeeping(){BOOKKEEPING}
 EXPIRY
 public static void main(String[] args){
  PlayerFixture p=new PlayerFixture();p.mechanicsItem.put(1L,0L);GameTime.instance.calendar.setTimeInMillis(86400000L);p.updateMechanicsItems();check(p.mechanicsItem.containsKey(1L));
  GameTime.instance.calendar.setTimeInMillis(86400001L);p.updateMechanicsItems();check(p.mechanicsItem.isEmpty());
  HashMap<Long,Long> reference=new HashMap<>();Random random=new Random(771);
  for(int i=0;i<5000;i++){
   if(i%7==0){long time=random.nextInt(200000000);reference.put((long)i,time);p.mechanicsItem.put((long)i,time);p.apocbrMechanicsNextExpiry=Long.MIN_VALUE;}
   long now=random.nextInt(300000000);GameTime.instance.calendar.setTimeInMillis(now);reference.entrySet().removeIf(e->now-e.getValue()>86400000L);
   p.updateMechanicsItems();check(p.mechanicsItem.equals(reference));
  }
  p.stats.numChasingZombies=42;p.stats.lastNumberChasingZombies=13;p.stats.numVisibleZombies=8;p.stats.lastVeryCloseZombies=5;
  p.spottedList.add(p);p.lastSpotted.add(p);p.bookkeeping();
  check(p.stats.numChasingZombies==42);check(p.stats.lastNumberChasingZombies==13);check(p.stats.numVisibleZombies==0);
  check(p.stats.lastVeryCloseZombies==0);check(p.spottedList.isEmpty()&&p.lastSpotted.isEmpty());check(p.closestZombie==1000000);
  System.out.println("IsoPlayer expiry/server-count bookkeeping: "+checks+" assertions passed");
 }
}
'''.replace("BOOKKEEPING", bookkeeping).replace("EXPIRY", expiry)
        p = work / "PlayerFixture.java"
        p.write_text(java, encoding="utf-8")
        subprocess.run(["javac", str(p)], check=True)
        subprocess.run(["java", "-cp", str(work), "PlayerFixture"], check=True)
        print("Full spatial LOS, network authority, movement and backend hooks are not executed")


if __name__ == "__main__":
    main()
