"""Differential 42.22 chunk unload fixture using extracted production loops.

Engine doubles model lifecycle callbacks; this is not full-class compilation.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.22.0"


def block(text, signature):
    start = text.index(signature)
    pos = text.index("{", start)
    depth = 1
    end = pos + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def main():
    patched = (VERSION / "src/zombie/iso/IsoChunk.java").read_text()
    vanilla = (VERSION / "decompiled/zombie/iso/IsoChunk.java").read_text()
    signature = "for (int a = 0; a < mov.size(); a++)"
    old_loop, new_loop = (block(s, signature) for s in (vanilla, patched))
    removal = block((VERSION / "decompiled/zombie/iso/IsoMovingObject.java").read_text(),
                    "public void removeFromSquare()")
    java = r'''
import java.util.*;
public class ChunkFixture {
 static List<String> trace = new ArrayList<>(); static int checks;
 static void check(boolean b) { checks++; if(!b) throw new AssertionError("check "+checks); }
 static class Square {
  ArrayList<IsoMovingObject> moving=new ArrayList<>(), statics=new ArrayList<>();
  ArrayList<IsoMovingObject> getMovingObjects(){return moving;}
  ArrayList<IsoMovingObject> getStaticMovingObjects(){return statics;}
 }
 static class Parent {public void removeFromSquare(){}}
 static class IsoMovingObject extends Parent {
  int id; Square current,last,square; boolean earlyDetach;
  void setMovingSquare(Square s){} void setSquare(Square s){square=s;}
  void removeFromWorld(){trace.add("remove"+id);if(trace.size()>10000)throw new AssertionError("loop progress");if(earlyDetach)removeFromSquare();}
  void Despawn(){trace.add("despawn"+id);}
  REMOVAL
 }
 static class IsoSurvivor extends IsoMovingObject {}
 static class IsoAnimal extends IsoMovingObject {}
 static class BaseVehicle extends IsoMovingObject {boolean shouldUpdateInMeta(){return true;}}
 static class GameServer {static boolean server;}
 static class GameClient {static boolean client;}
 static class Cell {ArrayList<IsoMovingObject> survivors=new ArrayList<>();ArrayList<IsoMovingObject> getSurvivorList(){return survivors;}}
 static class IsoWorld {static IsoWorld instance=new IsoWorld();Cell currentCell=new Cell();}
 static class AnimalInstanceManager {static AnimalInstanceManager getInstance(){return new AnimalInstanceManager();}void remove(IsoAnimal a){trace.add("animal"+a.id);}}
 static class VehiclesDB2 {static VehiclesDB2 instance=new VehiclesDB2();void updateVehicle(BaseVehicle v){trace.add("db"+v.id);}}
 static class VirtualVehicleManager {static VirtualVehicleManager getInstance(){return new VirtualVehicleManager();}void addToMeta(BaseVehicle v){trace.add("meta"+v.id);}}
 ArrayList<BaseVehicle> vehicles=new ArrayList<>();
 void vanilla(Square sq){ArrayList<IsoMovingObject> mov=sq.moving;OLD_LOOP mov.clear();}
 void patched(Square sq){ArrayList<IsoMovingObject> mov=sq.moving;NEW_LOOP mov.clear();}
 static List<String> run(long seed,int mode,boolean patch) {
  Random r=new Random(seed);trace=new ArrayList<>();GameServer.server=mode==0;GameClient.client=mode==1;
  IsoWorld.instance=new IsoWorld();ChunkFixture f=new ChunkFixture();Square sq=new Square(),other=new Square();
  int count=r.nextInt(65);ArrayList<IsoMovingObject> all=new ArrayList<>();
  for(int i=0;i<count;i++) {
   IsoMovingObject o=switch(r.nextInt(4)){case 0->new IsoSurvivor();case 1->new IsoAnimal();case 2->new BaseVehicle();default->new IsoMovingObject();};
   o.id=i;o.square=sq;o.earlyDetach=r.nextBoolean();all.add(o);sq.moving.add(o);
   // Unique membership; test current, last, both, and stale membership with neither.
   int relation=r.nextInt(4);o.current=relation==0||relation==2?sq:other;o.last=relation==1||relation==2?sq:null;
   if(o.current==other)other.moving.add(o);
   if(o instanceof IsoSurvivor)IsoWorld.instance.currentCell.survivors.add(o);
   if(o instanceof BaseVehicle v)f.vehicles.add(v);
  }
  if(patch)f.patched(sq);else f.vanilla(sq);
  check(sq.moving.isEmpty());check(other.moving.isEmpty());
  for(IsoMovingObject o:all){check(o.current==null&&o.last==null&&o.square==null);}
  check(IsoWorld.instance.currentCell.survivors.isEmpty());
  check(f.vehicles.isEmpty() || mode!=2);
  return List.copyOf(trace);
 }
 public static void main(String[] args){
  for(int mode=0;mode<3;mode++)for(int seed=0;seed<2000;seed++)check(run(seed,mode,false).equals(run(seed,mode,true)));
  System.out.println("IsoChunk unload fixture: "+checks+" assertions passed (6000 differential scenarios)");
 }
}
'''
    java = java.replace("REMOVAL", removal).replace("OLD_LOOP", old_loop).replace("NEW_LOOP", new_loop)
    # Native persistence and obsolete reader APIs must not regress during this merge.
    assert "bb.putInt(251);" in patched and "worldVersion > 251" in patched
    assert "apocbrReadOnlyElements" not in patched and ".getElements()" not in patched
    assert "getVehicleWithSqlId(int sqlId)" in patched
    with tempfile.TemporaryDirectory(prefix="pz-chunk-fixture-") as tmp:
        target = Path(tmp) / "ChunkFixture.java"
        target.write_text(java, encoding="utf-8")
        subprocess.run(["javac", str(target)], check=True, cwd=tmp)
        subprocess.run(["java", "-cp", tmp, "ChunkFixture"], check=True, cwd=tmp)


if __name__ == "__main__":
    main()
