"""Production async ordering plus extracted building algorithms; no renderer/game process."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = ROOT.parent / 'jdk/bin'


def method(text, signature):
    start = text.index(signature)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


class AsyncClientWorkTest(unittest.TestCase):
    def test_translucent_order(self):
        source = r'''
import java.util.*;
import java.util.concurrent.*;
import java.util.function.ToIntFunction;
import zombie.iso.fboRenderChunk.ApocBRTranslucentOrder;
public class AsyncOrderHarness {
 static final Thread OWNER=Thread.currentThread();
 static class Value { int key; Value(int k){key=k;} public boolean equals(Object x){throw new AssertionError("worker called world equals");}
  public int hashCode(){throw new AssertionError("worker called world hashCode");} }
 static final ToIntFunction<Value> ORDER=v->{if(Thread.currentThread()!=OWNER)throw new AssertionError("worker accessed coordinates");return v.key;};
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static List<Value> ready(ApocBRTranslucentOrder<Value> helper,Object chunk,int player,int level,List<Value> a,List<Value>b,List<Value>c)throws Exception {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  do {List<Value> result=helper.get(chunk,player,level,a,b,c,ORDER);if(result!=null)return result;Thread.sleep(1);}while(System.nanoTime()<deadline);
  throw new AssertionError("result never became ready");
 }
 static void equal(List<Value>a,List<Value>b){check(a.size()==b.size(),"size");for(int i=0;i<a.size();i++)check(a.get(i)==b.get(i),"stable identity order");}
 public static void main(String[]args)throws Exception {
  ApocBRTranslucentOrder<Value> helper=new ApocBRTranslucentOrder<>();Object world=new Object(),chunk=new Object();helper.beginFrame(world);
  Random rng=new Random(492);
  for(int trial=0;trial<150;trial++) {
   ArrayList<Value> pool=new ArrayList<>();for(int i=0;i<40;i++)pool.add(new Value(rng.nextInt(10)-5));
   ArrayList<Value>a=new ArrayList<>(),b=new ArrayList<>(),c=new ArrayList<>();
   for(ArrayList<Value> list:List.of(a,b,c))for(int i=0;i<80;i++)list.add(pool.get(rng.nextInt(pool.size())));
   ArrayList<Value> expected=new ArrayList<>(a);Set<Value> seen=Collections.newSetFromMap(new IdentityHashMap<>());seen.addAll(a);
   for(List<Value> list:List.of(b,c))for(Value value:list)if(seen.add(value))expected.add(value);
   expected.sort(Comparator.comparingInt(v->v.key));
   equal(expected,ready(helper,chunk,0,trial,a,b,c));
  }
  Value one=new Value(2),two=new Value(1);ArrayList<Value>a=new ArrayList<>(List.of(one,two));
  equal(List.of(two,one),ready(helper,chunk,0,0,a,List.of(),List.of()));
  one.key=0;equal(List.of(one,two),ready(helper,chunk,0,0,a,List.of(),List.of()));
  a.remove(0);equal(List.of(two),ready(helper,chunk,0,0,a,List.of(),List.of()));
  equal(List.of(two),ready(helper,chunk,1,0,a,List.of(),List.of()));
  helper.beginFrame(new Object());equal(List.of(two),ready(helper,chunk,0,0,a,List.of(),List.of()));
  // Cold/changed lists render immediately, even while the worker is blocked or saturated.
  var field=ApocBRTranslucentOrder.class.getDeclaredField("WORKER");field.setAccessible(true);ThreadPoolExecutor executor=(ThreadPoolExecutor)field.get(null);
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  executor.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}});entered.await();
  Object changingChunk=new Object();Value fresh=new Value(-1);
  equal(List.of(two),helper.get(changingChunk,0,0,a,List.of(),List.of(),ORDER));
  a.add(fresh);List<Value> continuity=helper.get(changingChunk,0,0,a,List.of(),List.of(),ORDER);
  equal(List.of(fresh,two),continuity);
  check(helper.get(changingChunk,0,0,a,List.of(),List.of(),ORDER)==continuity,"owner catch-up was not cached");
  a.remove(0);fresh.key=4;equal(List.of(fresh),helper.get(changingChunk,0,0,a,List.of(),List.of(),ORDER));
  while(executor.getQueue().remainingCapacity()>0)executor.execute(()->{});
  long before=System.nanoTime();equal(List.of(fresh),helper.get(new Object(),0,0,a,List.of(),List.of(),ORDER));
  check(System.nanoTime()-before<TimeUnit.SECONDS.toNanos(1),"owner blocked on queue");
  // In-flight old-world completion must never populate a new world's cache.
  helper.beginFrame(new Object());equal(List.of(fresh),helper.get(chunk,0,0,a,List.of(),List.of(),ORDER));
  release.countDown();FutureTask<Object> fence=new FutureTask<>(()->null);
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(true){try{executor.execute(fence);break;}catch(RejectedExecutionException full){if(System.nanoTime()>deadline)throw full;Thread.sleep(1);}}
  fence.get(5,TimeUnit.SECONDS);
  equal(List.of(fresh),ready(helper,chunk,0,0,a,List.of(),List.of()));
  for(int i=0;i<1100;i++)ready(helper,new Object(),0,0,a,List.of(),List.of());
  var cache=ApocBRTranslucentOrder.class.getDeclaredField("cache");cache.setAccessible(true);
  check(((Map<?,?>)cache.get(helper)).size()<=1024,"cache bound");
  System.out.println("Translucent continuous drawing, mutation/reuse, stable ties, blocked/saturated worker and cache bounds passed");
 }
}
'''
        self.run_java('AsyncOrderHarness', source, [ROOT/'src/zombie/ApocBRClientTelemetryLite.java',
            ROOT/'src/zombie/iso/fboRenderChunk/ApocBRTranslucentOrder.java'])

    def test_building_algorithms(self):
        relative = 'zombie/iso/areas/isoregion/metagrid/WorldRegionToMetaGrid.java'
        original = (ROOT/'decompiled'/relative).read_text()
        patched = (ROOT/'src'/relative).read_text()
        signatures = ['private void getConnectedRegions(', 'private void markChunkDirty(', 'private boolean chunkIsDirty(', 'private void assignBuildingToRegions(']
        baseline = '\n'.join(method(original, x) for x in signatures)
        updated = '\n'.join(method(patched, x) for x in signatures) + '\n' + method(patched, 'private static long chunkKey(')
        common = r'''
import java.util.*;
class BuildingDef {}
class IsoWorldRegion {boolean enclosed=true;float roof=1;ArrayList<IsoWorldRegion> neighbours=new ArrayList<>();BuildingDef building;void setBuildingDef(BuildingDef b){building=b;}
 boolean isEnclosed(){return enclosed;}float getRoofedPercentage(){return roof;}ArrayList<IsoWorldRegion>getNeighbors(){return neighbours;}}
class Location {int x,y,z;Location(int x,int y,int z){set(x,y,z);}void set(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 public boolean equals(Object o){return o instanceof Location p&&p.x==x&&p.y==y&&p.z==z;}}
class IsoGameCharacter {static class Location extends LOCATION {Location(int x,int y,int z){super(x,y,z);}}}
class IsoChunk {int wx,wy;IsoChunk(int x,int y){wx=x;wy=y;}}
class PZMath {static int fastfloor(float v){return (int)Math.floor(v);}}
'''.replace('extends LOCATION', 'extends Location')
        # Avoid the nested Location inheriting itself.
        common = common.replace('class Location {', 'class BaseLocation {').replace('Location(int x,int y,int z){set', 'BaseLocation(int x,int y,int z){set').replace('instanceof Location p', 'instanceof BaseLocation p').replace('extends Location', 'extends BaseLocation')
        source = common + '\nclass Original {final ArrayList<IsoWorldRegion>allWorldRegions=new ArrayList<>();final HashMap<BuildingDef,ArrayList<IsoWorldRegion>>buildingToRegions=new HashMap<>();final List<IsoGameCharacter.Location> dirtyChunks=new ArrayList<>();final IsoGameCharacter.Location tempLocation=new IsoGameCharacter.Location(0,0,0);' + baseline + r'''
 void assign(ArrayList<IsoWorldRegion>r,BuildingDef b){assignBuildingToRegions(r,b);}
 void graph(IsoWorldRegion root,ArrayList<IsoWorldRegion>out){getConnectedRegions(root,out);}void dirty(int x,int y){markChunkDirty(x,y);}boolean check(IsoChunk c){return chunkIsDirty(c);}}
class Updated {final LinkedHashSet<IsoWorldRegion>allWorldRegions=new LinkedHashSet<>();final HashMap<BuildingDef,ArrayList<IsoWorldRegion>>buildingToRegions=new HashMap<>();final HashSet<Long>dirtyChunks=new HashSet<>();''' + updated + r'''
 void assign(ArrayList<IsoWorldRegion>r,BuildingDef b){assignBuildingToRegions(r,b);}
 void graph(IsoWorldRegion root,ArrayList<IsoWorldRegion>out){getConnectedRegions(root,out);}void dirty(int x,int y){markChunkDirty(x,y);}boolean check(IsoChunk c){return chunkIsDirty(c);}}
public class BuildingAlgorithmHarness {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[]args){Random rng=new Random(882);
  for(int trial=0;trial<200;trial++){
   ArrayList<IsoWorldRegion>nodes=new ArrayList<>();for(int i=0;i<120;i++){IsoWorldRegion n=new IsoWorldRegion();n.enclosed=rng.nextInt(8)!=0;n.roof=rng.nextBoolean()?1:.4f;nodes.add(n);}
   for(IsoWorldRegion n:nodes)for(int j=0;j<8;j++)n.neighbours.add(nodes.get(rng.nextInt(nodes.size())));
   ArrayList<IsoWorldRegion>a=new ArrayList<>(),b=new ArrayList<>();if(trial%3==0){a.add(nodes.get(5));b.add(nodes.get(5));}
   new Original().graph(nodes.get(0),a);new Updated().graph(nodes.get(0),b);check(a.equals(b),"DFS order/cycles");
  }
  IsoWorldRegion root=new IsoWorldRegion(),tail=root;for(int i=0;i<100000;i++){IsoWorldRegion next=new IsoWorldRegion();tail.neighbours.add(next);tail=next;}
  ArrayList<IsoWorldRegion>out=new ArrayList<>();new Updated().graph(root,out);check(out.size()==100001,"deep graph");
  Original old=new Original();Updated now=new Updated();
  for(int i=0;i<10000;i++){int x=rng.nextInt(1000)-500,y=rng.nextInt(1000)-500;old.dirty(x,y);now.dirty(x,y);check(old.check(new IsoChunk(x,y))==now.check(new IsoChunk(x,y)),"dirty hit");}
  for(int i=0;i<10000;i++){IsoChunk c=new IsoChunk(rng.nextInt(2000)-1000,rng.nextInt(2000)-1000);check(old.check(c)==now.check(c),"dirty miss/negative");}
  check(Updated.chunkKeyForTest(Integer.MIN_VALUE,Integer.MAX_VALUE)!=Updated.chunkKeyForTest(Integer.MAX_VALUE,Integer.MIN_VALUE),"packed coordinate collision");
  BuildingDef building=new BuildingDef();old.buildingToRegions.put(building,new ArrayList<>());now.buildingToRegions.put(building,new ArrayList<>());
  ArrayList<IsoWorldRegion>pool=new ArrayList<>();for(int i=0;i<100;i++)pool.add(new IsoWorldRegion());
  for(int i=0;i<1000;i++){ArrayList<IsoWorldRegion>batch=new ArrayList<>();for(int j=0;j<15;j++)batch.add(pool.get(rng.nextInt(pool.size())));
   BuildingDef target=rng.nextBoolean()?building:null;old.assign(batch,target);now.assign(batch,target);
   check(old.allWorldRegions.equals(new ArrayList<>(now.allWorldRegions)),"region assignment insertion/removal order");}
  System.out.println("Building DFS differential, deep graph, and dirty-chunk lookup differential passed");
 }}
'''
        source = source.replace('private static long chunkKey(', 'static long chunkKey(').replace('Updated.chunkKeyForTest', 'Updated.chunkKey')
        self.run_java('BuildingAlgorithmHarness', source, [])

    def run_java(self, name, source, sources):
        with tempfile.TemporaryDirectory(prefix='apocbr-async-') as temp:
            work = pathlib.Path(temp)
            harness = work/f'{name}.java'
            harness.write_text(source)
            subprocess.run([str(JDK/'javac.exe'), '--release', '25', '-d', str(work), *map(str,sources), str(harness)], check=True)
            subprocess.run([str(JDK/'java.exe'), '-cp', str(work), name], check=True, timeout=60)


if __name__ == '__main__':
    unittest.main()
