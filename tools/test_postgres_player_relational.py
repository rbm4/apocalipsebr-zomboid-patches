"""Relational capture/JDBC fixtures and native 42.21.0 accessor compatibility; no live DB."""
from pathlib import Path
import os, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'42.21.0/src/zombie/savefile'
GAME=Path(os.environ.get('APOCBR_TEST_GAME_JAR',r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
JDK=ROOT/'jdk/bin'
MIGRATION=ROOT.parent/'apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V5__Create_Player_Relational_Schema.sql'
if MIGRATION.exists():
 import re
 sql=MIGRATION.read_text(encoding='utf-8')
 contract=(SRC/'ApocBRPlayerRelationalSchema.java').read_text(encoding='utf-8')
 for table,body in re.findall(r'CREATE TABLE player_rel\.(\w+) \((.*?)\n\);',sql,re.S):
  expected=[]
  for line in body.splitlines():
   match=re.match(r'    (\w+) (uuid|text|integer|bigint|real|double precision|boolean|timestamptz|bytea)\b(.*)',line)
   if match: expected.append((match[1],match[2],str('NOT NULL' in match[3]).lower()))
  block=re.search(r'TABLES.put\("'+table+r'", List.of\((.*?)\)\);',contract).group(1)
  actual=re.findall(r'new Column\("([^"]+)","([^"]+)",(true|false)\)',block)
  assert actual==expected, f'Backend V5 contract drift: {table}'

HARNESS=r'''
package zombie.savefile;
import java.util.*;
import java.lang.reflect.*;
import java.sql.*;
public class RelationalTest {
 static int checks;
 static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);checks++;}
 static Class<?> resolve(Class<?> type,String path)throws Exception {
  for(String part:path.split("\\.")) {
   Field found=null;
   for(Class<?> current=type;current!=null&&found==null;current=current.getSuperclass())try{found=current.getDeclaredField(part);}catch(NoSuchFieldException ignored){}
   if(found!=null){type=found.getType();continue;}
   String cap=Character.toUpperCase(part.charAt(0))+part.substring(1);Method method=null;
   for(String name:new String[]{part,"get"+cap,"is"+cap,"Get"+cap,"Is"+cap})try{method=type.getMethod(name);break;}catch(NoSuchMethodException ignored){}
   if(method==null)throw new NoSuchFieldException(type.getName()+"."+part);
   type=method.getReturnType();
  }return type;
 }
 static void accessors()throws Exception {
  Field f=ApocBRPlayerRelationalCapture.class.getDeclaredField("BINDINGS");f.setAccessible(true);
  @SuppressWarnings("unchecked") var bindings=(Map<String,Map<String,String>>)f.get(null);
  String[][] roots={
   {"character_state","characters.IsoPlayer","character_id inventory_container_id source_offset_x source_offset_y"},
   {"character_descriptor","characters.SurvivorDesc","character_id"},
   {"character_effects","characters.IsoPlayer","character_id"},
   {"character_presentation","characters.IsoPlayer","character_id tag_r tag_g tag_b"},
   {"character_nutrition","characters.BodyDamage.Nutrition","character_id"},
   {"character_book_progress","characters.IsoGameCharacter$ReadBook","character_id position"},
   {"character_craft_history","characters.PlayerCraftHistory$CraftHistoryEntry","character_id recipe_key"},
   {"body_damage","characters.BodyDamage.BodyDamage","character_id"},
   {"body_part","characters.BodyDamage.BodyPart","character_id body_part_key"},
   {"thermoregulator","characters.BodyDamage.Thermoregulator","character_id"},
   {"thermal_node","characters.BodyDamage.Thermoregulator$ThermalNode","character_id body_part_key"},
   {"human_visual","core.skinnedmodel.visual.HumanVisual","character_id hair_r hair_g hair_b beard_r beard_g beard_b skin_r skin_g skin_b natural_hair_r natural_hair_g natural_hair_b natural_beard_r natural_beard_g natural_beard_b"},
   {"visual","core.skinnedmodel.visual.ItemVisual","id owner_character_id owner_item_id position tint_r tint_g tint_b hue"},
   {"container","inventory.ItemContainer","id owner_character_id owner_item_id"},
   {"item","inventory.InventoryItem","id runtime_class recorded_media_id save_type"},
   {"weapon_state","inventory.types.HandWeapon","item_id"},
   {"food_state","inventory.types.Food","item_id"},
   {"clothing_state","inventory.types.Clothing","item_id"},
   {"clothing_patch","inventory.types.Clothing$ClothingPatch","item_id body_part_key"},
   {"literature_state","inventory.types.Literature","item_id"},
   {"alarm_state","inventory.types.AlarmClock","item_id"},
   {"key_state","inventory.types.Key","item_id"},
   {"moveable_state","inventory.types.Moveable","item_id"},
   {"map_symbol","worldMap.symbols.WorldMapBaseSymbol","item_id position kind color_r color_g color_b color_a text translated layer_id symbol_id"},
   {"radio_state","radio.devices.DeviceData","item_id max_presets recorded_media_id"},
   {"radio_preset","radio.devices.PresetEntry","item_id position"},
   {"animal_track","characters.animals.AnimalTracks","item_id"},
   {"egg_allele","characters.animals.AnimalAllele","item_id gene_key allele_index"}
  };
  ArrayList<String> errors=new ArrayList<>();
  for(String[] root:roots){Class<?> type=Class.forName("zombie."+root[1],false,RelationalTest.class.getClassLoader());var skip=new HashSet<>(Arrays.asList(root[2].split(" ")));skip.add("snapshot_id");
   for(var column:ApocBRPlayerRelationalSchema.TABLES.get(root[0]))if(!skip.contains(column.name())) {
    String path=bindings.getOrDefault(root[0],Map.of()).getOrDefault(column.name(),ApocBRPlayerRelationalAccess.camel(column.name()));
    try{resolve(type,path);checks++;}catch(Exception error){errors.add(root[0]+"."+column.name()+" => "+error.getMessage());}
   }
  }
  String[][] extra={
   {"zombie.characters.IsoPlayer","alreadyReadBook"},
   {"zombie.characters.IsoPlayer","attachedItems"},
   {"zombie.characters.IsoPlayer","bodyDamage"},
   {"zombie.characters.IsoPlayer","characterTraits.knownTraits"},
   {"zombie.characters.IsoPlayer","cheats.cheats"},
   {"zombie.characters.IsoPlayer","craftHistory.craftHistory"},
   {"zombie.characters.IsoPlayer","descriptor"},
   {"zombie.characters.IsoPlayer","fitness"},
   {"zombie.characters.IsoPlayer","inventory"},
   {"zombie.characters.IsoPlayer","knownMediaLines"},
   {"zombie.characters.IsoPlayer","knownRecipes"},
   {"zombie.characters.IsoPlayer","mechanicsItem"},
   {"zombie.characters.IsoPlayer","nutrition"},
   {"zombie.characters.IsoPlayer","perkList"},
   {"zombie.characters.IsoPlayer","primaryHandItem"},
   {"zombie.characters.IsoPlayer","readBooks"},
   {"zombie.characters.IsoPlayer","readLiterature"},
   {"zombie.characters.IsoPlayer","readPrintMedia"},
   {"zombie.characters.IsoPlayer","savedVehicleRunning"},
   {"zombie.characters.IsoPlayer","savedVehicleSeat"},
   {"zombie.characters.IsoPlayer","savedVehicleX"},
   {"zombie.characters.IsoPlayer","savedVehicleY"},
   {"zombie.characters.IsoPlayer","secondaryHandItem"},
   {"zombie.characters.IsoPlayer","stats"},
   {"zombie.characters.IsoPlayer","table"},
   {"zombie.characters.IsoPlayer","tagColor.b"},
   {"zombie.characters.IsoPlayer","tagColor.g"},
   {"zombie.characters.IsoPlayer","tagColor.r"},
   {"zombie.characters.IsoPlayer","visual"},
   {"zombie.characters.IsoPlayer","wornItems"},
   {"zombie.characters.IsoPlayer","xp.xpMap"},
   {"zombie.characters.IsoPlayer","xp.xpMapMultiplier"},
   {"zombie.characters.SurvivorDesc","XPBoostMap"},
   {"zombie.characters.SurvivorDesc","extra"},
   {"zombie.characters.BodyDamage.Fitness","bodypartToIncStiffness"},
   {"zombie.inventory.ItemContainer","items"},
   {"zombie.inventory.types.Food","eggGenome"},
   {"zombie.inventory.types.Food","spices"},
   {"zombie.radio.devices.DeviceData","mediaIndex"},
   {"zombie.radio.devices.DeviceData","presets"},
   {"zombie.entity.components.fluids.FluidContainer","fluids"},
   {"zombie.entity.components.fluids.FluidInstance","amount"},
   {"zombie.entity.components.fluids.FluidInstance","color"},
   {"zombie.entity.components.fluids.FluidInstance","fluid"},
   {"zombie.entity.components.fluids.FluidFilter","categories"},
   {"zombie.entity.components.fluids.FluidFilter","filterType"},
   {"zombie.entity.components.fluids.FluidFilter","fluidEnums"},
   {"zombie.entity.components.fluids.FluidFilter","fluidStrings"},
   {"zombie.characters.BodyDamage.Fitness","stiffnessIncMap"},
   {"zombie.characters.BodyDamage.Fitness","stiffnessTimerMap"},
   {"zombie.characters.BodyDamage.Fitness","regularityMap"},
   {"zombie.characters.BodyDamage.Fitness","exeTimer"},
   {"zombie.core.skinnedmodel.visual.HumanVisual","hairColor"},
   {"zombie.core.skinnedmodel.visual.HumanVisual","beardColor"},
   {"zombie.core.skinnedmodel.visual.HumanVisual","skinColor"},
   {"zombie.core.skinnedmodel.visual.HumanVisual","naturalHairColor"},
   {"zombie.core.skinnedmodel.visual.HumanVisual","naturalBeardColor"},
   {"zombie.core.skinnedmodel.visual.HumanVisual","blood"},
   {"zombie.core.skinnedmodel.visual.HumanVisual","dirt"},
   {"zombie.core.skinnedmodel.visual.HumanVisual","holes"},
   {"zombie.core.skinnedmodel.visual.HumanVisual","bodyVisuals"},
   {"zombie.inventory.types.InventoryContainer","container"},
   {"zombie.inventory.types.InventoryContainer","weightReduction"},
   {"zombie.inventory.types.HandWeapon","attachmentList"},
   {"zombie.inventory.types.WeaponPart","partType"},
  };
  for(String[] entry:extra)try{resolve(Class.forName(entry[0],false,RelationalTest.class.getClassLoader()),entry[1]);checks++;}catch(Exception error){errors.add(entry[0]+" => "+error.getMessage());}
  check(errors.isEmpty(),String.join("\n",errors));
 }


 static class Color {public float r=1,g=1,b=1,a=1;}
 static class Container {public int id=11,capacity=20;public String type="inventory";public boolean explored=true,hasBeenLooted;public List<Object> items=new ArrayList<>();}
 static class Item {
  public int id; public String getFullType(){return "Base.Test";}
  public Color col=new Color();public Object visual,table,byteData,animalTracks,texture;public List<String> extraItems=new ArrayList<>();
  public int componentSize=0;
  public int currentUses=0;
  public int condition=0;
  public String name="fixture";
  public boolean customName=false;
  public float actualWeight=0f;
  public boolean customWeight=false;
  public float itemCapacity=0f;
  public boolean activated=false;
  public int haveBeenRepaired=0;
  public boolean customColor=false;
  public int keyId=0;
  public int remoteControlId=0;
  public int remoteRange=0;
  public String worker="fixture";
  public float wetCooldown=0f;
  public boolean favorite=false;
  public String stashMap="fixture";
  public boolean zombieInfected=false;
  public int currentAmmoCount=0;
  public int attachedSlot=0;
  public String attachedSlotType="fixture";
  public String attachedToModel="fixture";
  public int maxCapacity=0;
  public float worldScale=0f;
  public boolean isInitialised=false;
  public int modelIndex=0;
  public float worldXRotation=0f;
  public float worldYRotation=0f;
  public float worldZRotation=0f;
  public int recordedMediaIndex=-1,registryId=7;
  Item(int id){this.id=id;}
 }
 static class InventoryContainer extends Item {public Container container=new Container();public int weightReduction=75;InventoryContainer(int id){super(id);}}
 static class HandWeapon extends Item {public List<Object> attachmentList=new ArrayList<>();
  public float maxRange=0f;
  public float minRangeRanged=0f;
  public int clipSize=0;
  public float minDamage=0f;
  public float maxDamage=0f;
  public int recoilDelay=0;
  public int aimingTime=0;
  public int reloadTime=0;
  public int hitChance=0;
  public float minAngle=0f;
  public String fireMode="fixture";
  public float cyclicRateMultiplier=0f;
  public int explosionTimer=0;
  public float maxAngle=0f;
  public float bloodLevel=0f;
  public boolean containsClip=false;
  public boolean roundChambered=false;
  public boolean isJammed=false;
  public String weaponSprite="fixture";
  public float minSightRange=0f;
  public float maxSightRange=0f;
  HandWeapon(int id){super(id);}
 }
 static class XP {public float totalXp=12.5f;public int level=2,lastlevel=1;public Map<Object,Object> xpMap=new HashMap<>(),xpMapMultiplier=new HashMap<>();}
 static class Traits {public List<String> knownTraits=List.of("base:brave");}
 static class Fitness {public Map<Object,Object> stiffnessIncMap=new HashMap<>(),stiffnessTimerMap=new HashMap<>(),regularityMap=new HashMap<>(),exeTimer=new HashMap<>();public List<String> bodypartToIncStiffness=List.of();}
 static class Nutrition {
  public float calories=0f;
  public float proteins=0f;
  public float lipids=0f;
  public float carbohydrates=0f;
  public float weight=0f;
 }
 static class Damage {public Object thermoregulator;public List<Object> bodyParts=List.of();
  public float catchACold=0f;
  public boolean hasACold=false;
  public float coldStrength=0f;
  public int timeToSneezeOrCough=0;
  public boolean reduceFakeInfection=false;
  public float healthFromFoodTimer=0f;
  public float painReduction=0f;
  public float coldReduction=0f;
  public float infectionTime=0f;
  public float infectionMortalityDuration=0f;
  public float coldDamageStage=0f;
 }
 static class Human {public Object hairColor,beardColor,skinColor,naturalHairColor,naturalBeardColor;public List<Object> bodyVisuals=List.of();public byte[] blood,dirt,holes;
  public int bodyHair=0;
  public int skinTexture=0;
  public int zombieRotStage=0;
  public String skinTextureName="fixture";
  public String beardModel="fixture";
  public String hairModel="fixture";
  public String nonAttachedHair="fixture";
 }
 static class Cheats {public List<String> cheats=List.of();}
 static class Craft {public Map<Object,Object> craftHistory=new HashMap<>();}
 static class Stats {public float get(zombie.characters.CharacterStat stat){return 0.25f;}}
 public static class P {
  public Container inventory=new Container();public Object descriptor,table,leftHandItem,rightHandItem;
  public Object getPrimaryHandItem(){return leftHandItem;}public Object getSecondaryHandItem(){return rightHandItem;}
  public XP xp=new XP();public Traits characterTraits=new Traits();public Stats stats=new Stats();public Fitness fitness=new Fitness();public Nutrition nutrition=new Nutrition();public Damage bodyDamage=new Damage();public Human visual=new Human();
  public Cheats cheats=new Cheats();public Craft craftHistory=new Craft();public Color tagColor=new Color();public int savedVehicleSeat=-1;public float savedVehicleX,savedVehicleY;public boolean savedVehicleRunning;
  public List<Object> perkList=List.of(),readBooks=List.of(),wornItems=new ArrayList<>(),attachedItems=List.of();
  public List<String> knownRecipes=List.of("MakeTest"),alreadyReadBook=List.of(),readPrintMedia=List.of(),knownMediaLines=List.of();
  public Map<Object,Object> readLiterature=new HashMap<>(),mechanicsItem=new HashMap<>();
  public float x=0f;
  public float y=0f;
  public float z=0f;
  public String forwardIsoDirection="fixture";
  public boolean isDead=false;
  public double hoursSurvived=0d;
  public int zombieKills=0;
  public int survivorKills=0;
  public boolean asleep=false;
  public float forceWakeUpTime=0f;
  public boolean onFire=false;
  public int lastHourSleeped=0;
  public float timeSinceLastSmoke=0f;
  public float beardGrowTiming=0f;
  public float hairGrowTiming=0f;
  public long lastAnimalPet=0L;
  public boolean sneaking=false;
  public boolean deathDragDown=false;
  public boolean autoDrink=false;
  public int voiceType=0;
  public float depressEffect=0f;
  public float depressFirstTakeTime=0f;
  public float betaEffect=0f;
  public float betaDelta=0f;
  public float painEffect=0f;
  public float painDelta=0f;
  public float sleepingTabletEffect=0f;
  public float sleepingTabletDelta=0f;
  public float reduceInfectionPower=0f;
  public boolean allChatMuted=false;
  public String tagPrefix="fixture";
  public String displayName="fixture";
  public boolean showTag=false;
  public boolean factionPvp=false;
  public boolean unlimitedCarry;
  public boolean buildCheat;
  public boolean healthCheat;
  public boolean mechanicsCheat;
  public boolean movablesCheat;
  public boolean farmingCheat;
  public boolean fishingCheat;
  public boolean canUseBrushTool;
  public boolean fastMoveCheat;
  public boolean timedActionInstantCheat;
  public boolean unlimitedEndurance;
  public boolean unlimitedAmmo;
  public boolean knowAllRecipes;
 }
 static class Worn {public String location="base:back";public Object item;Worn(Object item){this.item=item;}}
 static void captures() {
  P player=new P();var outer=new InventoryContainer(1);var inner=new InventoryContainer(2);var weapon=new HandWeapon(3);Item child=new Item(4);
  player.inventory.items.add(outer);outer.container.items.add(inner);inner.container.items.add(child);player.inventory.items.add(weapon);
  player.leftHandItem=weapon;player.rightHandItem=weapon;player.wornItems.add(new Worn(outer));
  UUID character=UUID.randomUUID(),known=UUID.randomUUID();
  var graph=new ApocBRPlayerRelationalCapture(character,Map.of(3,known),5_000_000_000L).capture(player,List.of());
  check(graph.items()==4&&graph.containers()==3,"recursive bag graph captured once");
  var equipment=graph.rows().stream().filter(r->r.table().equals("equipment")).toList();
  check(equipment.size()==3,"worn bag and both hands retained");
  check(equipment.stream().filter(r->r.values().get("item_id").equals(known)).count()==2,"same weapon UUID in both hands");
  check(graph.rows().stream().anyMatch(r->r.table().equals("bag_state")&&r.values().get("weight_reduction").equals(75)),"bag state captured");
  check(graph.rows().stream().filter(r->r.table().equals("item_location")).count()==4,"one ownership edge per item");
  player.inventory.items.add(outer);
  try{new ApocBRPlayerRelationalCapture(character,Map.of(),5_000_000_000L).capture(player,List.of());throw new AssertionError("duplicate ownership accepted");}catch(IllegalStateException expected){checks++;}
  player.inventory.items.removeLast();inner.container.items.add(outer);
  try{new ApocBRPlayerRelationalCapture(character,Map.of(),5_000_000_000L).capture(player,List.of());throw new AssertionError("cycle accepted");}catch(IllegalStateException expected){checks++;}
  inner.container.items.removeLast();
  try{new ApocBRPlayerRelationalCapture(character,Map.of(),-1L).capture(player,List.of());throw new AssertionError("time limit ignored");}catch(IllegalStateException expected){checks++;}
  var damaged=new ApocBRPlayerRelationalCapture(character,Map.of(),5_000_000_000L).capture(player,List.of("ITEM_LOAD_FAILED"));
  check(damaged.issues().contains("ITEM_LOAD_FAILED"),"load quality survives capture");
 }


 static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
 static class Fake {
  int opened,closed,commits,rollbacks,inserts,headWrites,batches;boolean complete,fail,stale;UUID dataset,character,head;String status;
  List<String> queries=new ArrayList<>();
  Connection open(){opened++;return(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(o,m,a)->{
   switch(m.getName()){
    case "close":closed++;return null;
    case "commit":commits++;return null;
    case "rollback":rollbacks++;return null;
    case "prepareStatement":String sql=(String)a[0];queries.add(sql);Map<Integer,Object> values=new HashMap<>();int[] queued={0};return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,pm,pa)->{
     String method=pm.getName();
     if(method.startsWith("set")&&pa.length>=2&&pa[0] instanceof Integer){values.put((int)pa[0],pa[1]);return null;}
     switch(method){
      case "executeQuery":boolean[] advanced={false};return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(r,rm,ra)->{
       String name=rm.getName();
       if(name.equals("next")){boolean found=sql.contains("advisory")||sql.equals("SELECT id FROM player_rel.player_slot WHERE world=? AND identity_mode=? AND identity_key=? AND player_index=?")||((complete||stale)&&sql.contains("active_character_id"));boolean result=found&&!advanced[0];advanced[0]=true;return result;}
       if(name.equals("getBoolean"))return true;
       if(name.equals("getInt"))return 1;
       if(name.equals("getString"))return "complete";
       if(name.equals("getObject")){int index=(int)ra[0];if(sql.startsWith("SELECT id FROM"))return UUID.randomUUID();return index==1?character:index==2?head:dataset;}
       return zero(rm.getReturnType());
      });
      case "addBatch":queued[0]++;return null;
      case "clearBatch":queued[0]=0;return null;
      case "executeBatch":if(fail)throw new SQLException("simulated","08006");batches++;int[] result=new int[queued[0]];Arrays.fill(result,1);return result;
      case "executeUpdate":if(sql.startsWith("INSERT"))inserts++;if(sql.contains("INSERT INTO player_rel.character_head"))headWrites++;if(sql.startsWith("UPDATE player_rel.snapshot"))status=(String)values.get(1);return 1;
      case "execute":return true;
     }return zero(pm.getReturnType());
    });
   }return zero(m.getReturnType());
  });}
 }
 static void database()throws Exception {
  UUID dataset=UUID.randomUUID();var candidate=new ApocBRPlayerRelationalDatabase.Candidate(dataset,"test","world","alice",false,0,1,249,"hash",List.of());
  Fake fake=new Fake();fake.dataset=dataset;var db=new ApocBRPlayerRelationalDatabase(fake::open);
  var receipt=db.inspect(candidate);check(!receipt.complete()&&receipt.head()==null,"missing receipt requests capture");
  P player=new P();var graph=new ApocBRPlayerRelationalCapture(receipt.character(),Map.of(),5_000_000_000L).capture(player,List.of());
  check(db.persist(candidate,receipt,graph),"initial graph committed");
  check(fake.headWrites==1&&"complete".equals(fake.status),"only complete graph published");
  check(fake.batches>0,"graph rows use JDBC batches");
  int committed=fake.commits,heads=fake.headWrites;fake.fail=true;
  try{db.persist(candidate,receipt,graph);throw new AssertionError("failed batch accepted");}catch(SQLException expected){checks++;}
  check(fake.commits==committed&&fake.rollbacks>0&&fake.headWrites==heads,"failed graph rolls back without publishing");fake.fail=false;
  var incomplete=new ApocBRPlayerRelationalCapture(receipt.character(),Map.of(),5_000_000_000L).capture(player,List.of("ITEM_SKIPPED"));
  check(db.persist(candidate,receipt,incomplete),"incomplete graph retained for diagnosis");check(fake.headWrites==heads&&"incomplete".equals(fake.status),"incomplete graph has no receipt");
  fake.complete=true;fake.character=receipt.character();fake.head=graph.snapshot();
  int writes=fake.inserts;var existing=db.inspect(candidate);check(existing.complete()&&fake.inserts==writes,"complete receipt lookup is read-only");
  check(!db.persist(candidate,receipt,graph)&&fake.headWrites==heads,"stale expected head rejected");
  fake.dataset=UUID.randomUUID();try{db.inspect(candidate);throw new AssertionError("foreign dataset accepted");}catch(SQLException expected){checks++;}
  check(fake.opened==fake.closed,"all JDBC leases closed");
  check(fake.queries.stream().noneMatch(q->q.contains("public.networkplayers")||q.startsWith("CREATE")||q.contains("LOCK TABLE")),"backfill never writes native store or runs DDL/table locks");
  try(var quality=ApocBRPlayerLoadQuality.begin()){
   ApocBRPlayerLoadQuality.issue("ITEM_SKIPPED");try(var nested=ApocBRPlayerLoadQuality.begin()){ApocBRPlayerLoadQuality.issue("INNER");check(nested.issues().equals(List.of("INNER")),"nested quality isolated");}
   ApocBRPlayerLoadQuality.issue("ITEM_SKIPPED");check(quality.issues().equals(List.of("ITEM_SKIPPED")),"quality scope restored and deduplicated");
  }
 }


 static void worker()throws Exception {
  captures(); // Native login also loads the stat registry before the capture pump.
  Fake fake=new Fake();fake.complete=true;fake.dataset=ApocBRPlayerRelationalDatabase.named("sqlite-save:test-save");fake.character=UUID.randomUUID();fake.head=UUID.randomUUID();
  zombie.savefile.ApocBRPostgresConnector.connections=fake::open;
  System.setProperty("apocbr.postgres.enabled","true");System.setProperty("apocbr.postgres.players.enabled","false");
  var player=new zombie.characters.IsoPlayer();
  ApocBRPlayerRelationalSync.offerLoadedPlayer(player,1,"world","alice",0,249,new byte[]{1},List.of());
  long until=System.nanoTime()+2_000_000_000L;
  Field pending=ApocBRPlayerRelationalSync.class.getDeclaredField("PENDING");pending.setAccessible(true);
  while(!((Map<?,?>)pending.get(null)).isEmpty()&&System.nanoTime()<until)Thread.sleep(10);
  check(((Map<?,?>)pending.get(null)).isEmpty()&&fake.inserts==0,"worker skips capture/write for committed receipt");
  fake.complete=false;System.setProperty("apocbr.postgres.playerRelational.captureBudgetMs","25");
  ApocBRPlayerRelationalSync.offerLoadedPlayer(player,1,"world","alice",0,249,new byte[]{1},List.of());
  until=System.nanoTime()+3_000_000_000L;
  while(fake.headWrites==0&&System.nanoTime()<until){ApocBRPlayerRelationalSync.updateMain();Thread.sleep(10);}
  check(fake.headWrites==1,"worker persists first captured graph");
  ApocBRPlayerRelationalSync.shutdown();check(fake.opened==fake.closed,"shutdown returns worker lease");
 }

 public static void main(String[] args)throws Exception {if(args.length>0){worker();System.out.println("Worker checks="+checks);return;}accessors();captures();database();System.out.println("Relational checks="+checks);}
}
'''
with tempfile.TemporaryDirectory(prefix='apocbr_rel_') as folder:
 target=Path(folder);harness=target/'RelationalTest.java';native_harness=HARNESS[:HARNESS.index(' static void worker()')]+HARNESS[HARNESS.index(' public static void main'):];native_harness=native_harness.replace('if(args.length>0){worker();System.out.println(\"Worker checks=\"+checks);return;}','');harness.write_text(native_harness,encoding='utf-8')
 sources=[SRC/(name+'.java') for name in ['ApocBRPlayerRelationalSchema','ApocBRPlayerRelationalAccess','ApocBRPlayerRelationalCapture','ApocBRPlayerRelationalDatabase','ApocBRPlayerLoadQuality']]
 subprocess.run([str(JDK/'javac.exe'),'--release','25','-cp',str(GAME),'-d',folder,*map(str,sources),str(harness)],check=True)
 subprocess.run([str(JDK/'java.exe'),'-cp',folder+os.pathsep+str(GAME),'zombie.savefile.RelationalTest'],check=True)

 # Recompile a separate worker fixture against small engine boundary stubs.
 stubs={
 'zombie/network/GameServer.java':'package zombie.network; public class GameServer {public static boolean server=true,coop;}',
 'zombie/core/Core.java':'package zombie.core; public class Core {}',
 'zombie/core/znet/SteamUtils.java':'package zombie.core.znet; public class SteamUtils {public static boolean isSteamModeEnabled(){return false;}}',
 'zombie/debug/DebugLog.java':'package zombie.debug; public class DebugLog {public static void log(String value){System.out.println(value);}}',
 'zombie/ZomboidFileSystem.java':'package zombie; public class ZomboidFileSystem {public static final ZomboidFileSystem instance=new ZomboidFileSystem();public String getCurrentSaveDir(){return "test-save";}}',
 'zombie/characters/IsoPlayer.java':'package zombie.characters; public class IsoPlayer extends zombie.savefile.RelationalTest.P {}',
 'zombie/savefile/ApocBRPostgresConnector.java':'package zombie.savefile; public class ApocBRPostgresConnector {public static java.util.function.Supplier<java.sql.Connection> connections;public static java.sql.Connection borrowPlayerConnection(){return connections.get();}}',
 'zombie/savefile/ApocBRPlayerDatabase.java':'package zombie.savefile; public class ApocBRPlayerDatabase {public static boolean enabled(){return Boolean.getBoolean("apocbr.postgres.players.enabled");}}'
 }
 harness.write_text(HARNESS,encoding='utf-8')
 files=[]
 for name,body in stubs.items():
  path=target/'stubs'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body);files.append(path)
 subprocess.run([str(JDK/'javac.exe'),'--release','25','-cp',folder+os.pathsep+str(GAME),'-d',folder,*map(str,files),str(SRC/'ApocBRPlayerRelationalSync.java'),str(harness)],check=True)
 subprocess.run([str(JDK/'java.exe'),'-cp',folder+os.pathsep+str(GAME),'zombie.savefile.RelationalTest','worker'],check=True)
