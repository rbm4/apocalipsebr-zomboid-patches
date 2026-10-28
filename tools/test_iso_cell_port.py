"""Extracted target IsoCell cadence fixture with explicit engine/update doubles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def block(text, signature):
    start = text.index(signature)
    end = text.index("{", start) + 1
    depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def main():
    text = (ROOT / "42.22.0/src/zombie/iso/IsoCell.java").read_text(encoding="utf-8-sig")
    constant = next(s.strip() for s in text.splitlines() if "private static final int APOC_BR_PROCESS_ISO_OBJECT_FRAME_MOD" in s)
    sources = {
        "zombie/GameTime.java": "package zombie; public class GameTime {static final GameTime instance=new GameTime();public static GameTime getInstance(){return instance;}public float perObjectMultiplier=1;}",
        "zombie/network/GameServer.java": "package zombie.network;public class GameServer {public static boolean server=true;}",
        "zombie/ApocBRServerTelemetryLite.java": "package zombie;public class ApocBRServerTelemetryLite {public static void count(String s,long n){}}",
        "zombie/iso/IsoWorld.java": "package zombie.iso;public class IsoWorld {public static final IsoWorld instance=new IsoWorld();public int frame;public int getFrameNo(){return frame;}}",
        "zombie/iso/IsoObject.java": """package zombie.iso;public class IsoObject {
          public long id;public int calls;public float elapsed;public boolean fail;public Runnable callback;
          public long getEntityNetID(){return id;}public void update(){calls++;elapsed+=zombie.GameTime.getInstance().perObjectMultiplier;
          if(callback!=null)callback.run();if(fail)throw new IllegalStateException("update");}}
        """,
        "zombie/iso/ServerIsoObjectUpdateTelemetry.java": "package zombie.iso;class ServerIsoObjectUpdateTelemetry {static int size(){return 1;}static int kind(IsoObject o){return 0;}static void flush(long[] n,long[] a){}}",
        "zombie/iso/objects/IsoThumpable.java": "package zombie.iso.objects;public class IsoThumpable extends zombie.iso.IsoObject {public boolean work;public boolean apocbrNeedsServerUpdate(){return work;}}",
        "zombie/iso/objects/IsoTrap.java": "package zombie.iso.objects;public class IsoTrap extends zombie.iso.IsoObject {}",
        "zombie/iso/objects/IsoGenerator.java": "package zombie.iso.objects;public class IsoGenerator extends zombie.iso.IsoObject {}",
    }
    java = """package zombie.iso;
import java.util.*;import zombie.GameTime;import zombie.network.GameServer;import zombie.ApocBRServerTelemetryLite;import zombie.iso.objects.*;
public class IsoCell {
 ArrayList<IsoObject> processIsoObject=new ArrayList<>();Set<IsoObject> processIsoObjectSet=new HashSet<>(),processIsoObjectRemove=new HashSet<>();
""" + constant + block(text, "private static int getIsoObjectUpdatePhase(") + block(text, "private void ProcessIsoObject()") + r'''
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static class CustomThump extends IsoThumpable {}
 static class CustomGenerator extends IsoGenerator {}
 void add(IsoObject o){processIsoObject.add(o);processIsoObjectSet.add(o);}
 public static void main(String[] args){
  int mod=APOC_BR_PROCESS_ISO_OBJECT_FRAME_MOD;IsoCell c=new IsoCell();
  ArrayList<IsoObject> regular=new ArrayList<>();
  for(int n=0;n<250;n++){IsoObject o=new IsoObject();o.id=n-125;c.add(o);regular.add(o);}
  IsoTrap trap=new IsoTrap();CustomGenerator generator=new CustomGenerator();
  IsoThumpable idle=new IsoThumpable(),working=new IsoThumpable();working.work=true;CustomThump custom=new CustomThump();
  c.add(trap);c.add(generator);c.add(idle);c.add(working);c.add(custom);
  for(int frame=0;frame<mod*2;frame++){IsoWorld.instance.frame=frame;c.ProcessIsoObject();check(GameTime.getInstance().perObjectMultiplier==1);}
  for(IsoObject o:regular){check(o.calls==2);check(o.elapsed==mod*2);}
  check(trap.calls==mod*2&&trap.elapsed==mod*2);check(generator.calls==mod*2&&generator.elapsed==mod*2);
  check(idle.calls==0);check(working.calls==2&&working.elapsed==mod*2);check(custom.calls==2);
  // Client bypasses the exact-class no-work predicate.
  GameServer.server=false;for(int frame=0;frame<mod;frame++){IsoWorld.instance.frame=frame;c.ProcessIsoObject();}
  check(idle.calls==1);GameServer.server=true;
  // Pending removals update both collection views before scheduling.
  IsoObject removed=regular.get(0);int calls=removed.calls;c.processIsoObjectRemove.add(removed);c.ProcessIsoObject();
  check(!c.processIsoObject.contains(removed)&&!c.processIsoObjectSet.contains(removed));check(c.processIsoObjectRemove.isEmpty());check(removed.calls==calls);
  // Removing the current entry must not skip the following entry.
  c=new IsoCell();IsoCell current=c;IsoObject self=new IsoObject(),next=new IsoObject();self.id=next.id=7;
  self.callback=()->{current.processIsoObject.remove(self);current.processIsoObjectSet.remove(self);};c.add(self);c.add(next);
  IsoWorld.instance.frame=getIsoObjectUpdatePhase(self,mod);c.ProcessIsoObject();check(self.calls==1&&next.calls==1);
  // Both ordinary and every-frame exceptions restore the shared multiplier.
  for(IsoObject bad:new IsoObject[]{new IsoObject(),new IsoTrap()}){
   c=new IsoCell();bad.id=5;bad.fail=true;c.add(bad);IsoWorld.instance.frame=getIsoObjectUpdatePhase(bad,mod);
   try{c.ProcessIsoObject();throw new AssertionError("expected failure");}catch(IllegalStateException expected){}
   check(GameTime.getInstance().perObjectMultiplier==1);
  }
  System.out.println("IsoCell cadence mod="+mod+": "+checks+" assertions passed");
 }
}
'''
    sources["zombie/iso/IsoCell.java"] = java
    with tempfile.TemporaryDirectory(prefix="pz-cell-fixture-") as tmp:
        tmp = Path(tmp)
        paths = []
        for relative, content in sources.items():
            p = tmp / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            paths.append(str(p))
        subprocess.run(["javac", "-d", str(tmp), *paths], check=True)
        for mod in [1, 10, 17]:
            subprocess.run(["java", f"-Dapocbr.isoObjectUpdateFrameMod={mod}", "-cp", str(tmp), "zombie.iso.IsoCell"], check=True)


if __name__ == "__main__":
    main()
