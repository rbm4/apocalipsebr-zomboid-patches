"""42.22 component membership/equipment finalization. Compile complete target
reconstruction; native component dependencies use available game JAR. Extract
target hand-owner methods with registration/event instrumentation. No live world.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_player_packet_algorithms import extract_method
from postgres_test_support import inactive_persistence_stubs
from test_player_weight_roundtrip_42_22 import HARNESS as WEIGHT_HARNESS

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "42.22.0/src/zombie/savefile"
NATIVE = ROOT / "42.22.0/decompiled/zombie"
GAME = Path(os.environ.get("APOCBR_TEST_GAME_JAR", r"Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar"))


def main(extra_java="", extra_calls="", label="reconstruction lifecycle"):
    native_item = (NATIVE / "inventory/InventoryItem.java").read_text()
    native_character = (NATIVE / "characters/IsoGameCharacter.java").read_text()
    item_methods = "\n".join(extract_method(native_item, sig) for sig in (
        "public void setEquipParent(IsoGameCharacter parent, boolean register)",
        "public IsoGameCharacter getEquipParent()"))
    character_method = extract_method(native_character, "protected void setEquipParent(InventoryItem handItem, InventoryItem newHandItem, boolean register)")
    character_method = character_method.replace('boolean register) {', 'boolean register) { if(newHandItem instanceof HandWeapon&&register)weaponRegistrations++;', 1)
    probe = '''public IsoGameCharacter equipParent;int registered,equipped,unequipped;
 public void onEquip(boolean register){equipped++;if(register)registered++;}
 public void onUnEquip(){unequipped++;}
''' + item_methods
    java = '''package zombie.savefile;
import java.util.*;import java.lang.reflect.*;import java.sql.*;
import zombie.inventory.*;import zombie.inventory.types.HandWeapon;
import zombie.characters.*;import zombie.entity.*;
import static zombie.savefile.ApocBRPlayerRelationalAccess.*;
public class ReconstructionLifecycleTest {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static Object blank(Class<?> type)throws Exception{var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return ((sun.misc.Unsafe)f.get(null)).allocateInstance(type);}
 static Object invoke(Object owner,String name,Object... args)throws Exception{
  for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())for(var m:type.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);try{return m.invoke(owner,args);}catch(IllegalArgumentException mismatch){}}
  throw new NoSuchMethodException(name);
 }
 static class ProbeItem extends InventoryItem {ProbeItem(){super("Base","Fixture","Item","");}
''' + probe + '''}
 static class Player extends IsoPlayer {Player(){super((zombie.iso.IsoCell)null);}InventoryItem primary,secondary;HandWeapon active;int weaponRegistrations;
 public InventoryItem getPrimaryHandItem(){return primary;}public InventoryItem getSecondaryHandItem(){return secondary;}
 public void setUseHandWeapon(HandWeapon weapon){active=weapon;}
 public void setPrimaryHandItem(InventoryItem item){throw new AssertionError("public equip callback path");}
 public void setSecondaryHandItem(InventoryItem item){throw new AssertionError("public equip callback path");}
''' + character_method + '''}
''' + extract_method(WEIGHT_HARNESS, "static class Store implements InvocationHandler") + HARNESS.replace(
        'membership();markers();mutationRoundTrip();equipment();',
        'membership();markers();mutationRoundTrip();equipment();'+extra_calls).replace(
        'reconstruction lifecycle checks=', label+' checks=') + extra_java + "}"
    source = (SRC / "ApocBRPlayerRelationalReconstruction.java").read_text()
    player = extract_method(source, "private IsoPlayer player(int slot)")
    assert player.index('components();') < player.index('equipment(player);')
    assert player.index('VERIFY_TABLE_COVERAGE') < player.index('restoreEquipmentState(player,false)')
    server = (SRC / "ServerPlayerDB.java").read_text()
    finish = extract_method(server, "public IsoPlayer finishPostgresPlayer(")
    assert finish.index('registerPostgresPlayer') < finish.index('restoreEquipmentState(player,true)')
    assert 'restoreEquipmentState(player,true)' not in extract_method(server, "public ApocBRPlayerDatabase.Profile buildPostgresClientProfile(")
    with tempfile.TemporaryDirectory(prefix="apocbr_reconstruction_lifecycle_4222_") as folder:
        target = Path(folder)
        fixture = target / "ReconstructionLifecycleTest.java"
        fixture.write_text(java, encoding="utf-8")
        connector = target / "ApocBRPostgresConnector.java"
        connector.write_text('''package zombie.savefile; public class ApocBRPostgresConnector {
 public static int dictionaryPublicationTimeoutSeconds(){return 30;}
 public static String sqlDiagnostic(Throwable e,boolean hints){return e.getClass().getSimpleName();}
 public static java.sql.Connection borrowDictionaryConnection()throws java.sql.SQLException{throw new java.sql.SQLException();}
 public static java.sql.Connection borrowPlayerConnection()throws java.sql.SQLException{throw new java.sql.SQLException();}}''', encoding="utf-8")
        names = ["Limits", "Schema", "Access", "Snapshot", "Codecs", "Capture", "Mutations", "Delta", "Metrics", "Database", "Reader", "Reconstruction"]
        sources = [SRC / "ApocBRPayloadProvenance.java", SRC / "ApocBRPlayerReconstructionLog.java"]
        sources += [SRC / f"ApocBRPlayerRelational{n}.java" for n in names]
        sources += [SRC.parent / p for p in ("world/ApocBRWorldDictionaryDecode.java", "world/ApocBRWorldDictionarySnapshot.java", "savefile/ApocBRWorldDictionaryDatabase.java")]
        sources += inactive_persistence_stubs(target)
        sources += [NATIVE / "entity/components/spriteconfig/SpriteConfig.java", fixture, connector]
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-cp", str(GAME), "-d", folder, *map(str, sources)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", folder+os.pathsep+str(GAME), "zombie.savefile.ReconstructionLifecycleTest"], check=True)


HARNESS = r'''
 static final UUID character=UUID.randomUUID(),head=UUID.randomUUID(),itemIdentity=UUID.randomUUID();
 // A detached owner double records connection observations. Native components
 // provide actual state fields/factories; no live-world manager is initialized.
 static class Owner {
  List<Object> parts=new ArrayList<>();int connections,releases,adds;Set<String> expected;float expectedHP;
  public int getComponentSize(){return parts.size();}public Object getComponentForIndex(int i){return parts.get(i);}
  boolean releaseComponent(Object c){releases++;return parts.remove(c);}
  boolean addComponent(Object c){adds++;parts.add(c);if(c.getClass().getSimpleName().equals("Durability"))check(((Number)get(c,"currentHitPoints")).floatValue()==expectedHP,"new payload restored before onAdded");return true;}
  void connectComponents(){connections++;check(parts.stream().map(p->p.getClass().getSimpleName()).collect(java.util.stream.Collectors.toSet()).equals(expected),"connect sees final membership");
   for(var c:parts)if(c.getClass().getSimpleName().equals("Durability"))check(((Number)get(c,"currentHitPoints")).floatValue()==expectedHP,"connect sees complete saved state");}
 }
 static Map<String,Object> durability(float hp){return Map.of("currentHitPoints",hp,"maxHitPoints",20f,"material","Default");}
 static void membership()throws Exception {
  for(boolean reverse:List.of(false,true)){
   var owner=new Owner();owner.parts.add(ComponentType.UiConfig.CreateComponent());owner.parts.add(ComponentType.Durability.CreateComponent());
   owner.expected=Set.of("Durability","Signals");owner.expectedHP=12;
   var states=new LinkedHashMap<String,Object>();if(reverse)states.put("Signals",Map.of());states.put("Durability",durability(12));if(!reverse)states.put("Signals",Map.of());
   ApocBRPlayerRelationalReconstruction.restoreEntityComponents(owner,states);
   check(owner.connections==1&&owner.releases==1&&owner.adds==1,"single final connect, extras removed, missing added");
   owner.expected=Set.of();ApocBRPlayerRelationalReconstruction.restoreEntityComponents(owner,Map.of());check(owner.parts.isEmpty()&&owner.connections==2,"explicit empty removes every factory component");
  }
  var owner=new Owner();owner.expected=Set.of("Durability");owner.expectedHP=7;
  ApocBRPlayerRelationalReconstruction.restoreEntityComponents(owner,Map.of("Durability",durability(7)));check(owner.adds==1,"new state loaded before attachment");
  int releases=owner.releases;try{ApocBRPlayerRelationalReconstruction.restoreEntityComponents(owner,Map.of("NotAComponent",Map.of()));throw new AssertionError("unknown accepted");}catch(IllegalStateException expected){checks++;}
  check(owner.releases==releases&&owner.parts.size()==1,"unknown kind rejected before membership mutation");
 }
 static int nextNode=300;static void node(List<Map<String,Object>> nodes,int component,Integer parent,String key,Object value){
  int id=nextNode++;var r=new HashMap<String,Object>();r.put("id",id);r.put("component_id",component);r.put("parent_id",parent);r.put("position",id);if(key!=null){r.put("key_kind","string");r.put("key_text",key);}
  if(value instanceof Map<?,?> map){r.put("value_kind","object");nodes.add(r);map.forEach((k,v)->node(nodes,component,id,k.toString(),v));return;}
  if(value instanceof Boolean){r.put("value_kind","boolean");r.put("boolean_value",value);}else if(value instanceof Number){r.put("value_kind","number");r.put("number_value",value);}else{r.put("value_kind","string");r.put("text_value",value);}nodes.add(r);
 }
 static Object reconstruction(InventoryItem owner,List<String> kinds,List<Object> values)throws Exception{
  var components=new ArrayList<Map<String,Object>>();var nodes=new ArrayList<Map<String,Object>>();
  for(int i=0;i<kinds.size();i++){int id=100+i;components.add(Map.of("id",id,"owner_object_id",1,"kind",kinds.get(i),"coverage","complete","codec_version",4));node(nodes,id,null,null,values.get(i));}
  var profile=new ApocBRPlayerRelationalReader.Profile(Map.of("component",components,"value_node",nodes));
  var ctor=ApocBRPlayerRelationalReconstruction.class.getDeclaredConstructors()[0];ctor.setAccessible(true);var r=ctor.newInstance(profile,null);
  var f=ApocBRPlayerRelationalReconstruction.class.getDeclaredField("objects");f.setAccessible(true);((Map<Integer,Object>)f.get(r)).put(1,owner);return r;
 }
 static InventoryItem nativeOwner()throws Exception{
  var item=(InventoryItem)blank(InventoryItem.class);invoke(item,"addComponent",ComponentType.Durability.CreateComponent());return item;
 }
 static ApocBRPlayerRelationalCapture.Graph membershipGraph(Map<String,Object> states)throws Exception{
  var capture=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE);
  invoke(capture,"componentValue",itemIdentity,"item.entityComponents",true,"complete");
  for(var e:states.entrySet())invoke(capture,"componentValue",itemIdentity,"entity."+e.getKey(),e.getValue(),"complete");
  var rows=(List<ApocBRPlayerRelationalCapture.Row>)get(capture,"rows");
  return new ApocBRPlayerRelationalCapture.Graph(UUID.randomUUID(),character,java.time.Instant.now(),rows,List.of(),0,0,0,0,0);
 }
 static InventoryItem restoreStore(Store store)throws Exception{
  var profile=store.profile();var owner=nativeOwner();
  var ctor=ApocBRPlayerRelationalReconstruction.class.getDeclaredConstructors()[0];ctor.setAccessible(true);var r=ctor.newInstance(profile,null);
  var f=ApocBRPlayerRelationalReconstruction.class.getDeclaredField("objects");f.setAccessible(true);
  ((Map<Integer,Object>)f.get(r)).put(((Number)profile.rows("component").getFirst().get("owner_object_id")).intValue(),owner);
  invoke(r,"components");return owner;
 }
 static void mutationRoundTrip()throws Exception{
  var store=new Store();store.save(membershipGraph(Map.of("Durability",durability(9))));var owner=restoreStore(store);
  check(owner.hasComponent(ComponentType.Durability)&&((Number)get(owner.getComponent(ComponentType.Durability),"currentHitPoints")).floatValue()==9,"component payload SQL round trip");
  store.save(membershipGraph(Map.of("Signals",Map.of())));owner=restoreStore(store);
  check(owner.componentSize()==1&&owner.hasComponent(ComponentType.Signals),"component replacement SQL round trip removes factory state");
  store.save(membershipGraph(Map.of()));owner=restoreStore(store);check(owner.componentSize()==0,"last component removal SQL round trip retains explicit empty");
 }
 static void markers()throws Exception{
  var old=nativeOwner();invoke(reconstruction(old,List.of(),List.of()),"components");check(old.componentSize()==1,"legacy absent membership retains factory defaults");
  var empty=nativeOwner();invoke(reconstruction(empty,List.of("item.entityComponents"),List.of(true)),"components");check(empty.componentSize()==0,"new explicit empty membership removes defaults through native release");
  var historical=nativeOwner();invoke(reconstruction(historical,List.of("entity.Signals"),List.of(Map.of())),"components");check(historical.componentSize()==1&&historical.hasComponent(ComponentType.Signals),"historical nonempty entity rows establish saved membership");
  for(var bad:List.of(List.of("item.entityComponents","item.entityComponents"),List.of("item.entityComponents"))){
   var owner=nativeOwner();var values=bad.size()==2?List.<Object>of(true,true):List.<Object>of(false);
   try{invoke(reconstruction(owner,bad,values),"components");throw new AssertionError("invalid membership admitted");}catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalStateException,"duplicate/malformed marker rejected");}
   check(owner.componentSize()==1,"invalid marker does not remove factory state");
  }
 }
 static void equipment()throws Exception{
  var p=(Player)blank(Player.class);var a=(HandWeapon)blank(HandWeapon.class);var b=(ProbeItem)blank(ProbeItem.class);
  p.primary=a;p.secondary=a;ApocBRPlayerRelationalReconstruction.restoreEquipmentState(p,false);
  check(a.getEquipParent()==p&&p.weaponRegistrations==0&&p.active==a,"temporary two-hand owner/weapon restored without registration");
  ApocBRPlayerRelationalReconstruction.restoreEquipmentState(p,true);check(p.weaponRegistrations==1,"shared hands request registration once");
  p.secondary=b;ApocBRPlayerRelationalReconstruction.restoreEquipmentState(p,true);check(b.getEquipParent()==p&&b.registered==1&&p.weaponRegistrations==2,"distinct hand owners each register once");
  p.primary=b;p.secondary=null;ApocBRPlayerRelationalReconstruction.restoreEquipmentState(p,false);check(p.active==null,"nonweapon primary clears active weapon");
  p.primary=null;p.secondary=a;ApocBRPlayerRelationalReconstruction.restoreEquipmentState(p,true);check(p.active==null&&p.weaponRegistrations==3,"secondary-only weapon does not become primary active weapon");
  p.secondary=null;ApocBRPlayerRelationalReconstruction.restoreEquipmentState(p,false);check(p.active==null,"empty hands valid");
 }
 public static void main(String[] args)throws Exception{membership();markers();mutationRoundTrip();equipment();System.out.println("42.22.0 reconstruction lifecycle checks="+checks);}
'''

if __name__ == "__main__":
    main()
