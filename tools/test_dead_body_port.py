"""Review22 extracted wakeup/LOS differential; loading checked at source level."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_unload_simulation_algorithms import method

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    native = (V / "decompiled/zombie/iso/objects/IsoDeadBody.java").read_text(encoding="utf-8-sig")
    with tempfile.TemporaryDirectory(prefix="pz-corpse-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/iso/objects/IsoDeadBody.java"
        p.parent.mkdir(parents=True)
        p.write_text(native, encoding="utf-8", newline="\n")
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-22-iso-dead-body.diff")], cwd=work, check=True)
        candidate = p.read_text(encoding="utf-8")
        applied = V / "src/zombie/iso/objects/IsoDeadBody.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate
        assert method(candidate, "private boolean isPlayerNearby(IsoPlayer player, boolean isCanSee)") == method(native, "private boolean isPlayerNearby(IsoPlayer player, boolean isCanSee)")
        assert "boneCount < 0 || boneCount > 256 || input.remaining() / 44 < boneCount" in candidate
        assert "try(var dictionary=ApocBRWorldDictionaryDecode.enter(input))" in candidate
        java = r'''
import java.util.*;
class CorpseFixture {
 static int checks; static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 IsoGridSquare square;IsoGridSquare getSquare(){return square;}
 static class IsoGridSquare {boolean isCanSee(int pn){return IsoPlayer.players[pn]!=null&&IsoPlayer.players[pn].visible;}}
 static class IsoPlayer {
  static int numPlayers;static IsoPlayer[] players;boolean dead,ghost,invisible,vehicle,visible;float distance;
  boolean isDead(){return dead;}boolean isGhostMode(){return ghost;}boolean isInvisible(){return invisible;}
  Object getVehicle(){return vehicle?this:null;}float DistToSquared(CorpseFixture b){return distance;}
 }
 static class GameServer {static boolean server;static ArrayList<IsoPlayer> Players=new ArrayList<>();}
 static class ServerLOS {static ServerLOS instance=new ServerLOS();static int calls;boolean isCouldSee(IsoPlayer p,IsoGridSquare s){calls++;return p!=null&&p.visible;}}
 NATIVE
 OPTIMIZED
 PREDICATE
 public static void main(String[] args){
  Random random=new Random(822);float[] distances={0,3.99f,4,8,16,16.01f,100,Float.NaN,Float.POSITIVE_INFINITY};
  for(int run=0;run<5000;run++){
   CorpseFixture body=new CorpseFixture();body.square=random.nextBoolean()?new IsoGridSquare():null;
   GameServer.Players.clear();GameServer.server=random.nextBoolean();
   for(int n=0;n<16;n++){IsoPlayer p=new IsoPlayer();p.dead=random.nextBoolean();p.ghost=random.nextBoolean();p.invisible=random.nextBoolean();
    p.vehicle=random.nextBoolean();p.visible=random.nextBoolean();p.distance=distances[random.nextInt(distances.length)];GameServer.Players.add(random.nextInt(8)==0?null:p);}
   IsoPlayer.players=GameServer.Players.toArray(new IsoPlayer[0]);IsoPlayer.numPlayers=IsoPlayer.players.length;
   ServerLOS.calls=0;boolean expected=body.nativeNearby();int originalCalls=ServerLOS.calls;
   ServerLOS.calls=0;check(body.isPlayerNearby()==expected);check(ServerLOS.calls<=originalCalls);
   if(!GameServer.server)check(ServerLOS.calls==0);
  }
  CorpseFixture body=new CorpseFixture();body.square=new IsoGridSquare();GameServer.server=true;GameServer.Players.clear();
  IsoPlayer distant=new IsoPlayer();distant.distance=100;distant.visible=true;GameServer.Players.add(distant);
  ServerLOS.calls=0;check(!body.isPlayerNearby());check(ServerLOS.calls==0);
  System.out.println("Corpse wakeup/LOS: "+checks+" assertions passed (5000 differential cases)");
 }
}
'''
        java = java.replace("NATIVE", method(native, "private boolean isPlayerNearby()").replace("isPlayerNearby()", "nativeNearby()", 1))
        java = java.replace("OPTIMIZED", method(candidate, "private boolean isPlayerNearby()"))
        java = java.replace("PREDICATE", method(native, "private boolean isPlayerNearby(IsoPlayer player, boolean isCanSee)"))
        p = work / "CorpseFixture.java"
        p.write_text(java, encoding="utf-8")
        subprocess.run(["javac", str(p)], check=True)
        subprocess.run(["java", "-cp", str(work), "CorpseFixture"], check=True)
        print("Native final predicate and bone guard retained; load scope present (full decoder not executed)")


if __name__ == "__main__":
    main()
