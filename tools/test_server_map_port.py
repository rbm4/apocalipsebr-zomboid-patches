"""Review-13 candidate queue fixture; no replacement source is required or changed."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def block(text, signature):
    start = text.index(signature)
    end = text.index("{", start) + 1
    depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def main():
    vanilla = (V / "decompiled/zombie/network/ServerMap.java").read_text(encoding="utf-8-sig")
    diff = V / "docs/reports/2026-10-28-merge-13-server-map.diff"
    with tempfile.TemporaryDirectory(prefix="pz-map-candidate-") as tmp:
        tmp = Path(tmp)
        source = tmp / "42.22.0/src/zombie/network/ServerMap.java"
        source.parent.mkdir(parents=True)
        source.write_text(vanilla, encoding="utf-8", newline="\n")
        subprocess.run(["git", "apply", str(diff)], cwd=tmp, check=True)
        candidate = source.read_text(encoding="utf-8")
        applied = V / "src/zombie/network/ServerMap.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate, "Applied source differs from reviewed candidate"
        signature = "if (!ServerMap.ServerCell.loaded2.isEmpty())"
        baseline_queue = block(vanilla, signature)
        candidate_queue = block(candidate, signature)
        compact = block(candidate, "private static void compactLoaded2()")
        java = r'''
import java.util.*;
class ServerMap {
 static int checks; static ArrayList<String> trace;
 ArrayList<ServerCell> loadedCells=new ArrayList<>(),releventNow=new ArrayList<>(),toLoad=new ArrayList<>();
 HashSet<ServerCell> releventNowLookup=new HashSet<>(),completedLoad2=new HashSet<>();
 ServerCell[] cellMap=new ServerCell[64];int width=8;
 int getMinX(){return 0;}int getMinY(){return 0;}
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static class ServerLOS {
  static ServerLOS instance=new ServerLOS();boolean paused;
  void suspend(){check(!paused);paused=true;}void resume(){check(paused);paused=false;}
 }
 static class DebugType {static DebugType General=new DebugType();void printException(Exception e,Object... args){trace.add("error:"+e.getMessage());}}
 static class LogSeverity {static Object Error=new Object();}
 static class Loader {
  ArrayList<ArrayList<ServerCell>> waves=new ArrayList<>();int calls;
  void getRecalc(ArrayList<ServerCell> queue){if(calls<waves.size())queue.addAll(waves.get(calls));calls++;}
 }
 static class ServerCell {
  static ArrayList<ServerCell> loaded2=new ArrayList<>();static Loader chunkLoader;
  int id,wx,wy;boolean fail,unloadFail;
  void Load2(){trace.add("load"+id);if(fail)throw new RuntimeException("load"+id);}
  void Unload(){trace.add("unload"+id);if(unloadFail)throw new RuntimeException("unload"+id);}
 }
 COMPACT
 void baseline(){BASELINE}
 void candidate(){CANDIDATE}
 static List<String> run(int seed,boolean patched) {
  Random random=new Random(seed);ServerMap map=new ServerMap();trace=new ArrayList<>();
  ServerLOS.instance=new ServerLOS();ServerCell.loaded2=new ArrayList<>();ServerCell.chunkLoader=new Loader();
  int size=random.nextInt(65);ArrayList<ServerCell> all=new ArrayList<>();
  for(int n=0;n<5;n++)ServerCell.chunkLoader.waves.add(new ArrayList<>());
  for(int id=0;id<size;id++){
   ServerCell c=new ServerCell();c.id=id;c.wx=id%8;c.wy=id/8;c.fail=random.nextInt(4)==0;c.unloadFail=random.nextBoolean();
   all.add(c);map.cellMap[id]=c;map.loadedCells.add(c);map.releventNow.add(c);map.releventNowLookup.add(c);map.toLoad.add(c);
   if(id==0||random.nextBoolean())ServerCell.loaded2.add(c);else ServerCell.chunkLoader.waves.get(random.nextInt(5)).add(c);
  }
  // Drain all initial waves; later waves are drained during processing.
  ServerCell.chunkLoader.getRecalc(ServerCell.loaded2);
  if(patched)map.candidate();else map.baseline();
  check(!ServerLOS.instance.paused);check(ServerCell.loaded2.isEmpty());
  if(patched){check(map.completedLoad2.isEmpty());check(map.releventNowLookup.equals(new HashSet<>(map.releventNow)));}
  for(ServerCell c:all){
   boolean processed=trace.contains("load"+c.id);
   check(map.toLoad.contains(c)!=processed);
   check(map.loadedCells.contains(c)==!(processed&&c.fail));
   check(map.releventNow.contains(c)==!(processed&&c.fail));
   check((map.cellMap[c.id]==c)==!(processed&&c.fail));
  }
  trace.add("pending"+map.toLoad.stream().map(c->c.id).toList());
  trace.add("loaded"+map.loadedCells.stream().map(c->c.id).toList());
  return List.copyOf(trace);
 }
 public static void main(String[] args){
  for(int seed=0;seed<3000;seed++)check(run(seed,false).equals(run(seed,true)));
  System.out.println("ServerMap candidate queue: "+checks+" assertions passed (3000 differential scenarios)");
 }
}
'''
        java = java.replace("COMPACT", compact).replace("BASELINE", baseline_queue).replace("CANDIDATE", candidate_queue)
        target = tmp / "ServerMap.java"
        target.write_text(java, encoding="utf-8")
        subprocess.run(["javac", str(target)], cwd=tmp, check=True)
        subprocess.run(["java", "-cp", str(tmp), "ServerMap"], cwd=tmp, check=True)
        assert block(candidate, "public void QueuedSaveAll(boolean quit)") == block(vanilla, "public void QueuedSaveAll(boolean quit)")
        assert "public void Load2()" in candidate and "public boolean Load2()" not in candidate
        assert "if (this.isLoaded)" not in block(candidate, "public void Unload()")
        assert "load2At(" not in candidate
        shutdown = block(candidate, "public void QueuedQuit()")
        hooks = ["ServerHumanFactory.shutdown()", "this.QueuedSaveAll(true)",
                 "runShutdownDatabaseWork(Roles::save)", "PathfindNative.instance.stop()",
                 "chunkLoader.quit()", "VehiclesDB2.instance.Reset()", "closePool()", "udpEngine.Shutdown()"]
        positions = [shutdown.index(hook) for hook in hooks]
        assert positions == sorted(positions)
        print("Native save method/void descriptor/partial unload and shutdown ordering checks passed")


if __name__ == "__main__":
    main()
