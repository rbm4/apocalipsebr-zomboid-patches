"""Reproduce 42.22.1 Java-fallback mover rejection, then exercise both production request types and the fix."""
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
V=ROOT/'42.22.1'
STUBS={
'zombie/ai/astar/Mover.java':'package zombie.ai.astar;public interface Mover {int getID();int getPathFindIndex();}',
'zombie/ai/KnownBlockedEdges.java':'''package zombie.ai;import java.util.*;public class KnownBlockedEdges {
 public static KnownBlockedEdges alloc(){return new KnownBlockedEdges();}public KnownBlockedEdges init(KnownBlockedEdges other){return this;}public static void releaseAll(List<KnownBlockedEdges> values){}}''',
'gnu/trove/list/array/TFloatArrayList.java':'''package gnu.trove.list.array;public class TFloatArrayList {public void resetQuick(){}public void add(float v){}}''',
'zombie/characters/IsoGameCharacter.java':'''package zombie.characters;import zombie.ai.astar.Mover;import zombie.ai.KnownBlockedEdges;
public class IsoGameCharacter implements Mover {
 public int id=1;public int getID(){return id;}public int getPathFindIndex(){return 0;}
 public Knowledge getMapKnowledge(){return new Knowledge();}public static class Knowledge {public java.util.ArrayList<KnownBlockedEdges> getKnownBlockedEdges(){return new java.util.ArrayList<>();}}}''',
'zombie/characters/IsoPlayer.java':'''package zombie.characters;public class IsoPlayer extends IsoGameCharacter {
 public static boolean isPlayer(IsoPlayer player){return player!=null;}public float getClimbingFailChanceFloat(){return 1;}}''',
'zombie/characters/animals/IsoAnimal.java':'''package zombie.characters.animals;public class IsoAnimal extends zombie.characters.IsoPlayer {
 public boolean fences=true;public boolean canClimbFences(){return fences;}public boolean shouldBreakObstaclesDuringPathfinding(){return true;}}''',
'zombie/characters/IsoZombie.java':'''package zombie.characters;public class IsoZombie extends IsoGameCharacter {
 public short onlineId=42;public boolean crawling,dead;public boolean isDead(){return dead;}
 public boolean isCrawling(){return crawling;}public boolean isCanCrawlUnderVehicle(){return crawling;}public boolean isCanWalk(){return !crawling;}
 public Object getTarget(){return null;}public boolean isMovingToPlayerSound(){return false;}
 public float getX(){return 1.5f;}public float getY(){return 2.5f;}public float getZ(){return 0;}public int getZi(){return 0;}
 public Cell getCell(){return new Cell();}public static class Cell {public Square getGridSquare(int x,int y,int z){return new Square();}}
 public static class Square {public boolean HasStairs(){return false;}}}''',
'zombie/iso/IsoDirections.java':'package zombie.iso;public enum IsoDirections {N}',
'zombie/iso/BentFences.java':'package zombie.iso;public class BentFences {public static BentFences getInstance(){return new BentFences();}public boolean isEnabled(){return true;}}',
'zombie/SandboxOptions.java':'package zombie;public class SandboxOptions {public static final SandboxOptions instance=new SandboxOptions();public final Option easyClimbing=new Option();public static class Option {public boolean getValue(){return false;}}}',
'zombie/util/Type.java':'package zombie.util;public class Type {public static <T>T tryCastTo(Object value,Class<T> type){return type.isInstance(value)?type.cast(value):null;}}',
'zombie/popman/ObjectPool.java':'''package zombie.popman;public class ObjectPool<T>{final java.util.function.Supplier<T> create;
 public ObjectPool(java.util.function.Supplier<T> create,String name){this.create=create;}public T alloc(){return create.get();}public void release(T item){}}''',
'zombie/pathfind/IPathfinder.java':'''package zombie.pathfind;import zombie.ai.astar.Mover;public interface IPathfinder {void Succeeded(Path path,Mover mover);void Failed(Mover mover);}''',
'zombie/pathfind/Path.java':'''package zombie.pathfind;public class Path {
 public record Node(float x,float y,float z){}public final java.util.ArrayList<Node> nodes=new java.util.ArrayList<>();
 public void clear(){nodes.clear();}public int size(){return nodes.size();}public Node getNode(int i){return nodes.get(i);}
 public void addNode(float x,float y,float z){nodes.add(new Node(x,y,z));}}'''.replace('public record Node(float x,float y,float z){}','public static class Node {public final float x,y,z;public Node(float x,float y,float z){this.x=x;this.y=y;this.z=z;}}'),
'zombie/pathfind/Square.java':'package zombie.pathfind;public class Square {public int x,y,z;}',
'zombie/pathfind/Chunk.java':'package zombie.pathfind;public class Chunk {public Level getLevelData(int z){return new Level();}public static class Level {public zombie.pathfind.highLevel.HLChunkLevel getHighLevelData(){return null;}}}',
'zombie/pathfind/highLevel/HLChunkLevel.java':'package zombie.pathfind.highLevel;public class HLChunkLevel {}',
'zombie/pathfind/highLevel/HLStaircase.java':'package zombie.pathfind.highLevel;public class HLStaircase {}',
'zombie/pathfind/highLevel/HLLevelTransition.java':'package zombie.pathfind.highLevel;public class HLLevelTransition {public zombie.pathfind.Square getTopFloorSquare(){return null;}public zombie.pathfind.Square getBottomFloorSquare(){return null;}}',
'zombie/apocalipse/humans/HumanLog.java':'package zombie.apocalipse.humans;public class HumanLog {public enum Level{DEBUG}public static void log(Level level,java.util.function.Supplier<String> text){}}',
'zombie/pathfind/PolygonalMap2.java':'''package zombie.pathfind;
import java.util.*;import zombie.ai.astar.Mover;
public class PolygonalMap2 {
 public static final PolygonalMap2 instance=new PolygonalMap2();
 public final Map<Mover,PathFindRequest> jobs=new IdentityHashMap<>();public PMMover reused=new PMMover();public int pumps;
 public Chunk getChunkFromSquarePos(int x,int y){return new Chunk();}
 public void addRequest(IPathfinder f,Mover m,float x,float y,float z,float tx,float ty,float tz){cancelRequest(m);jobs.put(m,PathFindRequest.alloc().init(f,m,x,y,z,tx,ty,tz));}
 public void cancelRequest(Mover mover){var old=jobs.remove(mover);if(old!=null)old.cancel=true;}
 public PMMover classify(Mover mover,boolean poison){var r=jobs.get(mover);if(poison){r.canCrawl=r.canThump=r.ignoreCrawlCost=true;}return reused.set(r);}
 public IPathfinder callback(Mover mover){return jobs.get(mover).finder;}
 public void updateMain(){pumps++;for(var r:new ArrayList<>(jobs.values())){
  if(r.cancel)continue;reused.set(r);jobs.remove(r.mover);Path p=new Path();p.addNode(r.startX,r.startY,r.startZ);p.addNode(r.targetX,r.targetY,r.targetZ);r.finder.Succeeded(p,r.mover);p.clear();r.release();}}
}''',
'zombie/pathfind/nativeCode/PathfindNative.java':'''package zombie.pathfind.nativeCode;
import java.util.*;import zombie.ai.astar.Mover;import zombie.pathfind.*;
public class PathfindNative {
 public static boolean useNativeCode=false;public static final PathfindNative instance=new PathfindNative();
 public final Map<Mover,PathFindRequest> jobs=new IdentityHashMap<>();public int pumps;
 public void addRequest(IPathfinder f,Mover m,float x,float y,float z,float tx,float ty,float tz,boolean avoid){cancelRequest(m);jobs.put(m,PathFindRequest.alloc().init(f,m,x,y,z,tx,ty,tz,avoid));}
 public void cancelRequest(Mover mover){var old=jobs.remove(mover);if(old!=null)old.cancel=true;}
 public IPathfinder callback(Mover mover){return jobs.get(mover).finder;}
 public boolean walkOnly(Mover mover){var r=jobs.get(mover);return !r.canCrawl&&!r.crawling&&!r.ignoreCrawlCost&&!r.canThump&&!r.canClimbFences&&!r.canClimbTallFences&&!r.hasTarget&&!r.canBend&&!r.avoidFarmingPlants;}
 public void updateMain(){pumps++;for(var r:new ArrayList<>(jobs.values())){if(r.cancel)continue;jobs.remove(r.mover);Path p=new Path();p.addNode(r.startX,r.startY,r.startZ);p.addNode(r.targetX,r.targetY,r.targetZ);r.finder.Succeeded(p,r.mover);p.clear();r.release();}}
}''',
}

HARNESS=r'''
package zombie.apocalipse.zombies;
import zombie.pathfind.*;import zombie.pathfind.nativeCode.PathfindNative;import zombie.characters.*;import zombie.ai.astar.Mover;
public class PathMoverTest {
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static final IPathfinder noop=new IPathfinder(){public void Succeeded(Path p,Mover m){}public void Failed(Mover m){}};
 static void queue(Mover m){PolygonalMap2.instance.addRequest(noop,m,1,2,0,4,5,0);}
 static Path result(float x,float y,float z){var p=new Path();p.addNode(x,y,z);return p;}
 static void javaClassification(){
  var map=PolygonalMap2.instance;var zombie=new IsoZombie();zombie.crawling=true;queue(zombie);
  var mover=map.classify(zombie,false);check(mover.isZombie()&&mover.crawling&&mover.canCrawl&&mover.canThump,"native zombie semantics preserved");
  var player=new IsoPlayer();queue(player);mover=map.classify(player,false);check(mover.isPlayer()&&!mover.crawling&&mover.canClimbTallFences,"native player semantics preserved");
  var animal=new zombie.characters.animals.IsoAnimal();queue(animal);mover=map.classify(animal,false);check(mover.isAnimal()&&mover.canClimbFences&&mover.canThump,"native animal semantics preserved");
  var body=new IsoZombie();var proxy=new ServerZombiePath(body);proxy.request(9,2);mover=map.classify(proxy,true);
  check(mover.isAnimal()&&!mover.crawling&&!mover.canCrawl&&!mover.ignoreCrawlCost&&!mover.canThump&&!mover.canClimbFences&&!mover.canClimbTallFences,"walk-only identity resets pooled state and cannot inherit native abilities");
  check(map.jobs.containsKey(body)==false&&map.jobs.containsKey(proxy),"request retains isolated proxy identity");
  Mover unsupported=new Mover(){public int getID(){return 10;}public int getPathFindIndex(){return -1;}};queue(unsupported);
  try{map.classify(unsupported,false);throw new AssertionError("arbitrary unknown mover accepted");}catch(IllegalArgumentException expected){checks++;}map.cancelRequest(unsupported);
  proxy.cancel();map.jobs.clear();
 }
 static void lifecycle(boolean nativeCode){
  PathfindNative.useNativeCode=nativeCode;var map=PolygonalMap2.instance;var backend=PathfindNative.instance;
  var body=new IsoZombie();var proxy=new ServerZombiePath(body);
  if(nativeCode)backend.addRequest(noop,body,1,2,0,3,4,0,false);else queue(body);
  proxy.request(9,2);
  check(nativeCode?backend.jobs.containsKey(body)&&backend.jobs.containsKey(proxy):map.jobs.containsKey(body)&&map.jobs.containsKey(proxy),"native AI and proxy request slots coexist");
  if(nativeCode)check(backend.walkOnly(proxy),"actual native request initializer retains generic walk-only flags");
  var old=nativeCode?backend.callback(proxy):map.callback(proxy);proxy.cancel();
  check(nativeCode?backend.jobs.containsKey(body):map.jobs.containsKey(body),"proxy cancellation preserves native AI job");
  proxy.request(10,2);old.Succeeded(result(99,99,0),proxy);old.Failed(proxy);
  check(proxy.pending()&&proxy.points().isEmpty(),"late prior generation cannot complete/fail replacement request");
  ServerZombiePath.update();check(!proxy.pending()&&proxy.points().size()==2&&proxy.points().getLast().x()==10,"backend pump delivers copied route before pooled result is cleared");
  proxy.cancel();proxy.request(11,2);var callback=nativeCode?backend.callback(proxy):map.callback(proxy);body.id++;
  callback.Succeeded(result(11,2,0),proxy);check(proxy.points().isEmpty(),"recycled native body rejects old incarnation result");proxy.cancel();body.id--;
  proxy.request(12,2);callback=nativeCode?backend.callback(proxy):map.callback(proxy);body.onlineId++;
  callback.Succeeded(result(12,2,0),proxy);check(proxy.points().isEmpty(),"online identity replacement rejects old result");proxy.cancel();body.onlineId--;
  proxy.request(13,2);callback=nativeCode?backend.callback(proxy):map.callback(proxy);body.dead=true;
  callback.Succeeded(result(13,2,0),proxy);check(proxy.points().isEmpty(),"dead body rejects route");proxy.cancel();body.dead=false;
  proxy.request(14,2);callback=nativeCode?backend.callback(proxy):map.callback(proxy);callback.Succeeded(result(14,2,1),proxy);
  check(proxy.points().isEmpty()&&proxy.needsNativeTraversal(),"cross-floor route still delegates to native traversal");proxy.cancel();
  proxy.request(15,2);callback=nativeCode?backend.callback(proxy):map.callback(proxy);callback.Succeeded(result(Float.NaN,2,0),proxy);check(proxy.points().isEmpty(),"nonfinite result rejected");proxy.cancel();
  proxy.request(16,2);callback=nativeCode?backend.callback(proxy):map.callback(proxy);Path tooLong=new Path();for(int i=0;i<257;i++)tooLong.addNode(i,2,0);callback.Succeeded(tooLong,proxy);
  check(proxy.points().isEmpty(),"route node limit preserved");proxy.cancel();map.jobs.clear();backend.jobs.clear();
 }
 public static void main(String[] args){
  PathfindNative.useNativeCode=false;
  if(args[0].equals("baseline")){
   var proxy=new ServerZombiePath(new IsoZombie());proxy.request(9,2);
   try{PolygonalMap2.instance.classify(proxy,false);throw new AssertionError("baseline failed to reproduce");}
   catch(IllegalArgumentException expected){check(expected.getMessage().contains("unsupported Mover"),"exact logged failure reproduced");}
   System.out.println("Vanilla PMMover reproduced unsupported ServerZombiePath (42.22.1)");return;
  }
  javaClassification();lifecycle(false);lifecycle(true);
  PathfindNative.useNativeCode=false;var proxies=new java.util.ArrayList<ServerZombiePath>();
  for(int i=0;i<128;i++){var body=new IsoZombie();body.id=1000+i;body.onlineId=(short)(100+i);var proxy=new ServerZombiePath(body);proxy.request(10+i,2);proxies.add(proxy);}
  ServerZombiePath.update();for(var proxy:proxies)check(!proxy.pending()&&proxy.points().size()==2,"burst Java fallback request completes without mover rejection");
  System.out.println("Zombie path mover: "+checks+" assertions passed (production PMMover, both request initializers and ServerZombiePath; backend/world doubles)");
 }
}
'''

def main():
    production=[V/'src/zombie/apocalipse/zombies/ServerZombiePath.java',V/'src/zombie/apocalipse/pathfind/WalkOnlyMover.java',
        V/'decompiled/zombie/pathfind/PathFindRequest.java',V/'decompiled/zombie/pathfind/nativeCode/PathFindRequest.java',V/'decompiled/zombie/pathfind/MoverType.java']
    with tempfile.TemporaryDirectory(prefix='apocbr-zombie-path-mover-') as folder:
        work=Path(folder);sources=list(production)
        for name,content in {**STUBS,'zombie/apocalipse/zombies/PathMoverTest.java':HARNESS}.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        mover=work/'zombie/pathfind/PMMover.java';sources.append(mover)
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        for mode in ('baseline','fixed'):
            source=V/('decompiled' if mode=='baseline' else 'src')/'zombie/pathfind/PMMover.java'
            mover.write_text(source.read_text(encoding='utf-8'),encoding='utf-8')
            output=work/mode
            subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(output),'@'+str(args)],check=True)
            subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-ea','-cp',str(output),'zombie.apocalipse.zombies.PathMoverTest',mode],check=True)

if __name__=='__main__':main()
