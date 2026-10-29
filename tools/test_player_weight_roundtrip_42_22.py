"""42.22 weight round trips: production Snapshot/Capture/mutation queue/Delta SQL,
extracted reconstruction and native food/weight methods. Engine/script/JDBC
transport doubles; no live DB, deployment, or complete native player login.
"""
import ast
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_player_packet_algorithms import extract_method
from postgres_test_support import inactive_persistence_stubs

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.22.0"
SRC = VERSION / "src/zombie/savefile"
GAME = Path(os.environ.get("APOCBR_TEST_GAME_JAR", r"Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar"))


def main():
    tree = ast.parse((ROOT / "tools/test_postgres_player_relational.py").read_text(encoding="utf-8"))
    contract = next(n.value for n in ast.walk(tree) if isinstance(n, ast.Constant)
                    and isinstance(n.value, str) and "public class RelationalTest {" in n.value)
    models = contract[contract.index(" static class Color"):contract.index(" static void captures()")]
    models = models.replace("static class Item {", "public static class Item {")
    models = models.replace("public int currentUses=0;", "public int uses=10; public int getCurrentUses(){return uses;}")
    models = models.replace("public float actualWeight=0f;", "public float actualWeight=2f,weight=2f; public boolean broken;")
    models = models.replace("Item(int id)", "public Item(int id)")
    native = (VERSION / "decompiled/zombie/inventory/InventoryItem.java").read_text()
    food = (VERSION / "decompiled/zombie/inventory/types/Food.java").read_text()
    character = (VERSION / "decompiled/zombie/characters/IsoGameCharacter.java").read_text()
    drain = (VERSION / "decompiled/zombie/inventory/types/DrainableComboItem.java").read_text()
    reconstruction = (SRC / "ApocBRPlayerRelationalReconstruction.java").read_text()
    inventory = '''package zombie.inventory;
import java.nio.*;import java.io.*;import zombie.core.math.PZMath;
public class InventoryItem extends zombie.savefile.WeightRoundTripTest.Item {
 public InventoryItem(){super(1);}public String getDisplayName(){return "Fixture";}
 public int getConditionMax(){return 10;}public int getID(){return id;}
 public void save(ByteBuffer b,boolean net){} public float getExtraItemsWeight(){return extraItems.size()*0.6f;}
 public String getReplaceOnUse(){return null;}public String getReplaceOnUseFullType(){return getReplaceOnUse();}
''' + "\n".join(extract_method(native, s) for s in (
        "public float getWeight()", "public void setWeight(float weight)",
        "public float getActualWeight()", "public float getActualWeightUnmodded()",
        "public void setActualWeight(float actualWeight)", "public boolean isCustomWeight()",
        "public void setCustomWeight(boolean custom)", "public void setCurrentUses(int newuses)",
        "public void setConditionWhileLoading(int condition)")) + "}"
    restore = '''package zombie.savefile;
import java.util.*;import java.lang.reflect.*;import java.nio.*;import zombie.inventory.InventoryItem;
import static zombie.savefile.ApocBRPlayerRelationalAccess.*;
final class ApocBRPlayerRelationalReconstruction {
 final ApocBRPlayerRelationalReader.Profile source;final Map<Integer,Object> objects=new HashMap<>();
 ApocBRPlayerReconstructionLog.Attempt trace;
 ApocBRPlayerRelationalReconstruction(ApocBRPlayerRelationalReader.Profile p){source=p;}
 List<Map<String,Object>> rows(String name){return source.rows(name);}
 static int id(Object value){return ((Number)value).intValue();}Object ref(Object key){return Objects.requireNonNull(objects.get(id(key)));}
 static Object registry(String name,Object key){throw new AssertionError("registry outside fixture");}
 static void restoreComponent(Object o,String n,Object v){throw new AssertionError("entity outside fixture");}
 static void restoreAnimal(Object o,Object v){throw new AssertionError("animal outside fixture");}
 static void add(Object o,Object v){call(o,"add",v);}static void put(Object o,Object k,Object v){call(o,"put",k,v);}
''' + "\n".join(extract_method(reconstruction, s) for s in (
        "static void project(String table,Object target,Map<String,Object> row,String skip)",
        "static void restoreItemScalars(InventoryItem item,Map<String,Object> row)",
        "static void restoreItemWeight(InventoryItem item,Object value)", "private void components()",
        "private void restoreItemSequence(String[] mapping,Map<String,Object> row,boolean first)",
        "private static Object value(Map<String,Object> row,Map<Integer,List<Map<String,Object>>> children,Set<Integer> visited,int depth,Map<Integer,Object> references)",
        "static void set(Object object,String path,Object value)", "static Object convert(Class<?> type,Object value)")) + "}"
    schema = (SRC / "ApocBRPlayerRelationalSchema.java").read_text()
    body = re.search(r'TABLES.put\("food_state", List.of\((.*?)\)\);', schema).group(1)
    fields = []
    for name, kind in re.findall(r'new Column\("([^"]+)","([^"]+)"', body):
        if name in ("snapshot_id", "item_id"):
            continue
        path = {"frozen": "isFrozen", "fertilized": "isFertilized"}.get(name, re.sub(r'_([a-z])', lambda m: m[1].upper(), name))
        fields.append(f'public { {"real":"float","integer":"int","text":"String","boolean":"boolean"}[kind]} {path};')
    food_methods = "\n".join(extract_method(food, s) for s in ("public float getActualWeight()", "public float getWeight()", "public void multiplyFoodValues(float percentage)"))
    # Script dependencies are adapted; arithmetic and control flow remain target source.
    food_methods = food_methods.replace("Item emptyItem", "ScriptItem emptyItem").replace("ScriptManager", "TestScripts")
    getters = '''public float getHungChange(){return hungChange;}public float getBaseHunger(){return baseHunger;}
 public float getThirstChange(){return thirstChange;}public boolean haveExtraItems(){return !extraItems.isEmpty();}
 public String replacement;public String getReplaceOnUse(){return replacement;}
 public ScriptItem getScriptItem(){return script;}public ScriptItem script=new ScriptItem(2,-100,0);
 public Map<Object,Object> eggGenome=new HashMap<>();public List<String> spices=new ArrayList<>();
'''
    pairs = {"BoredomChangeUnmodified":"boredomChange", "UnhappyChangeUnmodified":"unhappyChange", "HungChange":"hungChange",
             "FluReduction":"fluReduction", "ThirstChangeUnmodified":"thirstChange", "PainReduction":"painReduction",
             "FoodSicknessChange":"foodSicknessChange", "EnduranceChangeUnmodified":"endChange", "StressChangeUnmodified":"stressChange",
             "FatigueChange":"fatigueChange", "Calories":"calories", "Carbohydrates":"carbohydrates", "Proteins":"proteins",
             "Lipids":"lipids", "PoisonPower":"poisonPower"}
    for getter, field in pairs.items():
        kind = "int" if field in ("fluReduction", "foodSicknessChange", "poisonPower") else "float"
        if getter != "HungChange":
            getters += f'public {kind} get{getter}(){{return {field};}}'
        setter = field[0].upper()+field[1:]
        getters += f'public void set{setter}({kind} v){{{field}=v;}}'
    eat_start = character.index("public boolean Eat(InventoryItem info, float percentage, boolean useUtensil)")
    prelude_start = character.index("            float originalPercent = percentage;", eat_start)
    prelude_end = character.index("            this.stats.add", prelude_start)
    start = character.index("            if (percentage == 1.0F)", prelude_end)
    end = start + re.search(r'(?m)^            return true;', character[start:]).end()
    eat = (character[prelude_start:prelude_end] + character[start:end]).replace("Item emptyItem", "ScriptItem emptyItem").replace("ScriptManager", "TestScripts")
    java = '''package zombie.savefile;
import java.util.*;import java.lang.reflect.*;import java.sql.*;import java.io.*;import zombie.inventory.InventoryItem;import zombie.core.math.PZMath;
public class WeightRoundTripTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void near(float a,float b,String why){check(Math.abs(a-b)<0.00001f,why+": "+a+" / "+b);}
''' + models + '''
 static class ScriptItem {float mass,hunger,thirst;ScriptItem(float m,float h,float t){mass=m;hunger=h;thirst=t;}
 public float getActualWeight(){return mass;}public float getHungerChange(){return hunger;}public float getThirstChange(){return thirst;}}
 static class TestScripts {static TestScripts instance=new TestScripts();ScriptItem getItem(String type){return new ScriptItem(0.5f,0,0);}}
 static class Food extends InventoryItem {
''' + "\n".join(fields) + getters + food_methods + '''public void syncItemFields(){}boolean consumed;public void UseAndSync(){consumed=true;}
 }
 static boolean eat(Food food,float percentage){
''' + eat + '''}
 static class Drainable extends InventoryItem {
 float useDelta=0.1f,weightEmpty=0;String replaceOnDepleteFullType="Base.Empty";
 String getReplaceOnDeplete(){return replaceOnDepleteFullType;}ScriptItem getScriptItem(){return new ScriptItem(2,0,0);}
 float getWeightEmpty(){return weightEmpty;}
''' + "\n".join(extract_method(drain, s) for s in ("public void setCurrentUses(int newuses)", "public float getCurrentUsesFloat()", "public void updateWeight()")) .replace("Item emptyItem", "ScriptItem emptyItem").replace("ScriptManager", "TestScripts") + '''}
''' + HARNESS + "}"
    with tempfile.TemporaryDirectory(prefix="apocbr_weight_4222_") as folder:
        target = Path(folder)
        def write(name, value):
            p = target / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(value, encoding="utf-8")
            return p
        fixtures = [write("WeightRoundTripTest.java", java), write("InventoryItem.java", inventory),
                    write("ApocBRPlayerRelationalReconstruction.java", restore)]
        fixtures.append(write("ApocBRPostgresConnector.java", '''package zombie.savefile; public class ApocBRPostgresConnector {
 public static int dictionaryPublicationTimeoutSeconds(){return 30;}
 public static String sqlDiagnostic(Throwable e,boolean hints){return e.getClass().getSimpleName();}
 public static java.sql.Connection borrowDictionaryConnection()throws java.sql.SQLException{throw new java.sql.SQLException();}
 public static java.sql.Connection borrowPlayerConnection()throws java.sql.SQLException{throw new java.sql.SQLException();}}'''))
        names = ["Limits", "Schema", "Access", "Snapshot", "Codecs", "Capture", "Mutations", "Delta", "Metrics", "Database", "Reader"]
        sources = [SRC / "ApocBRPayloadProvenance.java", SRC / "ApocBRPlayerReconstructionLog.java"]
        sources += [SRC / f"ApocBRPlayerRelational{n}.java" for n in names]
        sources += [SRC.parent / p for p in ("world/ApocBRWorldDictionaryDecode.java", "world/ApocBRWorldDictionarySnapshot.java", "savefile/ApocBRWorldDictionaryDatabase.java")]
        sources += inactive_persistence_stubs(target)
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-cp", str(GAME), "-d", folder, *map(str, sources+fixtures)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", folder+os.pathsep+str(GAME), "zombie.savefile.WeightRoundTripTest"], check=True)


HARNESS = r'''
 static final UUID character=UUID.randomUUID(),head=UUID.randomUUID();
 static final ApocBRPlayerRelationalDatabase.Candidate candidate=new ApocBRPlayerRelationalDatabase.Candidate(UUID.randomUUID(),"fixture","world","player",false,0,0,251,null,List.of());
 static ApocBRPlayerRelationalCapture.Graph graph(P player)throws Exception {
  var snapshot=ApocBRPlayerRelationalSnapshot.copyLive(player);
  var bytes=new ByteArrayOutputStream();try(var out=new ObjectOutputStream(bytes)){out.writeObject(snapshot);}
  try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){snapshot=(ApocBRPlayerRelationalSnapshot.Snapshot)in.readObject();}
  var queue=new ApocBRPlayerRelationalMutations.DirtyQueue();UUID session=UUID.randomUUID();
  check(queue.register(candidate.key(),session),"mutation session");queue.bind(candidate.key(),session,character);
  check(queue.snapshot(candidate,session,snapshot,0),"detached mutation admitted");
  var batch=queue.poll(Long.MAX_VALUE/2);check(batch!=null&&batch.snapshot()!=null,"mutation batch retains graph");
  var graph=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(batch.snapshot(),List.of());
  ApocBRPlayerRelationalCapture.requireComplete(graph,"weight_fixture");return graph;
 }
 // Transport double executes the SQL and bindings produced by the real Delta.
 static class Store implements InvocationHandler {
  Map<ApocBRPlayerRelationalDelta.Key,Map<String,Object>> rows=new LinkedHashMap<>();int writes;
  Connection connection(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},this);}
  public Object invoke(Object proxy,Method method,Object[] args){
   if(method.getName().equals("prepareStatement")){String sql=(String)args[0];var parameters=new HashMap<Integer,Object>();var batches=new ArrayList<Map<Integer,Object>>();
    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
     switch(m.getName()){
      case "setObject":parameters.put((Integer)a[0],a[1]);break;
      case "addBatch":batches.add(new HashMap<>(parameters));break;
      case "clearBatch":batches.clear();break;
      case "executeBatch":int[] result=new int[batches.size()];int n=0;for(var values:batches){execute(sql,values);result[n++]=1;}return result;
     }return null;});}return null;
  }
  void execute(String sql,Map<Integer,Object> values){writes++;
   String table=sql.substring(sql.indexOf("player_rel.")+11).split("[ (]")[0];
   if(sql.startsWith("INSERT")){String[] columns=sql.substring(sql.indexOf('(')+1,sql.indexOf(')')).split(",");var row=new LinkedHashMap<String,Object>();
    for(int i=0;i<columns.length;i++)row.put(columns[i],values.get(i+1));check(rows.put(ApocBRPlayerRelationalDelta.key(table,row),row)==null,"insert unique row");return;}
   String[] columns=sql.startsWith("UPDATE")?sql.substring(sql.indexOf(" SET ")+5,sql.indexOf(" WHERE ")).replace("=?","").split(","):new String[0];
   var keyValues=new ArrayList<Object>();for(int i=0;i<ApocBRPlayerRelationalDelta.KEYS.get(table).size();i++)keyValues.add(values.get(columns.length+2+i));
   var key=new ApocBRPlayerRelationalDelta.Key(table,keyValues);var row=rows.get(key);check(row!=null,"mutation locates row");
   if(sql.startsWith("DELETE")){rows.remove(key);return;}for(int i=0;i<columns.length;i++)row.put(columns[i],values.get(i+1));
  }
  void save(ApocBRPlayerRelationalCapture.Graph graph)throws Exception{
   var next=ApocBRPlayerRelationalDelta.index(graph.rows());var delta=ApocBRPlayerRelationalDelta.diff(rows,next);
   ApocBRPlayerRelationalDelta.apply(connection(),head,delta,Long.MAX_VALUE);
   check(ApocBRPlayerRelationalDelta.diff(rows,next).isEmpty(),"SQL row state equals mutation projection");
  }
  ApocBRPlayerRelationalReader.Profile profile(){
   Map<UUID,Integer> ids=new HashMap<>();for(var row:rows.values())for(var v:row.values())if(v instanceof UUID uuid)ids.computeIfAbsent(uuid,k->ids.size()+1);
   Map<String,List<Map<String,Object>>> tables=new HashMap<>();
   for(var entry:rows.entrySet()){var mapped=new LinkedHashMap<String,Object>();entry.getValue().forEach((k,v)->mapped.put(k,v instanceof UUID uuid?ids.get(uuid):v));
    var normalized=new org.json.JSONObject(mapped).toMap();tables.computeIfAbsent(entry.getKey().table(),k->new ArrayList<>()).add(normalized);}
   return new ApocBRPlayerRelationalReader.Profile(tables);
  }
 }
 static <T extends InventoryItem>T restore(Store store,T target,long seed)throws Exception{
  var profile=store.profile();var reconstruction=new ApocBRPlayerRelationalReconstruction(profile);
  var row=profile.rows("item").stream().filter(r->((Number)r.get("native_id")).intValue()==target.id).findFirst().orElseThrow();var keys=new ArrayList<>(row.keySet());Collections.shuffle(keys,new Random(seed));var shuffled=new LinkedHashMap<String,Object>();keys.forEach(k->shuffled.put(k,row.get(k)));
  ApocBRPlayerRelationalReconstruction.project("item",target,shuffled,"id full_type runtime_class save_type source_registry_id texture_name recorded_media_id source_recorded_media_index");
  reconstruction.objects.put(((Number)row.get("id")).intValue(),target);
  for(var f:profile.rows("food_state"))ApocBRPlayerRelationalReconstruction.project("food_state",target,f,"item_id");
  target.extraItems.clear();var sequence=ApocBRPlayerRelationalReconstruction.class.getDeclaredMethod("restoreItemSequence",String[].class,Map.class,boolean.class);sequence.setAccessible(true);boolean first=true;
  for(var r:profile.rows("item_extra")){sequence.invoke(reconstruction,new String[]{"item_extra","extraItems","full_type"},r,first);first=false;}
  var method=ApocBRPlayerRelationalReconstruction.class.getDeclaredMethod("components");method.setAccessible(true);method.invoke(reconstruction);
  return target;
 }
 static Food food(boolean custom,boolean mixed,boolean replacement){var f=new Food();f.customWeight=custom;f.hungChange=-1;f.baseHunger=-1;f.calories=1000;
  if(mixed)f.extraItems.add("Base.Ingredient");if(replacement)f.replacement="Base.Empty";return f;}
 static void foods()throws Exception{
  for(boolean custom:List.of(false,true))for(boolean mixed:List.of(false,true))for(boolean replacement:List.of(false,true)){
   P p=new P();Food live=food(custom,mixed,replacement);p.inventory.items.add(live);var store=new Store();store.save(graph(p));
   float previous=live.getActualWeight();eat(live,0.25f);float expected=live.getActualWeight(),weight=live.getWeight();
   check(expected<=previous,"eating never increases actual food mass in fixture");store.save(graph(p));
   check(store.rows.values().stream().filter(r->"item.weight".equals(r.get("kind"))).count()==(custom?1:0),"custom weight extension presence");
   for(int seed=0;seed<12;seed++){Food loaded=restore(store,food(custom,mixed,replacement),seed);near(loaded.getActualWeight(),expected,"food actual mass round trip");near(loaded.getWeight(),weight,"food weight round trip");near(loaded.weight,live.weight,"raw food weight");near(loaded.actualWeight,live.actualWeight,"raw food actualWeight avoids double reduction");near(loaded.hungChange,live.hungChange,"remaining hunger");near(loaded.calories,750,"remaining calories");}
   Food loaded=restore(store,food(custom,mixed,replacement),33);eat(loaded,0.25f);eat(live,0.25f);p.inventory.items.set(0,loaded);store.save(graph(p));
   Food again=restore(store,food(custom,mixed,replacement),44);near(again.getActualWeight(),live.getActualWeight(),"second bite actual mass");near(again.getWeight(),live.getWeight(),"second bite stored weight");
  }
 }
 static void cases()throws Exception{
  var p=new P();var live=new InventoryItem();live.customWeight=true;live.actualWeight=0.5f;live.weight=0.3f;p.inventory.items.add(live);var store=new Store();store.save(graph(p));
  var restored=restore(store,new InventoryItem(),1);near(restored.weight,0.3f,"independent weight preserved");near(restored.actualWeight,0.5f,"raw actual preserved");check(restored.broken,"zero condition establishes broken flag");
  var detached=ApocBRPlayerRelationalSnapshot.copyLive(p);live.weight=9;
  store.save(new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(detached,List.of()));near(restore(store,new InventoryItem(),0).weight,0.3f,"snapshot owns independent weight after live mutation");
  live.weight=0.2f;store.save(graph(p));near(restore(store,new InventoryItem(),2).weight,0.2f,"weight-only mutation reaches SQL and restore");
  live.customWeight=false;live.actualWeight=1;store.save(graph(p));restored=restore(store,new InventoryItem(),3);near(restored.actualWeight,2,"noncustom current definition");near(restored.weight,2,"noncustom factory weight");check(!restored.customWeight,"custom cleared");
  var d=new Drainable();d.setCurrentUses(5);p.inventory.items.set(0,d);store=new Store();store.save(graph(p));d.setCurrentUses(4);d.actualWeight=1.8f;d.weight=0.7f;store.save(graph(p));
  for(int seed=0;seed<30;seed++){var out=restore(store,new Drainable(),seed);near(out.actualWeight,d.actualWeight,"drainable actual order invariant");near(out.weight,d.weight,"drainable weight order invariant");check(out.uses==4,"drainable uses");}
  store.rows.entrySet().removeIf(e->Set.of("component","value_node","object").contains(e.getKey().table())&&(!e.getKey().table().equals("object")||"component".equals(e.getValue().get("kind"))));
  var legacy=restore(store,new Drainable(),0);near(legacy.weight,d.actualWeight,"old relational rows remain readable");
  var snapshot=ApocBRPlayerRelationalSnapshot.copyLive(p);var container=(ApocBRPlayerRelationalSnapshot.Node)snapshot.player().get("inventory");
  var node=(ApocBRPlayerRelationalSnapshot.Node)((List<?>)container.get("items")).getFirst();node.itemWeight=null;
  var oldGraph=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(snapshot,List.of());
  check(oldGraph.rows().stream().noneMatch(r->"item.weight".equals(r.values().get("kind"))),"old journal does not invent weight");
  var malformed=new InventoryItem();malformed.customWeight=true;
  for(Object invalid:List.of(-1f,Float.NaN,Float.POSITIVE_INFINITY,"bad"))try{ApocBRPlayerRelationalReconstruction.restoreItemWeight(malformed,invalid);throw new AssertionError("bad weight accepted");}catch(IllegalStateException expected){checks++;}
  var thirst=food(false,false,true);thirst.hungChange=thirst.baseHunger=0;thirst.thirstChange=-1;thirst.script=new ScriptItem(2,0,-100);p.inventory.items.set(0,thirst);store=new Store();store.save(graph(p));eat(thirst,0.25f);store.save(graph(p));
  var loadedThirst=food(false,false,true);loadedThirst.script=new ScriptItem(2,0,-100);var outThirst=restore(store,loadedThirst,1);near(outThirst.getActualWeight(),thirst.getActualWeight(),"thirst-only food mass");near(outThirst.thirstChange,-0.75f,"thirst-only remaining values");
  thirst.customWeight=true;store.save(graph(p));check(!store.profile().rows("component").isEmpty(),"weight component present before consumption");
  eat(thirst,1);check(thirst.consumed,"native full-consumption branch calls UseAndSync");
  // UseAndSync's container/network effects are an engine boundary; reflect its removal in the live container.
  p.inventory.items.clear();store.save(graph(p));check(store.profile().rows("item").isEmpty(),"fully consumed item deleted by mutation SQL");check(store.profile().rows("component").isEmpty(),"consumed weight components removed");
  var bag=new InventoryContainer(99);var nested=food(true,false,false);bag.container.items.add(nested);p.inventory.items.add(bag);store=new Store();store.save(graph(p));eat(nested,0.25f);store.save(graph(p));
  check(store.profile().rows("bag_state").size()==1&&store.profile().rows("item_location").size()==2,"nested bag ownership survives food mutation");
  near(restore(store,food(true,false,false),0).getWeight(),nested.getWeight(),"food eaten inside bag round trip");
 }
 public static void main(String[] args)throws Exception{foods();cases();System.out.println("42.22.0 weight mutation/reconstruction checks="+checks);}
'''

if __name__ == "__main__":
    main()
