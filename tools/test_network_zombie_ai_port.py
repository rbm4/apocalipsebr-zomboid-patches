"""Review28 extracted enum caches and dynamic server null-animation fallback."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_unload_simulation_algorithms import method

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    with tempfile.TemporaryDirectory(prefix="pz-zombie-ai-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/characters/NetworkZombieAI.java"
        p.parent.mkdir(parents=True)
        p.write_bytes((V / "decompiled/zombie/characters/NetworkZombieAI.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-28-network-zombie-ai.diff")], cwd=work, check=True)
        candidate = p.read_text(encoding="utf-8-sig")
        applied = V / "src/zombie/characters/NetworkZombieAI.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate
        fields = candidate[candidate.index("private String lastWalkType"):candidate.index("private NetworkVariables.WalkType resolveWalkType")]
        java = r'''
import java.util.*;import zombie.network.NetworkVariables;
class ZombieCacheFixture {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static class GameServer {static boolean server;}
 static class Animator {String state;String getCurrentStateName(){return state;}}
 static class Path {boolean goal;boolean isGoalLocation(){return goal;}}
 static class Zombie {Animator animator=new Animator();Path path=new Path();Animator getAdvancedAnimator(){return animator;}Path getPathFindBehavior2(){return path;}}
 Zombie zombie=new Zombie();FIELDS
 WALK
 STATE
 public static void main(String[] args){
  ZombieCacheFixture f=new ZombieCacheFixture();Locale original=Locale.getDefault();
  try {
   for(Locale locale:new Locale[]{Locale.ENGLISH,Locale.forLanguageTag("tr-TR"),Locale.ROOT}){
    Locale.setDefault(locale);
    for(boolean server:new boolean[]{false,true})for(String value:new String[]{null,"idle","SITTING","pathfind","walktoward","unknown","Idle"}){
     GameServer.server=server;f.zombie.animator.state=value;
     for(boolean goal:new boolean[]{false,true,false}){
      f.zombie.path.goal=goal;var expected=server&&value==null?(goal?NetworkVariables.ZombieState.PathFind:NetworkVariables.ZombieState.Idle):NetworkVariables.ZombieState.fromString(value);
      check(f.resolveState()==expected);check(f.resolveState()==expected);
     }
    }
    for(String walk:new String[]{null,"1","WT1","sprint1","unknown","walk1","Walk1"}){check(f.resolveWalkType(walk)==NetworkVariables.WalkType.fromString(walk));check(f.resolveWalkType(walk)==NetworkVariables.WalkType.fromString(walk));}
   }
  }finally{Locale.setDefault(original);}
  System.out.println("NetworkZombieAI cache/fallback: "+checks+" assertions passed");
 }
}
'''.replace("FIELDS", fields).replace("WALK", method(candidate, "private NetworkVariables.WalkType resolveWalkType(String value)")).replace("STATE", method(candidate, "private NetworkVariables.ZombieState resolveState()"))
        p = work / "ZombieCacheFixture.java"
        p.write_text(java, encoding="utf-8")
        subprocess.run(["javac", "-d", str(work), str(p), str(V / "src/zombie/network/NetworkVariables.java")], check=True)
        subprocess.run(["java", "-cp", str(work), "ZombieCacheFixture"], check=True)
        print("Full prediction, packet fields/bookkeeping, ownership and worker lifecycle are not executed")


if __name__ == "__main__":
    main()
