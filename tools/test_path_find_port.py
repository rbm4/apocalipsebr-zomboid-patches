"""Review26 extracted threshold versus native path-length decisions."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_unload_simulation_algorithms import method

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    native = (V / "decompiled/zombie/pathfind/PathFindBehavior2.java").read_text(encoding="utf-8-sig")
    with tempfile.TemporaryDirectory(prefix="pz-path-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/pathfind/PathFindBehavior2.java"
        p.parent.mkdir(parents=True)
        p.write_text(native, encoding="utf-8", newline="\n")
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-26-path-find-behavior.diff")], cwd=work, check=True)
        candidate = p.read_text(encoding="utf-8")
        applied = V / "src/zombie/pathfind/PathFindBehavior2.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate
        for signature in ["public float getPathLength()", "private void initPathFindRequest(boolean avoidFarmingPlants)", "public PathFindBehavior2.BehaviorResult update(float speedMul)"]:
            assert method(candidate, signature) == method(native, signature)
        java = r'''
import java.util.*;
class PathFixture {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static class Character {float x,y;float getX(){return x;}float getY(){return y;}}
 static class Node {float x,y;Node(float x,float y){this.x=x;this.y=y;}}
 static class Path {ArrayList<Node> nodes=new ArrayList<>();}
 Character chr=new Character();Path path=new Path();int pathIndex;float targetX,targetY;
 LENGTH
 THRESHOLD
 public static void main(String[] args){
  Random random=new Random(921);
  for(int n=0;n<2000;n++){
   PathFixture f=new PathFixture();f.chr.x=random.nextFloat()*100;f.chr.y=random.nextFloat()*100;
   f.targetX=random.nextFloat()*100;f.targetY=random.nextFloat()*100;
   int count=random.nextInt(70);for(int i=0;i<count;i++)f.path.nodes.add(new Node(random.nextFloat()*100,random.nextFloat()*100));
   f.pathIndex=random.nextInt(count+2)-1;
   for(float threshold:new float[]{-1,0,5,100,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NaN})check(f.isPathLengthGreaterThan(threshold)==(f.getPathLength()>threshold));
  }
  PathFixture f=new PathFixture();f.path=null;check(f.isPathLengthGreaterThan(5)==(f.getPathLength()>5));
  f.path=new Path();f.path.nodes.add(new Node(0,0));for(int i=1;i<100;i++)f.path.nodes.add(new Node(i*10,0));
  zombie.ApocBRServerTelemetryLite.visited=0;check(f.isPathLengthGreaterThan(5));check(zombie.ApocBRServerTelemetryLite.visited==1);
  System.out.println("Path threshold: "+checks+" assertions passed (finite path geometry)");
 }
}
'''.replace("LENGTH", method(native, "public float getPathLength()")).replace("THRESHOLD", method(candidate, "public boolean isPathLengthGreaterThan(float threshold)"))
        sources = {
            "PathFixture.java": java,
            "zombie/network/GameServer.java": "package zombie.network;public class GameServer {public static boolean server=true;}",
            "zombie/ApocBRServerTelemetryLite.java": "package zombie;public class ApocBRServerTelemetryLite {public static long visited;public static void count(String n,long v){visited+=v;}}",
        }
        paths = []
        for relative, content in sources.items():
            p = work / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            paths.append(str(p))
        subprocess.run(["javac", "-d", str(work), *paths], check=True)
        subprocess.run(["java", "-cp", str(work), "PathFixture"], check=True)


if __name__ == "__main__":
    main()
