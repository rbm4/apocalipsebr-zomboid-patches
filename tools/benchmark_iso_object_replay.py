"""42.21 aggregate IsoObject replay; synthetic waits, not a server performance test."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.21.0"


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def fixture(snapshot):
    if "classes" in snapshot:
        return snapshot
    if snapshot["gameVersion"] != "42.21" or snapshot["schemaVersion"] != 3:
        raise ValueError("Expected a schema 3, 42.21 telemetry record")
    counters = snapshot["counters"]
    phase = snapshot["phases"]["simulation"]["isoObjects"]
    classes = []
    for kind, value in phase.get("update", {}).items():
        attempts = counters["isoObjects.updateAttempts." + kind]
        if attempts:
            classes.append(dict(kind=kind, attempts=attempts, totalMs=value["totalMs"],
                                meanNs=value["totalMs"] * 1_000_000 / attempts))
    result = dict(version="42.21.0", seq=snapshot["seq"], ticks=snapshot["tick"]["count"],
                  checked=counters["isoObjects.checked"], attempts=counters["isoObjects.updateAttempts"],
                  skipped=counters["isoObjects.skippedNoWork.IsoThumpable"],
                  always=counters["isoObjects.alwaysUpdateAttempts"], parentMs=phase["totalMs"], classes=classes)
    if sum(c["attempts"] for c in classes) != result["attempts"]:
        raise ValueError("Per-class attempt coverage does not match parent")
    if sum(c["attempts"] for c in classes if c["kind"] in ("IsoTrap", "IsoGenerator")) != result["always"]:
        raise ValueError("Every-tick attempt coverage does not match")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="One telemetry JSON snapshot or saved aggregate fixture")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--square-sizes", default="4,32,128", help="Synthetic square occupancies, NOT telemetry measurements")
    parser.add_argument("--wait", choices=("spin", "none"), default="spin")
    parser.add_argument("--save-fixture", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    data = fixture(json.loads(args.input.read_text(encoding="utf-8-sig")))
    sizes = [int(s) for s in args.square_sizes.split(",")]
    if args.repeats < 1 or not sizes or min(sizes) < 1:
        parser.error("Positive repeats and square sizes required")
    if args.save_fixture:
        args.save_fixture.parent.mkdir(parents=True, exist_ok=True)
        args.save_fixture.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    cell_source = (VERSION / "src/zombie/iso/IsoCell.java").read_text()
    object_source = (VERSION / "decompiled/zombie/iso/IsoObject.java").read_text()
    process = method(cell_source, "private void ProcessIsoObject()")
    cached = process.replace("ProcessIsoObject", "ProcessCachedPhase").replace("getIsoObjectUpdatePhase(i, mod)", "i.phase")
    sources = {
        "zombie/entity/ServerIsoEntityRegistry.java": "package zombie.entity;public class ServerIsoEntityRegistry{public static void listChanged(Object list){}public static void listExposed(Object list){}}",
        "zombie/network/GameServer.java": "package zombie.network;public class GameServer{public static boolean server=true;}",
        "zombie/UsedFromLua.java": "package zombie;public @interface UsedFromLua{}",
        "zombie/util/lambda/Invokers.java": "package zombie.util.lambda;public class Invokers{public static class Params2{public static class Boolean{public interface ICallback<A,B>{boolean accept(A a,B b);}}}}",
        "zombie/GameTime.java": "package zombie;public class GameTime{public static final GameTime instance=new GameTime();public static GameTime getInstance(){return instance;}public float perObjectMultiplier=1;public double getWorldAgeHours(){return 0;}}",
        "zombie/ApocBRServerTelemetryLite.java": """package zombie;public class ApocBRServerTelemetryLite{
          public static final java.util.Map<String,Long> counters=new java.util.HashMap<>(),timings=new java.util.HashMap<>();
          public static void count(String k,long v){counters.merge(k,v,Long::sum);}public static void recordPhase(String k,long n){timings.merge(k,n,Long::sum);}
        }""",
        "zombie/entity/GameEntityManager.java": "package zombie.entity;public class GameEntityManager{public static void checkEntityIDChange(Object o,long a,long b){zombie.iso.Replay.reconciliations++;}}",
        "zombie/iso/IsoWorld.java": "package zombie.iso;public class IsoWorld{public static final IsoWorld instance=new IsoWorld();public int frame;public int getFrameNo(){return frame;}}",
        "zombie/iso/IsoGridSquare.java": """package zombie.iso;import zombie.util.list.PZArrayList;
          public class IsoGridSquare{final PZArrayList<IsoObject> objects=new PZArrayList<>(IsoObject.class,2);final int x;
          IsoGridSquare(int x){this.x=x;}public PZArrayList<IsoObject> getObjects(){return objects;}public int getX(){return x;}public int getY(){return 10;}public int getZ(){return 0;}}
        """,
        "zombie/iso/IsoObject.java": """package zombie.iso;import zombie.entity.GameEntityManager;
          public class IsoObject{IsoGridSquare square;long isoEntityNetId=-1;int lastObjectIndex=-1;
          int phase,kind,ordinal;long duration;boolean always;public boolean isFloor(){return false;}
          public void update(){Replay.calls++;Replay.hash=Replay.hash*31+ordinal;Replay.requestedWait+=duration;
            if(Replay.waits&&duration>0){long until=System.nanoTime()+duration;while(System.nanoTime()<until)Thread.onSpinWait();}}
        """ + method(object_source, "public int getObjectIndex()").replace("public int getObjectIndex() {", "public int getObjectIndex() { Replay.objectIndexLookups++;")
        + method(object_source, "public long getEntityNetID()") + "}",
        "zombie/iso/IsoCell.java": """package zombie.iso;import java.util.*;import zombie.GameTime;import zombie.network.GameServer;import zombie.ApocBRServerTelemetryLite;import zombie.iso.objects.*;
          public class IsoCell{ArrayList<IsoObject> processIsoObject=new ArrayList<>();final Set<IsoObject> processIsoObjectSet=new HashSet<>(),processIsoObjectRemove=new HashSet<>();
          private static final int APOC_BR_PROCESS_ISO_OBJECT_FRAME_MOD=10;
        """ + method(cell_source, "private static int getIsoObjectUpdatePhase(") + process + cached + """
          void run(ArrayList<IsoObject> list,int mode){processIsoObject=list;if(mode==0)ProcessIsoObject();else ProcessCachedPhase();}
          static int phase(IsoObject o){return getIsoObjectUpdatePhase(o,10);}
        }""",
    }
    for c in data["classes"]:
        name = c["kind"]
        if name != "other":
            sources["zombie/iso/objects/" + name + ".java"] = "package zombie.iso.objects;public class " + name + " extends zombie.iso.IsoObject{}"
    for name in ("IsoTrap", "IsoGenerator"):
        sources.setdefault("zombie/iso/objects/" + name + ".java", "package zombie.iso.objects;public class " + name + " extends zombie.iso.IsoObject{}")
    thump = (VERSION / "src/zombie/iso/objects/IsoThumpable.java").read_text()
    sources["zombie/iso/objects/IsoThumpable.java"] = "package zombie.iso.objects;import zombie.GameTime;public class IsoThumpable extends zombie.iso.IsoObject{float updateAccumulator,lastUpdateHours=-1;float getLifeLeft(){return -1;}boolean isLightSourceOn(){return false;}" + method(thump, "public boolean apocbrNeedsServerUpdate()") + "}"
    declarations = []
    for c in data["classes"]:
        declarations.append('new Spec("%s",%d,%s)' % (c["kind"], c["attempts"], repr(c["meanNs"])))
    source = HARNESS.replace("@SPECS@", ",".join(declarations)).replace("@TICKS@", str(data["ticks"]))
    source = source.replace("@CHECKED@", str(data["checked"])).replace("@SKIPPED@", str(data["skipped"])).replace("@ATTEMPTS@", str(data["attempts"]))
    sources["zombie/iso/Replay.java"] = source
    with tempfile.TemporaryDirectory(prefix="apocbr-iso-replay-") as directory:
        work = Path(directory)
        paths = []
        for name, content in sources.items():
            p = work / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            paths.append(p)
        paths.extend(VERSION / "src/zombie" / n for n in ("util/list/PZArrayList.java", "iso/ServerIsoObjectUpdateTelemetry.java"))
        javac_args = work / "javac.args"
        javac_args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in paths))
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@" + str(javac_args)], check=True)
        result = subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-Xms512m", "-Xmx2g", "-cp", str(work / "classes"), "zombie.iso.Replay", str(args.repeats), args.wait, ",".join(map(str, sizes))], capture_output=True, text=True)
        print(result.stdout, end="")
        if result.stderr:
            print(result.stderr)
        result.check_returncode()
        report = dict(fixture=data, assumptions=dict(wait=args.wait, distribution="uniform mean +/-20%, seed 160", squareSizes=sizes,
                     frameMod=10, registry="ID registry side effects counted by a stub; not a live registry or world replay",
                     topology="synthetic stable square lists; per-frame aggregate counts, not recorded object histories",
                     alternatives="preassigned phases and due lists; construction and live mutation maintenance excluded"),
                     samples=[json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")])
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


HARNESS = r"""package zombie.iso;import java.util.*;import zombie.ApocBRServerTelemetryLite;
public class Replay{
 static record Spec(String kind,int attempts,double meanNs){}
 static final Spec[] specs={@SPECS@};static final int ticks=@TICKS@,checks=@CHECKED@,skips=@SKIPPED@,attempts=@ATTEMPTS@;
 public static long reconciliations;static long calls,hash,requestedWait,objectIndexLookups;static boolean waits;
 static int portion(int total,int frame){return total/ticks+(frame<total%ticks?1:0);}
 static IsoObject create(String kind){try{return kind.equals("other")?new IsoObject():(IsoObject)Class.forName("zombie.iso.objects."+kind).getConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
 static record Trace(ArrayList<ArrayList<IsoObject>> all,ArrayList<ArrayList<IsoObject>> due){}
 static Trace make(int occupancy,boolean exposed){Random r=new Random(160);var all=new ArrayList<ArrayList<IsoObject>>();var due=new ArrayList<ArrayList<IsoObject>>();
   var pools=new ArrayList<ArrayList<IsoObject>>();for(int phase=0;phase<10;phase++)pools.add(new ArrayList<>());
   // Attach objects to synthetic squares and obtain phases through the real vanilla ID getter.
   // Square lists are stable during each timed replay; raw-array exposure is a separate case.
   int squareNumber=0;
   while(pools.stream().anyMatch(p->p.size()<700)){
     IsoGridSquare sq=new IsoGridSquare(squareNumber++);var entries=new ArrayList<IsoObject>();
     for(int j=0;j<occupancy;j++){IsoObject o=new IsoObject();o.square=sq;sq.objects.add(o);entries.add(o);}
     if(exposed)sq.objects.getElements();
     for(IsoObject o:entries){o.phase=IsoCell.phase(o);pools.get(o.phase).add(o);}
   }
   int ordinal=0;
   for(int frame=0;frame<ticks;frame++){
     var a=new ArrayList<IsoObject>();var d=new ArrayList<IsoObject>();int phase=frame%10;
     for(int kind=0;kind<specs.length;kind++){Spec spec=specs[kind];for(int n=0;n<portion(spec.attempts,frame);n++){
       IsoObject o=create(spec.kind);o.kind=kind;o.ordinal=ordinal++;o.duration=Math.max(0,Math.round(spec.meanNs*(0.8+r.nextDouble()*0.4)));o.always=spec.kind.equals("IsoTrap")||spec.kind.equals("IsoGenerator");
       attachLike(o,pools.get(phase).get(r.nextInt(pools.get(phase).size())));o.phase=phase;a.add(o);d.add(o);
     }}
     for(int n=0;n<portion(skips,frame);n++){IsoObject o=create("IsoThumpable");attachLike(o,pools.get(phase).get(r.nextInt(pools.get(phase).size())));o.phase=phase;o.ordinal=ordinal++;a.add(o);d.add(o);}
     while(a.size()<portion(checks,frame)){int other=(phase+1+r.nextInt(9))%10;IsoObject o=pools.get(other).get(r.nextInt(pools.get(other).size()));a.add(o);}
     if(a.size()!=portion(checks,frame))throw new AssertionError("incompatible aggregate counts");
     // Interleave due and non-due entries without changing the selected object's relative order.
     Collections.shuffle(a,r);d.clear();for(IsoObject o:a)if(o.always||o.phase==phase)d.add(o);
     all.add(a);due.add(d);
   }return new Trace(all,due);
 }
 static void attachLike(IsoObject replacement,IsoObject original){
   // Replace a square entry with a concrete family while preserving its ID and ordinal.
   // Use a private copy of the square to avoid changing another replay object's phase.
   IsoGridSquare sq=new IsoGridSquare(original.square.x);for(int n=0;n<original.square.objects.size();n++)sq.objects.add(original.square.objects.get(n));
   int index=original.getObjectIndex();sq.objects.set(index,replacement);replacement.square=sq;
   // The template records whether this scenario exposes backing arrays.
   if(raw)sq.objects.getElements();
   replacement.getEntityNetID();
 }
 static boolean raw;
 static long run(Trace trace,int mode){calls=hash=requestedWait=reconciliations=objectIndexLookups=0;ApocBRServerTelemetryLite.counters.clear();ApocBRServerTelemetryLite.timings.clear();IsoCell cell=new IsoCell();
   long start=System.nanoTime();for(int f=0;f<ticks;f++){IsoWorld.instance.frame=f;cell.run((mode==2?trace.due:trace.all).get(f),mode);}long elapsed=System.nanoTime()-start;
   if(calls!=attempts||ApocBRServerTelemetryLite.counters.get("isoObjects.updateAttempts")!=attempts||ApocBRServerTelemetryLite.counters.get("isoObjects.skippedNoWork.IsoThumpable")!=skips)throw new AssertionError("call counts differ");
   if(mode!=2&&ApocBRServerTelemetryLite.counters.get("isoObjects.checked")!=checks)throw new AssertionError("scan count differs");
   for(Spec spec:specs)if(ApocBRServerTelemetryLite.counters.getOrDefault("isoObjects.updateAttempts."+spec.kind,0L)!=spec.attempts)throw new AssertionError("class count differs: "+spec.kind);
   return elapsed;
 }
 public static void main(String[] args){int repeats=Integer.parseInt(args[0]);boolean requested=args[1].equals("spin");
   // Warm all paths before reporting; rotate the measured variant order each repetition.
   for(String size:args[2].split(","))for(boolean exposed:new boolean[]{false,true}){raw=exposed;Trace trace=make(Integer.parseInt(size),exposed);waits=false;for(int w=0;w<2;w++)for(int mode=0;mode<3;mode++)run(trace,mode);
     waits=requested;for(int repeat=0;repeat<repeats;repeat++){long expectedHash=0,expectedWait=0;boolean haveExpected=false;
       for(int offset=0;offset<3;offset++){int mode=(repeat+offset)%3;long elapsed=run(trace,mode);
         if(haveExpected&&(hash!=expectedHash||requestedWait!=expectedWait))throw new AssertionError("order/wait mismatch");expectedHash=hash;expectedWait=requestedWait;haveExpected=true;
         long body=ApocBRServerTelemetryLite.timings.values().stream().mapToLong(Long::longValue).sum();
         System.out.printf(java.util.Locale.ROOT,"{\"squareSize\":%s,\"rawArrayExposed\":%s,\"variant\":\"%s\",\"repeat\":%d,\"elapsedMs\":%.6f,\"bodyMs\":%.6f,\"outsideBodyMs\":%.6f,\"requestedWaitMs\":%.6f,\"checked\":%d,\"updates\":%d,\"objectIndexLookups\":%d,\"idReconciliations\":%d}%n",size,exposed,new String[]{"current","preassigned-phase","due-list"}[mode],repeat,elapsed/1e6,body/1e6,(elapsed-body)/1e6,requestedWait/1e6,ApocBRServerTelemetryLite.counters.get("isoObjects.checked"),calls,objectIndexLookups,reconciliations);
       }
     }
   }
 }
}
"""


if __name__ == "__main__":
    main()
