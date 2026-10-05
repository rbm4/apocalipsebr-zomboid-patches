"""Production-method differential fixtures for synchronous 42.21 parked-car work."""
from pathlib import Path
import subprocess
import tempfile
import re
from test_unload_simulation_algorithms import method

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.21.0"


def main():
    parts = (V / "src/zombie/vehicles/VehicleParts.java").read_text()
    part = (V / "src/zombie/vehicles/VehiclePart.java").read_text()
    vehicle = (V / "src/zombie/vehicles/BaseVehicle.java").read_text()
    vanilla_vehicle = (V / "decompiled/zombie/vehicles/BaseVehicle.java").read_text()
    idle = parts[parts.index("private int[] idleDeviceParts"):parts.index("public void setOwner")]
    sources = {
        "zombie/network/GameServer.java": "package zombie.network; public class GameServer {public static boolean server=true;}",
        "zombie/ApocBRServerTelemetryLite.java": "package zombie; public class ApocBRServerTelemetryLite {public static final java.util.Map<String,Long> counts=new java.util.HashMap<>();public static void count(String n,long v){counts.merge(n,v,Long::sum);}public static void recordPhase(String n,long v){} }",
        "zombie/scripting/objects/VehiclePart.java": 'package zombie.scripting.objects;public enum VehiclePart {BATTERY,ENGINE; public String toString(){return this==BATTERY?"Battery":"Engine";}}',
        "zombie/util/StringUtils.java": 'package zombie.util;public class StringUtils{public static boolean isNullOrWhitespace(String s){return s==null||s.isBlank();}}',
        "zombie/GameTime.java": "package zombie;public class GameTime {public static final GameTime instance=new GameTime();public static GameTime getInstance(){return instance;}public static float hours;public double getWorldAgeHours(){return hours;}public static float minHours(float a,float b){return Math.min(a,b);}" + method((V / "decompiled/zombie/GameTime.java").read_text(), "public static float checkHours(") + "}",
        "zombie/vehicles/VehiclePart.java": """package zombie.vehicles;public class VehiclePart {
          final String id;final BaseVehicle vehicle;DeviceData deviceData;VehicleLight light;ChatElement chatElement;
          float last=-1;boolean callback=true;int signals;float elapsedTotal;float temperature=200;
          Runnable mutation;public VehiclePart(BaseVehicle v,String id){vehicle=v;this.id=id;}
          public BaseVehicle getOwner(){return vehicle;}public String getId(){return id;}
          public VehicleLight getLight(){return light;}public DeviceData getDeviceData(){return deviceData;}
          public void updateSignalDevice(){signals++;}public Object getInventoryItem(){return this;}
          public void setLightActive(boolean a){light.active=a;}public String getLuaFunction(String s){return callback?id:null;}
          public float getLastUpdated(){return last;}public void setLastUpdated(float f){last=f;}
        """ + method(part, "public void createSpotLight(") + method(part, "public DeviceData createSignalDevice()") + method(part, "public void setDeviceData(").replace("@Override", "") + "}",
        "zombie/vehicles/DeviceData.java": "package zombie.vehicles;public class DeviceData {boolean on;DeviceData(VehiclePart p){}boolean getIsTurnedOn(){return on;}void cleanSoundsAndEmitter(){}void setParent(VehiclePart p){} }",
        "zombie/vehicles/ChatElement.java": "package zombie.vehicles;public class ChatElement{ChatElement(Object o,int n,String s){}}",
        "zombie/vehicles/VehicleLight.java": "package zombie.vehicles;public class VehicleLight{boolean active;float dist,intensity,dot;int focusing;public Vec offset=new Vec();boolean getActive(){return active;}static class Vec{void set(float x,float y,float z){}}}",
        "zombie/vehicles/VehicleParts.java": """package zombie.vehicles;import java.util.*;import zombie.GameTime;import zombie.util.StringUtils;
        public class VehicleParts {
          final BaseVehicle owner;final List<VehiclePart> parts=new ArrayList<>();final Map<String,VehiclePart> partsById=new HashMap<>();VehiclePart battery,engine;
          VehicleParts(BaseVehicle v){owner=v;}public BaseVehicle getOwner(){return owner;}public int size(){return parts.size();}public VehiclePart get(int i){return parts.get(i);}
          float getBatteryCharge(){return owner.charge;}public VehiclePart getBattery(){return battery;}
          void callLuaVoid(String f,BaseVehicle v,VehiclePart p,Object elapsed){
            p.elapsedTotal+=((Double)elapsed).floatValue();p.temperature=Math.max(0,p.temperature-2*((Double)elapsed).floatValue());
          }
        """ + idle + method(parts, "public void add(") + method(parts, "public void clear()") + method(parts, "public boolean updatePart(") + "}",
    }
    # Use the production selector directly, with a callback stub that exposes list mutation.
    sources["zombie/vehicles/VehicleParts.java"] = sources["zombie/vehicles/VehicleParts.java"].replace(
        "public boolean updatePart(VehiclePart part) {", "public boolean minuteUpdatePart(VehiclePart part) {")
    sources["zombie/vehicles/VehicleParts.java"] = sources["zombie/vehicles/VehicleParts.java"][:-1] + """
        public boolean updatePart(VehiclePart p){owner.log.add(p.id);if(p.mutation!=null){Runnable action=p.mutation;p.mutation=null;action.run();}return true;}
        }"""
    sources["zombie/vehicles/BaseVehicle.java"] = """package zombie.vehicles;import java.util.*;import zombie.network.GameServer;
      public class BaseVehicle {
        final VehicleParts parts=new VehicleParts(this);final ArrayList<String> log=new ArrayList<>();boolean running,lightbar;float charge=1;
        final Mode lightbarLightsMode=new Mode(),lightbarSirenMode=new Mode();static class Mode{boolean enabled;boolean isEnable(){return enabled;}}
        boolean isEngineRunning(){return running;}VehicleParts getParts(){return parts;}boolean hasLightbar(){return lightbar;}
        VehiclePart getBattery(){return parts.getBattery();}boolean getHeadlightsOn(){return false;}
      """ + method(vehicle, "public void drainBatteryUpdateHack()") + method(vanilla_vehicle, "public void drainBatteryUpdateHack()").replace("drainBatteryUpdateHack", "vanillaDrain") + "}"

    # Execute the actual square-selection statements against mutable per-call world contents.
    def squares(text, name):
        update = method(text, "public void update()")
        start = update.index("int zi = PZMath.fastfloor(this.jniTransform.origin.y")
        end = update.index("if (sq == null &&", start)
        return "public IsoGridSquare " + name + "(){setZ(0);" + update[start:end] + "return sq;}"

    sources["zombie/vehicles/SquareTest.java"] = """package zombie.vehicles;import java.util.*;import zombie.network.GameServer;
      public class SquareTest {
        static class PZMath{static int fastfloor(float v){return (int)Math.floor(v);}}
        static class IsoGridSquare{boolean floor;IsoGridSquare(boolean f){floor=f;}Object getFloor(){return floor?this:null;}}
        static class Transform{final Vec origin=new Vec();}static class Vec{float y;}
        final Transform jniTransform=new Transform();float z;int queries;final Map<Integer,IsoGridSquare> grid=new HashMap<>();
        float getX(){return 1;}float getY(){return 2;}float getZ(){return z;}void setZ(float f){z=f;}SquareTest getCell(){return this;}
        IsoGridSquare getGridSquare(double x,double y,double z){queries++;return grid.get((int)z);}
      """ + squares(vehicle, "optimized") + squares(vanilla_vehicle, "vanilla") + """
        static int run(){int checks=0;Random r=new Random(5021);SquareTest a=new SquareTest(),b=new SquareTest();
          for(int i=0;i<20000;i++){a.grid.clear();b.grid.clear();a.queries=b.queries=0;
            for(int j=-3;j<=5;j++)if(r.nextBoolean()){IsoGridSquare s=new IsoGridSquare(r.nextBoolean());a.grid.put(j,s);b.grid.put(j,s);}
            a.jniTransform.origin.y=b.jniTransform.origin.y=r.nextFloat()*17-5;
            IsoGridSquare x=a.optimized(),y=b.vanilla();if(x!=y||a.z!=b.z||a.queries>b.queries)throw new AssertionError("square reconciliation");checks++;
          }return checks;}
      }"""
    sources["zombie/vehicles/IdleTest.java"] = HARNESS
    # Model index construction is a per-pass first-identity view, including duplicate/null keys.
    transform = method(vehicle, "protected void updateTransform()")
    index_block = method(transform, "if (GameServer.server && modelParts == null)")
    index_code = ("java.util.IdentityHashMap<VehiclePart, BaseVehicle.ModelInfo> modelParts = null;" + index_block).replace("this.models", "models").replace("this.serverModelParts", "serverModelParts")
    sources["zombie/vehicles/ModelTest.java"] = """package zombie.vehicles;import java.util.*;import zombie.network.GameServer;
      public class ModelTest {static class BaseVehicle{static class ModelInfo{VehiclePart part;ModelInfo(VehiclePart p){part=p;}}}
        IdentityHashMap<VehiclePart,BaseVehicle.ModelInfo> serverModelParts;
        IdentityHashMap<VehiclePart,BaseVehicle.ModelInfo> build(List<BaseVehicle.ModelInfo> models){""" + index_code + "return modelParts;}" + """
        static int run(){ModelTest t=new ModelTest();Random r=new Random(102);VehiclePart[] parts=new VehiclePart[20];for(int i=0;i<20;i++)parts[i]=new VehiclePart(null,"p"+i);
          int checks=0;for(int n=0;n<1000;n++){ArrayList<BaseVehicle.ModelInfo> models=new ArrayList<>();for(int i=0;i<100;i++)models.add(new BaseVehicle.ModelInfo(r.nextInt(4)==0?null:parts[r.nextInt(20)]));
            var index=t.build(models);for(VehiclePart p:parts){BaseVehicle.ModelInfo first=null;for(var m:models)if(m.part==p){first=m;break;}if(index.get(p)!=first)throw new AssertionError("first identity");checks++;}
          }return checks;}
      }"""
    # Outside the index lookup, the matrix calculations retain vanilla statements.
    stripped = transform.replace(index_block, "").replace("java.util.IdentityHashMap<VehiclePart, BaseVehicle.ModelInfo> modelParts = null;", "")
    stripped = stripped.replace("BaseVehicle.ModelInfo parentModelInfo = modelParts == null\n                            ? this.getModelInfoForPart(modelInfo.part.getParent()) : modelParts.get(modelInfo.part.getParent());",
                                "BaseVehicle.ModelInfo parentModelInfo = this.getModelInfoForPart(modelInfo.part.getParent());")
    stripped = stripped.replace("if (modelParts != null) modelParts.clear();", "")
    assert re.sub(r"\s+", "", stripped) == re.sub(r"\s+", "", method(vanilla_vehicle, "protected void updateTransform()"))
    sound = method(vehicle, "public void updateSounds()")
    sound = sound.replace("// All remaining server work in this method requires an existing emitter.", "").replace("if (GameServer.server && this.emitter == null) return;", "")
    assert re.sub(r"\s+", "", sound) == re.sub(r"\s+", "", method(vanilla_vehicle, "public void updateSounds()"))
    # Validate every added capability hook; the rest of VehiclePart remains exact vanilla.
    original_part = (V / "decompiled/zombie/vehicles/VehiclePart.java").read_text()
    unhooked = part.replace("if (this.light == null) this.getOwner().getParts().invalidateIdleDeviceParts();", "")
    unhooked = unhooked.replace("this.getOwner().getParts().invalidateIdleDeviceParts();", "")
    assert re.sub(r"\s+", "", unhooked) == re.sub(r"\s+", "", original_part)
    with tempfile.TemporaryDirectory(prefix="apocbr-vehicle-tests-") as directory:
        work = Path(directory)
        paths = []
        for name, content in sources.items():
            p = work / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            paths.append(p)
        paths.append(V / "src/zombie/vehicles/ServerVehicleUpdateTelemetry.java")
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in paths))
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(work / "classes"), "zombie.vehicles.IdleTest"], check=True)


HARNESS = r"""package zombie.vehicles;import java.util.*;import zombie.GameTime;import zombie.network.GameServer;import zombie.ApocBRServerTelemetryLite;
public class IdleTest {
  static int checks;static void check(boolean ok){if(!ok)throw new AssertionError();checks++;}
  static VehiclePart add(BaseVehicle v,String id,boolean device,boolean light){VehiclePart p=new VehiclePart(v,id);if(device)p.createSignalDevice();if(light)p.createSpotLight(0,0,1,1,1,0);v.parts.add(p);return p;}
  static void compare(BaseVehicle a,BaseVehicle b){a.log.clear();b.log.clear();a.drainBatteryUpdateHack();b.vanillaDrain();check(a.log.equals(b.log));}
  public static void main(String[] args){Random r=new Random(90051);
    for(int run=0;run<100;run++){BaseVehicle a=new BaseVehicle(),b=new BaseVehicle();ArrayList<VehiclePart> x=new ArrayList<>(),y=new ArrayList<>();
      for(int i=0;i<100;i++){boolean d=r.nextInt(20)==0,l=r.nextInt(20)==0;x.add(add(a,"p"+i,d,l));y.add(add(b,"p"+i,d,l));}
      for(int step=0;step<200;step++){int i=r.nextInt(x.size());VehiclePart p=x.get(i),q=y.get(i);boolean active=r.nextBoolean();
        switch(r.nextInt(7)){case 0:p.createSignalDevice();q.createSignalDevice();break;case 1:p.createSpotLight(0,0,1,1,1,0);q.createSpotLight(0,0,1,1,1,0);break;
          case 2:p.setDeviceData(null);q.setDeviceData(null);break;case 3:if(p.light!=null)p.light.active=q.light.active=active;break;
          case 4:if(p.deviceData!=null)p.deviceData.on=q.deviceData.on=active;break;case 5:a.running=b.running=active;break;
          case 6:if(x.size()<140){x.add(add(a,"new"+step,true,true));y.add(add(b,"new"+step,true,true));}break;}
        compare(a,b);
      }
    }
    // Callback creates a candidate on a later part, turns off another, and appends one.
    BaseVehicle a=new BaseVehicle(),b=new BaseVehicle();VehiclePart ax=add(a,"first",true,false),bx=add(b,"first",true,false);
    VehiclePart ay=add(a,"later",false,false),by=add(b,"later",false,false);ax.deviceData.on=bx.deviceData.on=true;compare(a,b);
    ax.mutation=()->{ay.createSignalDevice().on=true;add(a,"appended",true,false).deviceData.on=true;};
    bx.mutation=()->{by.createSignalDevice().on=true;add(b,"appended",true,false).deviceData.on=true;};compare(a,b);check(a.log.equals(List.of("first","later","appended")));
    // Clear/rebuild during callback follows the original incremented numeric cursor.
    ax.mutation=()->{a.parts.clear();add(a,"replacement0",true,false).deviceData.on=true;add(a,"replacement1",true,false).deviceData.on=true;};
    bx.mutation=()->{b.parts.clear();add(b,"replacement0",true,false).deviceData.on=true;add(b,"replacement1",true,false).deviceData.on=true;};compare(a,b);check(a.log.equals(List.of("first","replacement1")));
    // Duplicated part identities and lightbar battery double visitation remain ordered.
    a.parts.add(a.parts.get(1));b.parts.add(b.parts.get(1));a.lightbar=b.lightbar=true;a.lightbarLightsMode.enabled=b.lightbarLightsMode.enabled=true;
    VehiclePart ab=add(a,"Battery",true,true),bb=add(b,"Battery",true,true);ab.deviceData.on=bb.deviceData.on=true;compare(a,b);
    check(Collections.frequency(a.log,"Battery")==2);
    // Nested update rebuilds the shared view before the outer callback resumes.
    BaseVehicle nestedA=new BaseVehicle(),nestedB=new BaseVehicle();VehiclePart na=add(nestedA,"first",true,false),nb=add(nestedB,"first",true,false);
    VehiclePart laterA=add(nestedA,"later",false,false),laterB=add(nestedB,"later",false,false);na.deviceData.on=nb.deviceData.on=true;compare(nestedA,nestedB);
    na.mutation=()->{laterA.createSignalDevice().on=true;nestedA.drainBatteryUpdateHack();};nb.mutation=()->{laterB.createSignalDevice().on=true;nestedB.vanillaDrain();};compare(nestedA,nestedB);
    check(nestedA.log.equals(List.of("first","first","later","later")));
    // Client branch still uses the original list traversal.
    GameServer.server=false;compare(a,b);GameServer.server=true;
    // Mutation/exception recovery: next call must reconcile a capability created by the failed callback.
    VehiclePart throwing=a.parts.get(0);throwing.mutation=()->{add(a,"afterThrow",true,false).deviceData.on=true;throw new IllegalStateException();};
    try{a.drainBatteryUpdateHack();throw new AssertionError();}catch(IllegalStateException expected){}a.log.clear();a.drainBatteryUpdateHack();check(a.log.contains("afterThrow"));
    // No-capability parked cars do zero candidate visits after their one-time build.
    BaseVehicle empty=new BaseVehicle();for(int i=0;i<1000;i++)add(empty,"plain"+i,false,false);empty.drainBatteryUpdateHack();
    long entries=ApocBRServerTelemetryLite.counts.get("vehicles.idleParts.rebuildEntries");long candidates=ApocBRServerTelemetryLite.counts.get("vehicles.idleParts.candidatesChecked");
    for(int i=0;i<1000;i++)empty.drainBatteryUpdateHack();check(entries==ApocBRServerTelemetryLite.counts.get("vehicles.idleParts.rebuildEntries"));check(candidates==ApocBRServerTelemetryLite.counts.get("vehicles.idleParts.candidatesChecked"));
    // Cooling uses the unchanged production minute gate and accumulated elapsed minutes.
    BaseVehicle full=new BaseVehicle(),slow=new BaseVehicle();VehiclePart f=add(full,"Engine",false,false),s=add(slow,"Engine",false,false);
    for(int tick=0;tick<=16000;tick++){GameTime.hours=tick/6000f;full.parts.minuteUpdatePart(f);if(tick%16==0)slow.parts.minuteUpdatePart(s);}
    check(Math.abs(f.elapsedTotal-s.elapsedTotal)<1.1f);check(f.temperature==0&&s.temperature==0);
    // New work counters retain nested-batch and direct-call semantics.
    long before=ApocBRServerTelemetryLite.counts.getOrDefault("vehicles.squareQueries.reused",0L);
    ServerVehicleUpdateTelemetry.beginBatch();ServerVehicleUpdateTelemetry.countWork(4,5);ServerVehicleUpdateTelemetry.beginBatch();ServerVehicleUpdateTelemetry.countWork(4,7);
    ServerVehicleUpdateTelemetry.endBatch();check(ApocBRServerTelemetryLite.counts.getOrDefault("vehicles.squareQueries.reused",0L)==before);
    ServerVehicleUpdateTelemetry.endBatch();check(ApocBRServerTelemetryLite.counts.get("vehicles.squareQueries.reused")==before+12);
    checks+=SquareTest.run()+ModelTest.run();System.out.println("Vehicle idle algorithms: "+checks+" assertions");
  }
}
"""


if __name__ == "__main__":
    main()
