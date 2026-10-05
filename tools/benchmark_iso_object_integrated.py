"""Integrated 42.21 registry mutations and scheduling replay; no runtime scheduler edits."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from benchmark_iso_object_replay import fixture, method, HARNESS as BASE_HARNESS
from test_iso_entity_registry import build_sources

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.21.0"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--square-sizes", default="4,32,128")
    parser.add_argument("--wait", choices=("spin", "none"), default="spin")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sizes = [int(n) for n in args.square_sizes.split(",")]
    if args.repeats < 1 or min(sizes) < 2:
        parser.error("Positive repetitions and occupancies >=2 required")
    data = fixture(json.loads(args.input.read_text(encoding="utf-8-sig")))
    sources = build_sources()
    sources.pop("zombie/entity/RegistryTest.java")
    sources["zombie/ApocBRServerTelemetryLite.java"] = """package zombie;public class ApocBRServerTelemetryLite{
      public static final java.util.Map<String,Long> counters=new java.util.HashMap<>(),timings=new java.util.HashMap<>();
      public static void count(String k,long v){counters.merge(k,v,Long::sum);}public static void recordPhase(String k,long n){timings.merge(k,n,Long::sum);}}
    """
    sources["zombie/GameTime.java"] = "package zombie;public class GameTime{public static final GameTime instance=new GameTime();public static GameTime getInstance(){return instance;}public float perObjectMultiplier=1;public double getWorldAgeHours(){return 0;}}"
    sources["zombie/iso/IsoWorld.java"] = "package zombie.iso;public class IsoWorld{public static final IsoWorld instance=new IsoWorld();public int frame;public int getFrameNo(){return frame;}}"
    square = sources["zombie/iso/IsoGridSquare.java"]
    square = square.replace("public IsoGridSquare(int x,int y,int z)", "public IsoGridSquare(int x){this(x,10,0);}public IsoGridSquare(int x,int y,int z)")
    sources["zombie/iso/IsoGridSquare.java"] = square
    obj = sources["zombie/iso/IsoObject.java"]
    obj = obj.replace("public int getObjectIndex() {", "public int getObjectIndex() { Integrated.indexReads++;")
    obj = obj.replace("public long getEntityNetID() {", "public long getEntityNetID() {if(Integrated.legacy)return legacyID();")
    legacy = method((V / "decompiled/zombie/iso/IsoObject.java").read_text(), "public long getEntityNetID()")
    legacy = legacy.replace("getEntityNetID", "legacyID", 1).replace("if (this.isoEntityNetId == -1L", "if (this.apocbrEntityIdDirty || this.isoEntityNetId == -1L")
    legacy = legacy.replace("return -1L;", "this.apocbrEntityIdDirty=false;return -1L;").replace("return this.isoEntityNetId;", "this.apocbrEntityIdDirty=false;return this.isoEntityNetId;")
    obj = obj[:-1] + """
      int phase,kind,ordinal;long duration;boolean always;
      public void update(){Integrated.calls++;Integrated.hash=Integrated.hash*31+ordinal;Integrated.requestedWait+=duration;
        if(Integrated.waits&&duration>0){long until=System.nanoTime()+duration;while(System.nanoTime()<until)Thread.onSpinWait();}}
    """ + legacy + "}"
    sources["zombie/iso/IsoObject.java"] = obj
    # A fixture-only bridge exposes the actual production lifecycle boundaries.
    manager = sources["zombie/entity/GameEntityManager.java"]
    manager = manager[:-1] + """
      public static void mockRegister(IsoObject o){RegisterEntity(o);if(!o.addedToEntityManager)throw new AssertionError("registration");}
      public static void mockUnregister(IsoObject o){UnregisterEntity(o);if(o.addedToEntityManager||o.addedToEngine)throw new AssertionError("unregistration");}
      public static void mockReset(){ServerIsoEntityRegistry.reset();idToEntityMap.clear();engine=new Engine();}
      public static void mockEmpty(){if(!idToEntityMap.isEmpty()||!engine.objects.isEmpty())throw new AssertionError("lifecycle leak");}
    }"""
    sources["zombie/entity/GameEntityManager.java"] = manager
    cell = (V / "src/zombie/iso/IsoCell.java").read_text()
    process = method(cell, "private void ProcessIsoObject()")
    assigned = process.replace("ProcessIsoObject", "ProcessAssigned").replace("getIsoObjectUpdatePhase(i, mod)", "i.phase")
    sources["zombie/iso/IsoCell.java"] = """package zombie.iso;import java.util.*;import zombie.GameTime;import zombie.network.GameServer;import zombie.ApocBRServerTelemetryLite;import zombie.iso.objects.*;
      public class IsoCell{ArrayList<IsoObject> processIsoObject=new ArrayList<>();final Set<IsoObject> processIsoObjectSet=new HashSet<>(),processIsoObjectRemove=new HashSet<>();
      private static final int APOC_BR_PROCESS_ISO_OBJECT_FRAME_MOD=10;
    """ + method(cell, "private static int getIsoObjectUpdatePhase(") + process + assigned + """
      void run(ArrayList<IsoObject> list,int mode){processIsoObject=list;if(mode<2)ProcessIsoObject();else ProcessAssigned();}
      static int phase(IsoObject o){return getIsoObjectUpdatePhase(o,10);}}
    """
    for c in data["classes"]:
        name = c["kind"]
        if name != "other":
            sources["zombie/iso/objects/" + name + ".java"] = "package zombie.iso.objects;public class " + name + " extends zombie.iso.IsoObject{}"
    thump = (V / "src/zombie/iso/objects/IsoThumpable.java").read_text()
    sources["zombie/iso/objects/IsoThumpable.java"] = "package zombie.iso.objects;import zombie.GameTime;public class IsoThumpable extends zombie.iso.IsoObject{float updateAccumulator,lastUpdateHours=-1;float getLifeLeft(){return -1;}boolean isLightSourceOn(){return false;}" + method(thump, "public boolean apocbrNeedsServerUpdate()") + "}"
    specs = ",".join('new Spec("%s",%d,%s)' % (c["kind"], c["attempts"], repr(c["meanNs"])) for c in data["classes"])
    harness = BASE_HARNESS[:BASE_HARNESS.index(" static long run(")].replace("public class Replay", "public class Integrated")
    harness = harness.replace("static boolean waits;", "static boolean waits;public static boolean legacy;public static long indexReads;")
    harness = harness.replace("@SPECS@", specs).replace("@TICKS@", str(data["ticks"])).replace("@CHECKED@", str(data["checked"])).replace("@SKIPPED@", str(data["skipped"])).replace("@ATTEMPTS@", str(data["attempts"]))
    sources["zombie/iso/Integrated.java"] = harness + RUNNER
    with tempfile.TemporaryDirectory(prefix="apocbr-integrated-iso-") as directory:
        work = Path(directory)
        paths = []
        for name, content in sources.items():
            p = work / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            paths.append(p)
        paths.extend(V / "src/zombie" / name for name in ("entity/ServerIsoEntityRegistry.java", "util/list/PZArrayList.java", "iso/ServerIsoObjectUpdateTelemetry.java"))
        arguments = work / "javac.args"
        arguments.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in paths))
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@" + str(arguments)], check=True)
        result = subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-Xms512m", "-Xmx2g", "-cp", str(work / "classes"), "zombie.iso.Integrated", str(args.repeats), args.wait, ",".join(map(str, sizes))], capture_output=True, text=True)
        print(result.stdout, end="")
        if result.stderr:
            print(result.stderr)
        result.check_returncode()
    samples = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(fixture=data, samples=samples,
        assumptions=dict(wait=args.wait, frameMod=10, occupancy=sizes, mutations="one processing object registered/edited/queried/restored each frame; separate persistent registered world mutations",
                         reference="legacy getter with dirty-ID invalidation support; same production registry in every variant",
                         timing="includes mutations, lifecycle, repair and packet-style assertions; initial trace/due-list construction excluded")), indent=2) + "\n", encoding="utf-8")


RUNNER = r"""
 static long validations,mutationNanos,processingNanos,processingReads;
 static long id(IsoObject o){int index=o.getObjectIndex();return index<0?-1L:((long)(o.floor?0:index)<<40)+((long)o.square.getZ()<<32)+((long)o.square.getY()<<16)+o.square.getX();}
 static void packet(IsoObject o){long key=id(o);if(zombie.entity.GameEntityManager.GetEntity(key)!=o)throw new AssertionError("packet lookup before ID getter");validations++;}
 static void edit(IsoObject o,int frame){
   IsoGridSquare sq=o.square;int index=o.getObjectIndex();IsoObject pad=new IsoObject();pad.setSquare(sq);
   if(frame%3==0){sq.objects.add(index,pad);packet(o);sq.objects.remove(index);packet(o);}
   else if(frame%3==1){IsoObject[] a=sq.objects.getElements();int other=(index+1)%sq.objects.size();IsoObject old=a[other];a[other]=o;a[index]=old;packet(o);a[index]=o;a[other]=old;packet(o);}
   else{int x=sq.getX();sq.setX(x+1);packet(o);sq.setX(x);packet(o);}
 }
 static void worldEdit(ArrayList<IsoObject> objects,int frame){
   IsoObject o=objects.get(frame%objects.size());IsoGridSquare sq=o.square;int index=o.getObjectIndex();
   if(frame%4==0){IsoObject pad=new IsoObject();pad.setSquare(sq);sq.objects.add(0,pad);for(IsoObject item:objects)packet(item);sq.objects.remove(0);}
   else if(frame%4==1){IsoObject other=objects.get((frame+1)%objects.size());int j=other.getObjectIndex();sq.objects.set(index,other);sq.objects.set(j,o);}
   else if(frame%4==2){IsoObject[] a=sq.objects.getElements();IsoObject other=objects.get((frame+1)%objects.size());int j=other.getObjectIndex();a[index]=other;a[j]=o;}
   else{zombie.entity.GameEntityManager.mockUnregister(o);sq.objects.remove(o);sq.objects.add(o);zombie.entity.GameEntityManager.mockRegister(o);}
   for(IsoObject item:objects)packet(item);
 }
 static void durableMutationTest(){
   legacy=false;waits=false;zombie.entity.GameEntityManager.mockReset();zombie.debug.DebugType.errors=0;
   IsoGridSquare sq=new IsoGridSquare(45000,45000,0);var objects=new ArrayList<IsoObject>();
   objects.add(create("IsoGenerator"));objects.add(create("IsoCompost"));objects.add(create("IsoThumpable"));
   for(IsoObject o:objects){o.setSquare(sq);sq.objects.add(o);zombie.entity.GameEntityManager.mockRegister(o);o.phase=IsoCell.phase(o);}
   IsoCell cell=new IsoCell();int phase=objects.get(1).phase;long actual=0,expected=0;
   for(int frame=0;frame<100;frame++){
     // These edits persist into processing and subsequent frames: unlike the paired
     // timing trace, the processing objects' network identities really do change.
     worldEdit(objects,frame);IsoWorld.instance.frame=frame;long before=calls,reads=indexReads;
     cell.run(objects,2);actual+=calls-before;expected+=1+(frame%10==phase?1:0);
     if(indexReads!=reads)throw new AssertionError("durable mutation triggered processing lookup");
     if(actual!=expected||objects.get(1).phase!=phase)throw new AssertionError("booked cadence changed with network identity");
   }
   for(IsoObject o:objects)zombie.entity.GameEntityManager.mockUnregister(o);zombie.entity.GameEntityManager.mockEmpty();
   if(zombie.debug.DebugType.errors!=0)throw new AssertionError("durable registry errors");
   System.out.println("Durable mutation check: 100 frames, stable booked cadence, zero processing index reads, registry assertions passed");
 }
 static long runIntegrated(Trace trace,int mode){
   legacy=mode==0;zombie.entity.GameEntityManager.mockReset();calls=hash=requestedWait=indexReads=validations=mutationNanos=processingNanos=processingReads=0;
   ApocBRServerTelemetryLite.counters.clear();ApocBRServerTelemetryLite.timings.clear();zombie.debug.DebugType.errors=0;IsoCell cell=new IsoCell();
   // Persistent mock player neighborhood: all four registered objects survive frames.
   IsoGridSquare neighborhood=new IsoGridSquare(50000,50000,0);var registered=new ArrayList<IsoObject>();
   for(int n=0;n<4;n++){IsoObject o=new IsoObject();o.setSquare(neighborhood);neighborhood.objects.add(o);zombie.entity.GameEntityManager.mockRegister(o);registered.add(o);}
   long start=System.nanoTime();
   for(int frame=0;frame<ticks;frame++){
     IsoWorld.instance.frame=frame;IsoObject target=trace.due.get(frame).get(0);long before=System.nanoTime();
     zombie.entity.GameEntityManager.mockRegister(target);edit(target,frame);worldEdit(registered,frame);mutationNanos+=System.nanoTime()-before;
     long reads=indexReads;before=System.nanoTime();cell.run((mode==3?trace.due:trace.all).get(frame),mode);processingNanos+=System.nanoTime()-before;processingReads+=indexReads-reads;
     before=System.nanoTime();zombie.entity.GameEntityManager.mockUnregister(target);mutationNanos+=System.nanoTime()-before;
   }
   for(IsoObject o:registered)zombie.entity.GameEntityManager.mockUnregister(o);long elapsed=System.nanoTime()-start;
   zombie.entity.GameEntityManager.mockEmpty();if(zombie.debug.DebugType.errors!=0)throw new AssertionError("registry errors");
   if(calls!=attempts||ApocBRServerTelemetryLite.counters.get("isoObjects.updateAttempts")!=attempts||ApocBRServerTelemetryLite.counters.get("isoObjects.skippedNoWork.IsoThumpable")!=skips)throw new AssertionError("aggregate count");
   if(mode<3&&ApocBRServerTelemetryLite.counters.get("isoObjects.checked")!=checks)throw new AssertionError("scan count");
   for(Spec spec:specs)if(ApocBRServerTelemetryLite.counters.getOrDefault("isoObjects.updateAttempts."+spec.kind,0L)!=spec.attempts)throw new AssertionError("family count");
   if(mode>=2&&processingReads!=0)throw new AssertionError("ID/index lookup leaked into processing");return elapsed;
 }
 public static void main(String[] args){int repeats=Integer.parseInt(args[0]);boolean requested=args[1].equals("spin");
   durableMutationTest();
   for(String size:args[2].split(","))for(boolean exposed:new boolean[]{false,true}){
     raw=exposed;zombie.entity.GameEntityManager.mockReset();legacy=false;Trace trace=make(Integer.parseInt(size),exposed);
     waits=false;for(int warm=0;warm<2;warm++)for(int mode=0;mode<4;mode++)runIntegrated(trace,mode);
     waits=requested;for(int repeat=0;repeat<repeats;repeat++){long referenceHash=0,referenceWait=0,referenceAssertions=0;boolean seen=false;
       for(int offset=0;offset<4;offset++){int mode=(repeat+offset)%4;long elapsed=runIntegrated(trace,mode);
         if(seen&&(hash!=referenceHash||requestedWait!=referenceWait||validations!=referenceAssertions))throw new AssertionError("order/work/mutation mismatch");
         referenceHash=hash;referenceWait=requestedWait;referenceAssertions=validations;seen=true;
         long body=ApocBRServerTelemetryLite.timings.entrySet().stream().filter(e->e.getKey().startsWith("simulation.isoObjects.update.")).mapToLong(e->e.getValue()).sum();
         System.out.printf(java.util.Locale.ROOT,"{\"squareSize\":%s,\"rawArrayExposed\":%s,\"variant\":\"%s\",\"repeat\":%d,\"elapsedMs\":%.6f,\"processingMs\":%.6f,\"mutationsMs\":%.6f,\"bodyMs\":%.6f,\"outsideBodyMs\":%.6f,\"requestedWaitMs\":%.6f,\"checked\":%d,\"updates\":%d,\"processingIndexReads\":%d,\"packetAssertions\":%d,\"registryChecked\":%d,\"registryRefreshed\":%d,\"registryMs\":%.6f}%n",size,exposed,new String[]{"legacy-reference","registry-reference","preassigned-phase","due-list"}[mode],repeat,elapsed/1e6,processingNanos/1e6,mutationNanos/1e6,body/1e6,(processingNanos-body)/1e6,requestedWait/1e6,ApocBRServerTelemetryLite.counters.get("isoObjects.checked"),calls,processingReads,validations,ApocBRServerTelemetryLite.counters.getOrDefault("entities.registry.checked",0L),ApocBRServerTelemetryLite.counters.getOrDefault("entities.registry.refreshed",0L),ApocBRServerTelemetryLite.timings.getOrDefault("entities.registry.reconcile",0L)/1e6);
       }
     }
   }
 }
}
"""


if __name__ == "__main__":
    main()
