"""42.21.0 typed modData traversal, owned references and journal/reconstruction fixtures.

Compile complete capture/snapshot/JDBC classes with engine boundary stubs, plus
actual structured reconstruction methods. No game JAR or live database required.
"""
import ast
from pathlib import Path
import subprocess
import tempfile

import test_postgres_duplicate_items as base
from postgres_test_support import inactive_persistence_stubs
from test_unload_simulation_algorithms import method

ROOT = base.ROOT
SRC = base.SRC

TESTS = r'''
 public static class TestTable implements se.krka.kahlua.vm.KahluaTable {
  final Map<Object,Object> values=new LinkedHashMap<>();
  public void rawset(Object key,Object value){if(value==null)values.remove(key);else values.put(key,value);}
  public Object rawget(Object key){return values.get(key);}
  public boolean isEmpty(){return values.isEmpty();}
  public se.krka.kahlua.vm.KahluaTableIterator iterator(){
   var it=values.entrySet().iterator();return new se.krka.kahlua.vm.KahluaTableIterator(){
    Map.Entry<Object,Object> current;public boolean advance(){if(!it.hasNext())return false;current=it.next();return true;}
    public Object getKey(){return current.getKey();}public Object getValue(){return current.getValue();}
   };
  }
 }
 static TestTable table(Object... pairs){var table=new TestTable();for(int i=0;i<pairs.length;i+=2)table.rawset(pairs[i],pairs[i+1]);return table;}
 static class Impostor {public Object iterator(){throw new AssertionError("unsupported object's iterator called");}}
 public static class BrokenTable extends TestTable {
  public se.krka.kahlua.vm.KahluaTableIterator iterator(){throw new NullPointerException("PRIVATE_VALUE token=secret");}
 }
 static void rejected(Runnable call,String code){try{call.run();throw new AssertionError("accepted "+code);}catch(IllegalStateException expected){check(expected.getMessage().equals(code),"explicit rejection: "+code);}}
 static List<ApocBRPlayerRelationalCapture.Row> refs(ApocBRPlayerRelationalCapture.Graph graph){return graph.rows().stream().filter(r->r.table().equals("value_node")&&r.values().get("value_kind").equals("reference")).toList();}
 static void referenceTests()throws Exception {
  P player=new P();var root=new zombie.inventory.ItemContainer();player.inventory=root;
  var first=new zombie.inventory.InventoryItem(1,"first");var clothing=new zombie.inventory.types.Clothing(2,"jacket");
  var bag=new zombie.inventory.types.InventoryContainer(3,"bag");var nested=new zombie.inventory.types.Clothing(4,"nested");
  bag.container.items.add(nested);root.items.addAll(List.of(first,clothing,bag));
  // Forward, backward, self and nested ownership references, shared through actual Kahlua tables.
  first.table=table("later",clothing,"self",first,"container",root,"player",player,"nested",nested);
  clothing.table=table("earlier",first);
  var shared=table("clothing",clothing);
  player.table=table("left",shared,"right",shared,"bag",bag,"contents",bag.container,"number",2.0,"label","ok");
  UUID character=UUID.randomUUID(),alias=UUID.randomUUID();
  var snapshot=ApocBRPlayerRelationalSnapshot.copyLive(player);
  detached(snapshot.player(),Collections.newSetFromMap(new IdentityHashMap<>()));
  var graph=new ApocBRPlayerRelationalCapture(character,Map.of(2,alias),Long.MAX_VALUE).capture(snapshot,List.of());
  check(graph.issues().isEmpty()&&graph.items()==4&&graph.containers()==2,"reference traversal preserves the owned graph");
  check(refs(graph).size()==10,"all nested typed references become reference leaves");
  check(refs(graph).stream().filter(r->r.values().get("reference_object_id").equals(alias)).count()==3,"owned clothing references reuse its persisted alias");
  Set<Object> objects=new HashSet<>();for(var row:graph.rows())if(row.table().equals("object"))objects.add(row.values().get("id"));
  check(refs(graph).stream().allMatch(r->objects.contains(r.values().get("reference_object_id"))),"every reference targets an object in this snapshot");
  check(graph.rows().stream().noneMatch(r->r.table().equals("item")&&r.values().get("name").equals("copy")),"reference routing never adds another inventory object");
  var direct=new ApocBRPlayerRelationalCapture(character,Map.of(2,alias),Long.MAX_VALUE).capture(player,List.of());
  check(canonical(direct).equals(canonical(graph)),"direct and detached reference graphs agree");
  clothing.condition=7;((TestTable)first.table).rawset("later",first);
  var frozen=new ApocBRPlayerRelationalCapture(character,Map.of(2,alias),Long.MAX_VALUE).capture(snapshot,List.of());
  check(canonical(frozen).equals(canonical(graph)),"post-handoff live mutations cannot change reference targets or data");

  // Real journal encode/decode applies the production deserialization filter.
  var batch=new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),character,Map.of(),java.time.Instant.EPOCH,snapshot,1);
  var entry=new ApocBRPersistenceJournal.Entry(UUID.randomUUID(),1,new ApocBRPersistenceOperations.Live(batch));
  var encode=ApocBRPersistenceJournal.class.getDeclaredMethod("encode",ApocBRPersistenceJournal.Entry.class);encode.setAccessible(true);
  var decode=ApocBRPersistenceJournal.class.getDeclaredMethod("decode",byte[].class);decode.setAccessible(true);
  byte[] bytes=(byte[])encode.invoke(null,entry);var restored=(ApocBRPersistenceJournal.Entry)decode.invoke(null,bytes);
  var restoredBatch=(ApocBRPlayerRelationalMutations.Batch)((ApocBRPersistenceOperations.Live)restored.operation()).batch();
  var replayed=new ApocBRPlayerRelationalCapture(character,Map.of(2,alias),Long.MAX_VALUE).capture(restoredBatch.snapshot(),List.of());
  check(canonical(replayed).equals(canonical(graph)),"journal replay retains detached reference identity, including self references");

  // Convert UUIDs to ordinals just as Reader.SQL does; resolve to existing reconstructed objects.
  Map<UUID,Integer> ordinals=new HashMap<>();for(var row:graph.rows())for(Object value:row.values().values())if(value instanceof UUID id)ordinals.computeIfAbsent(id,k->ordinals.size()+1);
  Map<Integer,Object> reconstructed=new HashMap<>();for(Object object:objects)reconstructed.put(ordinals.get(object),new Object());
  Map<Integer,List<Map<String,Object>>> children=new HashMap<>();Map<String,Object> modDataRoot=null;
  Object playerComponent=null;for(var row:graph.rows())if(row.table().equals("component")&&row.values().get("owner_object_id").equals(character)&&row.values().get("kind").equals("lua.modData"))playerComponent=row.values().get("id");
  for(var row:graph.rows())if(row.table().equals("value_node")){
   var values=new LinkedHashMap<String,Object>();row.values().forEach((key,value)->values.put(key,value instanceof UUID id?ordinals.get(id):value));
   if(values.get("parent_id")!=null)children.computeIfAbsent((Integer)values.get("parent_id"),k->new ArrayList<>()).add(values);
   else if(row.values().get("component_id").equals(playerComponent))modDataRoot=values;
  }
  var decoded=(TestTable)StructuredReader.read(modDataRoot,children,reconstructed);
  var left=(TestTable)decoded.rawget("left");var right=(TestTable)decoded.rawget("right");
  check(left!=right&&left.rawget("clothing")==right.rawget("clothing"),"shared tables restore by value while object references restore by identity");
  check(left.rawget("clothing")==reconstructed.get(ordinals.get(alias)),"decoded clothing resolves the existing object rather than constructing a copy");
  check(decoded.rawget("number").equals(2.0)&&decoded.rawget("label").equals("ok"),"scalar table members survive beside references");
  var reference=new LinkedHashMap<>(refs(graph).getFirst().values());reference.put("id",999999);reference.put("reference_object_id",999998);
  try{StructuredReader.read(reference,Map.of(),reconstructed);throw new AssertionError("unresolved saved reference accepted");}catch(NullPointerException expected){check(expected.getMessage().equals("Unresolved structured reference"),"missing saved targets fail explicitly");}

  // Reference retargeting produces a targeted FK update, not a new inventory item.
  P changes=new P();var a=new zombie.inventory.InventoryItem(10,"a");var b=new zombie.inventory.InventoryItem(11,"b");changes.inventory.items.addAll(List.of(a,b));changes.table=table("selected",a);
  var before=liveGraph(changes,character);((TestTable)changes.table).rawset("selected",b);var after=liveGraph(changes,character);
  var delta=ApocBRPlayerRelationalDelta.diff(ApocBRPlayerRelationalDelta.index(before.rows()),ApocBRPlayerRelationalDelta.index(after.rows()));
  check(delta.size()==1&&delta.getFirst().key().table().equals("value_node")&&delta.getFirst().columns().equals(List.of("reference_object_id")),"reference retargeting updates only its FK");
  LiveFake fake=new LiveFake();fake.character=character;fake.reason="live_fields";fake.graphRows=before.rows();var database=new ApocBRPlayerRelationalDatabase(fake::open);
  var patch=new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),character,Map.of(),java.time.Instant.now());
  check(database.applyFields(patch,after),"typed references pass the normal JDBC commit path");
  check(fake.bound.keySet().stream().anyMatch(sql->sql.startsWith("UPDATE player_rel.value_node SET reference_object_id=?")),"JDBC binds a reference FK update");

  P unsupported=new P();unsupported.table=table("nested",table("bad",new Impostor()));
  rejected(()->ApocBRPlayerRelationalSnapshot.copyLive(unsupported),"UNSUPPORTED_STRUCTURED_VALUE");
  rejected(()->new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(unsupported,List.of()),"UNSUPPORTED_STRUCTURED_VALUE");
  P orphan=new P();orphan.table=table("outside",new zombie.inventory.types.Clothing(20,"outside"));
  var orphanGraph=liveGraph(orphan,character);
  check(orphanGraph.issues().isEmpty()&&refs(orphanGraph).isEmpty()&&orphanGraph.rows().stream().anyMatch(r->r.table().equals("value_node")&&r.values().get("value_kind").equals("null")),"external item reference becomes a typed null without blocking capture");
  check(canonical(new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(orphan,List.of())).equals(canonical(orphanGraph)),"direct and detached external reference policy agrees");
  // An equipment-only item is copied but is not an owned persistence target.
  var outside=new zombie.inventory.types.Clothing(21,"worn-only");orphan.wornItems.add(new Worn(outside));orphan.table=table("outside",outside);
  var orphanSnapshot=ApocBRPlayerRelationalSnapshot.copyLive(orphan);
  check(refs(new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(orphanSnapshot,List.of())).isEmpty(),"copied equipment-only reference does not become an owned target");
  P duplicates=new P();var kept=new zombie.inventory.types.Clothing(30,"kept");var dropped=new zombie.inventory.types.Clothing(30,"dropped");duplicates.inventory.items.addAll(List.of(kept,dropped));duplicates.table=table("dropped",dropped);
  var duplicateGraph=liveGraph(duplicates,character);check(duplicateGraph.items()==1&&refs(duplicateGraph).isEmpty(),"reference to rejected duplicate becomes null instead of rebinding to another item");
  P scoped=new P();var tracked=new zombie.inventory.types.Clothing(42,"tracked");scoped.inventory.items.add(tracked);scoped.table=table("item",tracked);
  UUID persistent=UUID.randomUUID();var tracker=new ApocBRPlayerRelationalSnapshot.ItemTracker(scoped);
  var original=ApocBRPlayerRelationalSnapshot.copyLive(scoped,tracker,character,Map.of(42,persistent));tracked.id=43;
  var renumbered=ApocBRPlayerRelationalSnapshot.copyLive(scoped,tracker,character,Map.of());
  var renumberedGraph=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(renumbered,List.of());
  check(refs(renumberedGraph).size()==1&&refs(renumberedGraph).getFirst().values().get("reference_object_id").equals(persistent),"owned modData reference retains UUID through native ID reassignment");
  scoped.inventory.items.clear();var departed=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(ApocBRPlayerRelationalSnapshot.copyLive(scoped,tracker,character,Map.of()),List.of());
  check(departed.items()==0&&refs(departed).isEmpty()&&((TestTable)scoped.table).rawget("item")==tracked,"departed target becomes null in persistence while its live modData reference remains untouched");
  scoped.inventory.items.add(tracked);var returned=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(ApocBRPlayerRelationalSnapshot.copyLive(scoped,tracker,character,Map.of()),List.of());
  check(refs(returned).size()==1&&!refs(returned).getFirst().values().get("reference_object_id").equals(persistent),"returning owned object starts a new lifecycle and modData binds its new UUID");
  P cyclic=new P();var cycle=table();cycle.rawset("cycle",cycle);cyclic.table=cycle;
  try{liveGraph(cyclic,character);throw new AssertionError("Lua cycle accepted");}catch(IllegalStateException expected){check(expected.getMessage().contains("Cyclic structured"),"table cycles still fail rather than becoming item references");}
  P broken=new P();broken.table=table("PRIVATE_KEY",new BrokenTable());
  try{ApocBRPlayerRelationalSnapshot.copyLive(broken);throw new AssertionError("null-pointer capture accepted");}
  catch(ApocBRPlayerRelationalSnapshot.CaptureFailure expected){
   String diagnostic=ApocBRPlayerRelationalMutations.writeFailure(expected);
   check(diagnostic.contains("snapshot.structured:depth[1]")&&diagnostic.contains("BrokenTable"),"nested capture diagnostics identify stage, depth and runtime type");
   check(diagnostic.contains("snapshot.player:live")&&diagnostic.contains("cause=java.lang.NullPointerException"),"capture retains outer context and original null-pointer cause");
   check(diagnostic.contains("BrokenTable.iterator:"),"null-pointer diagnostic retains the failing frame");
   check(!diagnostic.contains("PRIVATE_")&&!diagnostic.contains("secret"),"capture excludes modData keys, values and exception messages");
  }
  broken.table=null;var recovered=ApocBRPlayerRelationalSnapshot.copyLive(broken);
  check(new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(recovered,List.of()).issues().isEmpty(),"failed capture leaves later valid snapshots usable");
 }
 static void recordedMediaTests()throws Exception {
  check(!java.lang.reflect.Modifier.isStatic(zombie.radio.ZomboidRadio.class.getMethod("getRecordedMedia").getModifiers()),"fixture preserves native instance-method contract");
  check(ApocBRPlayerRelationalCapture.media((short)-1)==null&&zombie.radio.ZomboidRadio.lookups==0,"items without recorded media do not initialize the radio registry");
  check(ApocBRPlayerRelationalCapture.media((short)7).equals("fixture-vhs"),"capture resolves recorded media through the radio singleton instance");
  P player=new P();var bag=new zombie.inventory.types.InventoryContainer(100,"bag");
  for(int i=0;i<11;i++)player.inventory.items.add(new zombie.inventory.InventoryItem(i+1,"ordinary"));
  for(int i=0;i<13;i++)bag.container.items.add(new zombie.inventory.InventoryItem(i+20,"ordinary"));
  var tape=new zombie.inventory.types.ComboItem(101,"VHS");tape.recordedMediaIndex=7;bag.container.items.add(tape);player.inventory.items.add(bag);
  var snapshot=ApocBRPlayerRelationalSnapshot.copyLive(player);UUID character=UUID.randomUUID();
  var graph=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(snapshot,List.of());
  check(graph.issues().isEmpty()&&graph.items()==26&&graph.rows().stream().anyMatch(r->r.table().equals("item")&&Integer.valueOf(101).equals(r.values().get("native_id"))&&"fixture-vhs".equals(r.values().get("recorded_media_id"))),"nested ComboItem with media index captures and projects completely");
  var direct=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(player,List.of());
  check(canonical(direct).equals(canonical(graph)),"direct and detached media identity agree");
  LiveFake fake=new LiveFake();fake.character=character;fake.reason="live_fields";fake.graphRows=liveGraph(new P(),character).rows();
  check(new ApocBRPlayerRelationalDatabase(fake::open).applyFields(new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),character,Map.of(),java.time.Instant.EPOCH),graph),"recorded media graph reaches normal JDBC commit");
  check(fake.bound.entrySet().stream().filter(e->e.getKey().startsWith("INSERT INTO player_rel.item (")).flatMap(e->e.getValue().stream()).anyMatch(row->row.containsValue("fixture-vhs")),"JDBC writes stable recorded media identity");
  check(StructuredReader.media(null)==-1&&StructuredReader.media("fixture-vhs")==7,"reconstruction resolves saved media ID through the same instance API");
  rejected(()->ApocBRPlayerRelationalCapture.media((short)99),"Unresolved recorded media index");
  rejected(()->StructuredReader.media("missing"),"Missing saved recorded media");
 }
 public static void main(String[] args)throws Exception {referenceTests();recordedMediaTests();System.out.println("ModData/media checks="+checks);}
'''


def fixture():
    harness = base.fixture()
    harness = harness.replace(" static class Item {", " public static class Item {")
    harness = harness.replace("  Item(int id){", "  public Item(int id){")
    harness = harness.replace(" static class Container {", " public static class Container {")
    tree = ast.parse((ROOT / "tools/test_postgres_player_relational.py").read_text())
    full = next(ast.literal_eval(node.value) for node in tree.body
                if isinstance(node, ast.Assign)
                and any(isinstance(t, ast.Name) and t.id == "HARNESS" for t in node.targets))
    return harness.replace(method(harness, "public static void main"), method(full, "static void detached(") + TESTS)


def reader():
    source = (SRC / "ApocBRPlayerRelationalReconstruction.java").read_text()
    signatures = (
        "private static short mediaIndex(", "private static Object staticCall(", "private static Class<?> type(",
        "private static int id(", "private static void add(", "private static void put(",
        "private static Object value(Map<String,Object> row,Map<Integer,List<Map<String,Object>>> children,Set<Integer> visited,int depth)",
        "private static Object value(Map<String,Object> row,Map<Integer,List<Map<String,Object>>> children,Set<Integer> visited,int depth,Map<Integer,Object> references)",
    )
    return """package zombie.savefile;import java.util.*;import java.lang.reflect.*;import static zombie.savefile.ApocBRPlayerRelationalAccess.*;
final class StructuredReader {
static short media(Object id){return mediaIndex(id);}
static Object read(Map<String,Object> row,Map<Integer,List<Map<String,Object>>> children,Map<Integer,Object> references){return value(row,children,new HashSet<>(),0,references);}
""" + "\n".join(method(source, signature) for signature in signatures) + "\n}"


def main():
    stubs = {**base.STUBS,
        "zombie/radio/ZomboidRadio.java": '''package zombie.radio;public class ZomboidRadio {
public static int lookups;private static final ZomboidRadio instance=new ZomboidRadio();
public static ZomboidRadio getInstance(){lookups++;return instance;}
public Media getRecordedMedia(){return new Media();}
public static class Media {public Data getMediaDataFromIndex(short index){return index==7?new Data():null;}public Data getMediaData(String id){return "fixture-vhs".equals(id)?new Data():null;}}
public static class Data {public String id="fixture-vhs";public short index=7;}
}''',
        "zombie/inventory/types/ComboItem.java": '''package zombie.inventory.types;public class ComboItem extends zombie.inventory.InventoryItem {public ComboItem(int id,String name){super(id,name);}}''',
        "zombie/inventory/InventoryItem.java": '''package zombie.inventory;public class InventoryItem extends zombie.savefile.RelationalTest.Item {
public ItemContainer container;public InventoryItem(int id,String name){super(id);this.name=name;}
public void save(java.nio.ByteBuffer bytes,boolean net){}
}''',
        "zombie/inventory/ItemContainer.java": "package zombie.inventory;public class ItemContainer extends zombie.savefile.RelationalTest.Container {}",
        "zombie/inventory/types/Clothing.java": '''package zombie.inventory.types;public class Clothing extends zombie.inventory.InventoryItem {
public java.util.Map<Object,Object> patches=java.util.Map.of();public String spriteName="jacket";public float dirtyness,bloodLevel,wetness,lastWetnessUpdate;public Clothing(int id,String name){super(id,name);}
}''',
        "zombie/inventory/types/InventoryContainer.java": '''package zombie.inventory.types;public class InventoryContainer extends zombie.inventory.InventoryItem {
public zombie.inventory.ItemContainer container=new zombie.inventory.ItemContainer();public int weightReduction=75;public InventoryContainer(int id,String name){super(id,name);}
}''',
        "zombie/Lua/LuaManager.java": '''package zombie.Lua;public class LuaManager {public static final Platform platform=new Platform();public static class Platform {public Object newTable(){return new zombie.savefile.RelationalTest.TestTable();}}}''',
    }
    sources = [SRC / (name + ".java") for name in (
        "ApocBRPayloadProvenance", "ApocBRPlayerRelationalLimits", "ApocBRPlayerRelationalSchema",
        "ApocBRPlayerRelationalAccess", "ApocBRPlayerRelationalSnapshot", "ApocBRPlayerRelationalCodecs",
        "ApocBRPlayerRelationalCapture", "ApocBRPlayerRelationalMutations", "ApocBRPlayerRelationalDelta",
        "ApocBRPlayerRelationalMetrics", "ApocBRPlayerRelationalDatabase",
    )]
    with tempfile.TemporaryDirectory(prefix="apocbr-moddata-references-") as folder:
        work = Path(folder)
        files = {**stubs, "zombie/savefile/RelationalTest.java": fixture(), "zombie/savefile/StructuredReader.java": reader()}
        for relative, content in files.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(path)
        sources += inactive_persistence_stubs(work)
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), *map(str, sources)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.savefile.RelationalTest"], check=True)


if __name__ == "__main__":
    main()
