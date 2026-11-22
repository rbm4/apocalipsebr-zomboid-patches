"""Production reconciliation/planner differential tests with explicit engine doubles.
No renderer/native game is started. Geometry, ID publication and lifetime ordering
execute the original/patched class bodies rather than a mirrored reconciler.
"""
import json
import pathlib
import re
import subprocess
import tempfile
import unittest
from test_network_telemetry import method

ROOT=pathlib.Path(__file__).resolve().parents[1]
JDK=ROOT.parent/'jdk/bin'


def body(path, old, new):
    text=path.read_text()
    text='\n'.join(line for line in text.splitlines() if not line.startswith(('package ', 'import ')))
    return text.replace(old,new,1)


class BuildingReconciliationTest(unittest.TestCase):
    def test_geometry_commit_and_retirement(self):
        original=ROOT/'decompiled/zombie/iso/areas/isoregion/metagrid/WorldRegionToMetaGrid.java'
        patched=ROOT/'src/zombie/iso/areas/isoregion/metagrid/WorldRegionToMetaGrid.java'
        doubles=(ROOT/'tests/fixtures/BuildingEngineDoubles.java').read_text()
        root_code=(ROOT/'src/zombie/iso/areas/isoregion/data/DataRoot.java').read_text()
        collect=method(root_code,'public ArrayList<IsoWorldRegion> getIsoWorldRegionsInCell(')
        original_collect=method((ROOT/'decompiled/zombie/iso/areas/isoregion/data/DataRoot.java').read_text(),'public ArrayList<IsoWorldRegion> getIsoWorldRegionsInCell(')
        doubles=doubles.replace('ORIGINAL_COLLECT_METHOD',original_collect.replace('getIsoWorldRegionsInCell','originalCollect',1).replace('{','{\ncollects++;',1))
        doubles=doubles.replace('COLLECT_METHOD',collect.replace('{','{\ncollects++;',1))
        for token,signature in [('PREPARE_METHOD','public void prepareClientBuildings('),('CONSUME_METHOD','public void clientProcessBuildings()')]:
            doubles=doubles.replace(token,method(root_code,signature).replace('zombie.iso.areas.isoregion.metagrid.ApocBRBuildingPlan','ApocBRBuildingPlan'))
        room=(ROOT.parent/'42.22.2/decompiled/zombie/iso/RoomDef.java').read_text()
        building=(ROOT/'src/zombie/iso/BuildingDef.java').read_text()
        for token,sig in [('ROOM_BOUNDS','public void CalculateBounds('),('ROOM_META','public long calculateMetaID(')]:doubles=doubles.replace(token,method(room,sig))
        original_bounds=method((ROOT/'decompiled/zombie/iso/BuildingDef.java').read_text(),'public void CalculateBounds(')
        doubles=doubles.replace('ORIGINAL_BUILDING_BOUNDS',original_bounds.replace('CalculateBounds','OriginalBounds',1))
        for token,sig in [('BUILDING_BOUNDS','public void CalculateBounds('),('BUILDING_OVERLAPS_CHUNK','public boolean overlapsChunk('),('BUILDING_META','public long calculateMetaID('),('BUILDING_ADD_ROOMS','public void addRoomsOf('),('BUILDING_ROOF_GET','public long getRoofRoomID('),('BUILDING_ROOF_INVALIDATE','public void apocInvalidateRoofRoomIDs(')]:
            value=method(building,sig)
            if token=='BUILDING_BOUNDS':value=value.replace('{','{\nif(BuildingHarness.originalGeometry){OriginalBounds(tempRooms);return;}',1)
            doubles=doubles.replace(token,value)
        source='import java.util.*;import java.util.function.*;import java.util.concurrent.*;\n'+doubles+'\n'
        source+=body(original,'public final class WorldRegionToMetaGrid','class OriginalReconciler')+'\n'
        source+=body(patched,'public final class WorldRegionToMetaGrid','class NewReconciler').replace('WorldRegionToMetaGrid','NewReconciler')+'\n'
        source+=body(ROOT/'src/zombie/iso/areas/isoregion/metagrid/ApocBRBuildingPlan.java','public final class ApocBRBuildingPlan','class ApocBRBuildingPlan')+'\n'
        source+=r'''
public class BuildingHarness {
 static final Thread owner=Thread.currentThread();static boolean enforce,originalGeometry;static final ArrayList<String>diagnostics=new ArrayList<>();
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void retireCheck(Object value){
  if(!enforce||!(value instanceof RoomDef room))return;
  for(IsoChunk[]row:IsoWorld.instance.currentCell.map.chunks)for(IsoChunk chunk:row)if(chunk!=null)
   for(IsoGridSquare[]level:chunk.squares.values())for(IsoGridSquare square:level)if(square!=null) {
    check(square.getRoom()==null||square.getRoom().def!=room,"room pooled before square refresh");
    check(square.roofHideBuilding==null||square.roofHideBuilding.def!=room.building,"roof cache retains retired building");
   }
 }
 static void setup(){
  enforce=false;Pools.reset();diagnostics.clear();IsoWorld.instance=new IsoWorld();
  IsoMetaCell cell=IsoWorld.instance.meta.getCellData(0,0);BuildingDef authored=new BuildingDef();RoomDef room=new RoomDef();room.name="authored";room.level=0;room.rects.add(new RoomDef.RoomRect(16,0,4,4));room.CalculateBounds();room.building=authored;authored.rooms.add(room);authored.CalculateBounds(new ArrayList<>());
  authored.id=0;room.id=0;authored.metaId=authored.calculateMetaID(0,0);room.metaId=room.calculateMetaID(0,0);cell.buildings.add(authored);cell.buildingByMetaId.put(authored.metaId,authored);cell.roomList.add(room);cell.rooms.put(room.id,room);cell.roomByMetaId.put(room.metaId,room);IsoWorld.instance.meta.buildings.add(authored);
  for(int cx=0;cx<3;cx++)for(int cy=0;cy<2;cy++){IsoChunk chunk=new IsoChunk(cx,cy);IsoWorld.instance.currentCell.map.chunks[cx][cy]=chunk;for(int z=0;z<3;z++)for(int i=0;i<64;i++){IsoGridSquare s=new IsoGridSquare(chunk,cx*8+i%8,cy*8+i/8,z);chunk.getSquaresForLevel(z)[i]=s;}}
 }
 static DataRoot graph(int seed,int generation){
  DataRoot root=new DataRoot();Random rng=new Random(seed);int id=1;
  for(int cx=0;cx<4;cx++){DataChunk chunk=new DataChunk((seed&1)==0?cx:cx-2,0);root.add(chunk);
   for(int z=0;z<3;z++){
    IsoWorldRegion region=new IsoWorldRegion(id++);region.roof=(seed%7==0&&cx==1)?.4f:1;
    long mask=generation==2?0L:rng.nextLong();if(cx==2)mask=-1L;if(generation==1&&cx==0)mask=0xffL;
    if(generation!=2) {
     if(cx==0&&z==0){long lower=mask&0xffffffffL,upper=mask&0xffffffff00000000L;IsoWorldRegion neighbor=new IsoWorldRegion(id++);region.neighbors.add(neighbor);neighbor.neighbors.add(region);chunk.add(region,z,lower==0?1:lower);chunk.add(neighbor,z,upper==0?1L<<40:upper);}
     else chunk.add(region,z,mask==0?1:mask);
    }
   }
  }
  // Same room spans the region-cell boundary; a distinct adjacent level can merge during commit.
  if(generation!=2){IsoWorldRegion cross=new IsoWorldRegion(100);DataChunk a=new DataChunk(31,0),b=new DataChunk(32,0);a.add(cross,0,-1L);b.add(cross,0,-1L);root.add(a);root.add(b);}
  return root;
 }
 static ArrayList<IsoGameCharacter.Location>changed(){return new ArrayList<>(List.of(new IsoGameCharacter.Location(0,0,0),new IsoGameCharacter.Location(0,0,0),new IsoGameCharacter.Location(1,0,0)));}
 static String dump(){
  ArrayList<String>buildings=new ArrayList<>();
  for(IsoMetaCell cell:IsoWorld.instance.meta.cells.values()){
   check(cell.rooms.size()==cell.roomList.size(),"room map/list divergence");
   for(RoomDef r:cell.roomList)check(cell.rooms.get(r.id)==r,"room ID lookup mismatch");
   for(var entry:cell.isoRooms.entrySet())check(entry.getValue().def!=null&&entry.getKey()==entry.getValue().def.id,"runtime room rekey mismatch");
   for(var entry:cell.isoBuildings.entrySet())check(entry.getValue().def!=null&&entry.getKey()==entry.getValue().def.id,"runtime building rekey mismatch");
   for(BuildingDef b:cell.buildings){ArrayList<String>rooms=new ArrayList<>();for(RoomDef r:b.rooms){ArrayList<String>rects=new ArrayList<>();for(RoomDef.RoomRect q:r.rects)rects.add(q.x+","+q.y+","+q.w+","+q.h);rooms.add(r.id+":"+r.level+":"+rects);}buildings.add(cell.x+","+cell.y+":"+b.id+":"+b.x+","+b.y+","+b.x2+","+b.y2+":"+rooms);}
  }
  Collections.sort(buildings);return buildings.toString();
 }
 static ArrayList<String>run(boolean patched,int seed)throws Exception {
  originalGeometry=!patched;setup();OriginalReconciler old=new OriginalReconciler();NewReconciler updated=new NewReconciler();ArrayList<String>states=new ArrayList<>();
  for(int generation=0;generation<3;generation++) {
   IsoRegions.root=graph(seed,generation);ArrayList<IsoGameCharacter.Location>cells=changed();
   if(patched){
    String before=dump();IsoMetaCell ownerCell=IsoWorld.instance.meta.getCellData(0,0);BuildingDef authored=ownerCell.buildings.get(0);authored.getRoofRoomID(0);
    final ApocBRBuildingPlan[]ready=new ApocBRBuildingPlan[1];DataRoot root=IsoRegions.root;int gen=generation;
    Thread worker=new Thread(()->ready[0]=ApocBRBuildingPlan.build(root,cells,gen,()->false));worker.start();worker.join();
    check(before.equals(dump()),"worker changed published metadata");check(ready[0]!=null,"worker failed");ready[0].validate(root,generation);
    check(ready[0].cells.size()==12,"deduplicated neighbor footprint");
    check(root.collects==12,"cell collection repeated between preparation and removal");
    int beforeClears=IsoWorld.instance.meta.getCellData(0,0).rooms.clears;enforce=true;updated.commitPrepared(root,ready[0]);enforce=false;
    check(IsoWorld.instance.meta.getCellData(0,0).rooms.clears-beforeClears<=1,"reindexed cell more than once");
    check(authored.getRoofRoomID(0)==RoomID.makeID(0,0,ownerCell.rooms.size()+ownerCell.buildings.indexOf(authored)*64+32),"stale synthetic roof-room ID");
    HashSet<BuildingDef>active=new HashSet<>();for(IsoMetaCell c:IsoWorld.instance.meta.cells.values())active.addAll(c.buildings);
    check(new HashSet<>(IsoWorld.instance.meta.buildings).equals(active),"global registry retains retired definitions");
   }else old.clientProcessBuildings(cells);
   states.add(dump());check(diagnostics.isEmpty(),"unexpected diagnostic "+diagnostics);
  }
  return states;
 }
 public static void main(String[]args)throws Exception {
  for(int seed=0;seed<60;seed++)check(run(false,seed).equals(run(true,seed)),"vanilla/new geometry or final IDs differ, seed "+seed);
  IsoRegions.root=graph(2,0);ApocBRBuildingPlan plan=ApocBRBuildingPlan.build(IsoRegions.root,changed(),19,()->false);
  try{plan.validate(new DataRoot(),19);throw new AssertionError("accepted wrong root");}catch(IllegalStateException expected){}
  try{plan.validate(IsoRegions.root,20);throw new AssertionError("accepted wrong generation");}catch(IllegalStateException expected){}
  try{ApocBRBuildingPlan.build(IsoRegions.root,changed(),20,()->true);throw new AssertionError("ignored cancellation");}catch(CancellationException expected){}
  if(!plan.shapes.isEmpty()){IsoWorldRegion r=plan.shapes.get(0).rooms().get(0).region();r.id++;try{plan.validate(IsoRegions.root,19);throw new AssertionError("accepted recycled region");}catch(IllegalStateException expected){}r.id--;}
  rectangleAndBounds();handoff();
  System.out.println("Production original/new reconciliation differential: 60 maps x 3 generations, published-state isolation, duplicate cells, cross-cell rooms, ID/cache consistency and safe retirement passed");
 }
 static void rectangleAndBounds()throws Exception {
  originalGeometry=false;setup();Random rng=new Random(713);OriginalReconciler original=new OriginalReconciler();
  var convert=OriginalReconciler.class.getDeclaredMethod("regionToRoomDef",IsoWorldRegion.class);convert.setAccessible(true);
  for(int trial=0;trial<2500;trial++){
   long mask=trial<64?1L<<trial:rng.nextLong();DataChunk c=new DataChunk(rng.nextInt(80)-40,rng.nextInt(80)-40);IsoWorldRegion r=new IsoWorldRegion(1);c.add(r,0,mask);
   RoomDef expected=(RoomDef)convert.invoke(original,r);List<ApocBRBuildingPlan.Rect>actual=ApocBRBuildingPlan.rectangles(mask,c.x*8,c.y*8);
   check(expected.rects.size()==actual.size(),"rectangle size");for(int i=0;i<actual.size();i++){RoomDef.RoomRect e=expected.rects.get(i);var a=actual.get(i);check(e.x==a.x()&&e.y==a.y()&&e.w==a.w()&&e.h==a.h(),"greedy rectangle order");}
  }
  for(int trial=0;trial<400;trial++){
   BuildingDef expected=new BuildingDef(),actual=new BuildingDef();RoomDef a=new RoomDef(),b=new RoomDef();a.level=b.level=0;
   int offset=trial%3==0?32768*8:trial%3==1?-32770*8:0;
   for(int i=0;i<30;i++){int x=offset+rng.nextInt(30)-15,y=rng.nextInt(30)-15,w=1+rng.nextInt(8),h=1+rng.nextInt(8);a.rects.add(new RoomDef.RoomRect(x,y,w,h));b.rects.add(new RoomDef.RoomRect(x,y,w,h));}
   expected.rooms.add(a);actual.rooms.add(b);expected.OriginalBounds(new ArrayList<>());actual.CalculateBounds(new ArrayList<>());
   check(expected.x==actual.x&&expected.y==actual.y&&expected.x2==actual.x2&&expected.y2==actual.y2,"bounds");check(expected.overlappedChunks.a.equals(actual.overlappedChunks.a),"short/negative chunk order");
  }
 }
 static DataRoot dataRoot;static IsoRegionWorker regionWorker;static Logger logger=new Logger();
 static class Logger {void log(String s){}}
 static void clientResetCachedRegionReferences(){}
 static class Flag {final java.util.concurrent.atomic.AtomicBoolean value=new java.util.concurrent.atomic.AtomicBoolean();boolean get(){return value.get();}void set(boolean v){if(!v){check(dataRoot.apocBuildingPlan==null&&dataRoot.changedCells.isEmpty(),"ack preceded commit consumption");dump();}value.set(v);}}
 static class IsoRegionWorker {static Flag isRequestingBufferSwap=new Flag();DataRoot buffer;DataRoot getRootBuffer(){return buffer;}void setRootBuffer(DataRoot r){buffer=r;}}
 static void swap(){SWAP_BODY}
 static void handoff()throws Exception {
  setup();originalGeometry=false;DataRoot.wrtmg=new NewReconciler();dataRoot=graph(22,0);IsoRegions.root=dataRoot;dataRoot.changedCells.addAll(changed());dataRoot.clientProcessBuildings();
  DataRoot incoming=graph(22,1);incoming.changedCells.addAll(changed());regionWorker=new IsoRegionWorker();regionWorker.buffer=incoming;String previous=dump();
  Thread worker=new Thread(()->{incoming.prepareClientBuildings(()->false);IsoRegionWorker.isRequestingBufferSwap.set(true);while(IsoRegionWorker.isRequestingBufferSwap.get())Thread.onSpinWait();check(dataRoot==incoming,"worker resumed before publication");});
  worker.start();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!IsoRegionWorker.isRequestingBufferSwap.get()){if(System.nanoTime()>end)throw new AssertionError("handoff timeout");Thread.sleep(1);}
  check(previous.equals(dump()),"metadata changed before swap");IsoRegions.root=incoming;enforce=true;swap();enforce=false;worker.join(5000);check(!worker.isAlive(),"ack failed");check(regionWorker.buffer!=incoming,"old-root ownership not transferred");
  DataRoot cancelled=graph(25,0);cancelled.changedCells.addAll(changed());String stable=dump();
  try{cancelled.prepareClientBuildings(()->true);throw new AssertionError("cancelled preparation succeeded");}catch(CancellationException expected){}
  check(!cancelled.changedCells.isEmpty()&&cancelled.apocBuildingPlan==null&&stable.equals(dump()),"cancelled plan consumed edits or changed world");
  DataRoot wrongSession=graph(26,0);wrongSession.changedCells.addAll(changed());wrongSession.apocBuildingPlan=ApocBRBuildingPlan.build(cancelled,cancelled.changedCells,wrongSession.apocBuildingGeneration,()->false);
  try{wrongSession.clientProcessBuildings();throw new AssertionError("stale world plan accepted");}catch(IllegalStateException expected){}
  check(!wrongSession.changedCells.isEmpty()&&stable.equals(dump()),"rejected plan consumed edits or changed world");
 }
}
'''
        regions=(ROOT/'src/zombie/iso/areas/isoregion/IsoRegions.java').read_text()
        source=source.replace('SWAP_BODY',method(regions,'if (IsoRegionWorker.isRequestingBufferSwap.get())'))
        # Trove contains(long) tests keys, unlike Java Map.containsValue.
        source=source.replace('class CountingRooms extends', 'class MetaMap<T> extends HashMap<Long,T> {boolean contains(long key){return containsKey(key);}}\nclass CountingRooms extends')
        source=source.replace('final HashMap<Long,RoomDef>roomByMetaId=new HashMap<>()','final MetaMap<RoomDef>roomByMetaId=new MetaMap<>()')
        self.run_java(source,'BuildingHarness')

    def run_java(self,source,name):
        with tempfile.TemporaryDirectory(prefix='apocbr-buildings-') as temp:
            work=pathlib.Path(temp);harness=work/f'{name}.java';harness.write_text(source)
            subprocess.run([str(JDK/'javac.exe'),'--release','25','-d',str(work),str(ROOT/'src/zombie/ApocBRClientTelemetryLite.java'),str(harness)],check=True)
            subprocess.run([str(JDK/'java.exe'),'-cp',str(work),name],check=True,timeout=60)


if __name__=='__main__':unittest.main()
