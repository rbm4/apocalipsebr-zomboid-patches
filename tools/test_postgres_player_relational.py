"""Relational capture/JDBC fixtures and native 42.21.0 accessor compatibility; no live DB."""
from postgres_test_support import inactive_persistence_stubs

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
  if table=='value_node':
   v7=(MIGRATION.parent/'V7__Add_Server_And_Payload_Provenance.sql').read_text(encoding='utf-8')
   alter=re.search(r'ALTER TABLE player_rel.value_node(.*?);',v7,re.S).group(1)
   expected += [(name,kind,'false') for name,kind in re.findall(r'ADD COLUMN (\w+) (integer|uuid)',alter)]
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
  Class<?> radio=Class.forName("zombie.radio.ZomboidRadio");
  check(Modifier.isStatic(radio.getMethod("getInstance").getModifiers())&&!Modifier.isStatic(radio.getMethod("getRecordedMedia").getModifiers()),"native 42.21.0 recorded media requires a radio singleton receiver");
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
  public int id; public String partType="fixture"; public String getFullType(){return "Base.Test";}
  public Color col=new Color();public Object visual,table,byteData,animalTracks,texture;public List<String> extraItems=new ArrayList<>();
  public int componentSize=0;
  public List<Object> components=new ArrayList<>();public Object getComponentForIndex(int index){return components.get(index);}
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
 static class Descriptor {
  public int id=7,voiceType=1;public String forename="Before",surname="Surname",torso="torso",characterGender="female",characterProfession="base:unemployed",voicePrefix="VoiceFemale";
  public float voicePitch=1;public List<String> extra=List.of();public Map<Object,Object> XPBoostMap=Map.of();
 }
 public static class P {
  public Container inventory=new Container();public Object descriptor,table,leftHandItem,rightHandItem,vehicle;
  public boolean forbidExportReads;
  public Object getPrimaryHandItem(){if(forbidExportReads)throw new AssertionError("live player read after handoff");return leftHandItem;}public Object getSecondaryHandItem(){return rightHandItem;}
  public XP xp=new XP();public Traits characterTraits=new Traits();public Stats stats=new Stats();public Fitness fitness=new Fitness();public Nutrition nutrition=new Nutrition();public Damage bodyDamage=new Damage();public Human visual=new Human();
  public Cheats cheats=new Cheats();public Craft craftHistory=new Craft();public Color tagColor=new Color();public int savedVehicleSeat=-1;public float savedVehicleX,savedVehicleY;public boolean savedVehicleRunning;
  public List<Object> perkList=List.of(),readBooks=List.of(),wornItems=new ArrayList<>(),attachedItems=List.of();
  public List<String> knownRecipes=List.of("MakeTest"),alreadyReadBook=List.of(),readPrintMedia=List.of(),knownMediaLines=List.of();
  public Map<Object,Object> readLiterature=new HashMap<>(),mechanicsItem=new HashMap<>();
  public float offsetX,offsetY;public int extraInfoFlags;
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
 static class ItemVisual {
  public String fullType="Base.Test",alternateModelName="alternate",clothingItemName="shirt",decal="decal";
  public int baseTexture=2,textureChoice=3;public float hue=0.5f;public Color tint=new Color();
  public byte[] blood={1,2},dirt={3},holes={0},basicPatches={1},denimPatches={2},leatherPatches={3};
 }
 static class Durability {public float currentHitPoints=12,maxHitPoints=20;public String material="metal";}
 static class UnreviewedComponent {}
 static class FluidType {public String fluidTypeString="Water";}
 static class FluidInstance {public FluidType fluid=new FluidType();public float amount=2;public Color color=new Color();}
 static class FluidFilter {public String filterType="Whitelist";public List<String> fluidEnums=List.of("Water"),fluidStrings=List.of("CustomFluid"),categories=List.of("Drink");}
 static class FluidContainer {
  public float capacity=5,hiddenAmount=0;public boolean inputLocked=false,canPlayerEmpty=true,rainCatcher=false;
  public String containerName="bottle";public List<FluidInstance> fluids=new ArrayList<>(List.of(new FluidInstance()));
  public FluidFilter whitelist=new FluidFilter(),blacklist;
 }
 static void captures() {
  P player=new P();var outer=new InventoryContainer(1);var inner=new InventoryContainer(2);var weapon=new HandWeapon(3);Item child=new Item(4);
  player.inventory.items.add(outer);outer.container.items.add(inner);inner.container.items.add(child);player.inventory.items.add(weapon);
  player.leftHandItem=weapon;player.rightHandItem=weapon;player.wornItems.add(new Worn(outer));
  UUID character=UUID.randomUUID(),known=UUID.randomUUID();
  var graph=new ApocBRPlayerRelationalCapture(character,Map.of(3,known),5_000_000_000L).capture(player,List.of());
  var snapshot=ApocBRPlayerRelationalSnapshot.copy(player);
  detached(snapshot.player(),Collections.newSetFromMap(new IdentityHashMap<>()));
  var projected=new ApocBRPlayerRelationalCapture(character,Map.of(3,known),Long.MAX_VALUE).capture(snapshot,List.of());
  check(canonical(graph).equals(canonical(projected)),"detached nested inventory/equipment matches live graph");
  check(projected.capturedAt().equals(snapshot.capturedAt()),"graph preserves load-time snapshot timestamp");
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
  // Deterministic clock: abort a partially built graph, then retry from scratch.
  long[] ticks={0};
  try {
   new ApocBRPlayerRelationalCapture(character,Map.of(),8_000_000L,()->ticks[0]++*1_000_000L).capture(player,List.of());
   throw new AssertionError("partial capture accepted");
  } catch(ApocBRPlayerRelationalCapture.TimeBudgetExceeded expected) {
   check(expected.rows>0&&expected.bytes>0&&expected.elapsedNanos>expected.budgetNanos,"timeout retains partial progress diagnostics");
   check(expected.diagnostic().contains("budgetMs=8")&&expected.diagnostic().contains("elapsedMs=9"),"controlled timeout diagnostics");
  }
  ticks[0]=0;
  var retried=new ApocBRPlayerRelationalCapture(character,Map.of(3,known),5_000_000_000L,()->ticks[0]++*1_000_000L).capture(player,List.of());
  check(retried.items()==graph.items()&&retried.rows().size()==graph.rows().size(),"retry captures entire graph with no partial rows reused");
  var damaged=new ApocBRPlayerRelationalCapture(character,Map.of(),5_000_000_000L).capture(player,List.of("ITEM_LOAD_FAILED"));
  check(damaged.issues().contains("ITEM_LOAD_FAILED"),"load quality survives capture");
 }

 static List<String> canonical(ApocBRPlayerRelationalCapture.Graph graph) {
  Map<UUID,Integer> ids=new HashMap<>();List<String> result=new ArrayList<>();
  for(var row:graph.rows()){
   StringBuilder text=new StringBuilder(row.table());
   for(var e:row.values().entrySet()){
    Object value=e.getValue();if(value instanceof UUID id)value=ids.computeIfAbsent(id,k->ids.size());
    if(value instanceof byte[] data)value=Arrays.toString(data);
    text.append('|').append(e.getKey()).append('=').append(value);
   }result.add(text.toString());
  }return result;
 }
 static void detached(Object value,Set<Object> visited) {
  if(value==null||!visited.add(value))return;
  if(value instanceof String||value instanceof Boolean||value instanceof Number||value instanceof byte[])return;
  if(value instanceof ApocBRPlayerRelationalSnapshot.Node node){
   try{Field f=node.getClass().getDeclaredField("fields");f.setAccessible(true);detached(f.get(node),visited);detached(node.types,visited);}catch(Exception e){throw new AssertionError(e);}return;
  }
  if(value instanceof ApocBRPlayerRelationalSnapshot.Table table){detached(table.entries(),visited);return;}
  if(value instanceof ApocBRPlayerRelationalSnapshot.Reference reference){detached(reference.target(),visited);return;}
  if(value instanceof ApocBRPlayerRelationalSnapshot.Stat stat){detached(stat.key(),visited);detached(stat.value(),visited);return;}
  if(value instanceof Map<?,?> map){for(var e:map.entrySet()){detached(e.getKey(),visited);detached(e.getValue(),visited);}return;}
  if(value instanceof Collection<?> values){for(Object child:values)detached(child,visited);return;}
  throw new AssertionError("Snapshot retained live object "+value.getClass());
 }
 static void snapshots() {
  P p=new P();Item item=new Item(1);p.inventory.items.add(item);p.leftHandItem=item;
  ItemVisual visual=new ItemVisual();item.visual=visual;p.visual.bodyVisuals=List.of(new ItemVisual());p.visual.blood=new byte[]{4,5};
  Durability durability=new Durability();FluidContainer fluid=new FluidContainer();item.components.add(durability);item.components.add(fluid);item.componentSize=2;
  byte[] data={1,2};item.byteData=java.nio.ByteBuffer.wrap(data);
  var origin=new ApocBRPayloadProvenance(2,UUID.randomUUID(),UUID.randomUUID());
  ApocBRPayloadProvenance.remember(item.byteData,new ApocBRPayloadProvenance.Metadata(origin,249));
  Map<Object,Object> mod=new LinkedHashMap<>();mod.put("values",new ArrayList<>(List.of(1.25,true,"before")));mod.put(2.5,"numeric");mod.put(false,"boolean");item.table=mod;
  UUID character=UUID.randomUUID();Map<Integer,UUID> aliases=Map.of(1,UUID.randomUUID());
  var before=new ApocBRPlayerRelationalCapture(character,aliases,Long.MAX_VALUE).capture(p,List.of());
  var snapshot=ApocBRPlayerRelationalSnapshot.copy(p);detached(snapshot.player(),Collections.newSetFromMap(new IdentityHashMap<>()));
  ApocBRPayloadProvenance.remember(item.byteData,new ApocBRPayloadProvenance.Metadata(new ApocBRPayloadProvenance(1,UUID.randomUUID(),UUID.randomUUID()),250));
  mod.clear();data[0]=99;item.name="after";p.inventory.items.clear();p.leftHandItem=null;p.x=999;p.forbidExportReads=true;
  visual.blood[0]=99;visual.tint.r=0;p.visual.blood[0]=99;durability.currentHitPoints=0;fluid.fluids.clear();
  var after=new ApocBRPlayerRelationalCapture(character,aliases,Long.MAX_VALUE).capture(snapshot,List.of());
  check(canonical(before).equals(canonical(after)),"snapshot survives inventory, scalar, mod-data, bytes, visuals and component mutations");
  try {
   java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("apocbr_snapshot_journal_");
   try {
    var batch=new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),character,Map.of(),java.time.Instant.EPOCH,snapshot,1);
    try(var journal=new ApocBRPersistenceJournal(directory,1024,s->{})){journal.submit(new ApocBRPersistenceOperations.Live(batch));}
    try(var journal=new ApocBRPersistenceJournal(directory,1024,s->{})) {
     var restored=(ApocBRPlayerRelationalMutations.Batch)((ApocBRPersistenceOperations.Live)journal.head().operation()).batch();
     detached(restored.snapshot().player(),Collections.newSetFromMap(new IdentityHashMap<>()));
     var restoredGraph=new ApocBRPlayerRelationalCapture(character,aliases,Long.MAX_VALUE).capture(restored.snapshot(),List.of());
     check(canonical(after).equals(canonical(restoredGraph)),"disk journal round-trip preserves detached graph, shared references and opaque payload provenance");
     journal.acknowledge(journal.head().id());
    }
   }finally{try(var paths=java.nio.file.Files.walk(directory)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
  }catch(Exception failure){throw new AssertionError("snapshot journal round trip",failure);}

  var opaque=after.rows().stream().filter(r->r.table().equals("value_node")&&r.values().get("opaque_value")!=null).findFirst().orElseThrow().values();
  check(opaque.get("source_server_id").equals(2)&&opaque.get("source_dictionary_view_id").equals(origin.viewId())&&opaque.get("payload_world_version").equals(249),"detached item bytes retain foreign encoder and version after live buffer is reused");
  P cyclic=new P();Map<Object,Object> table=new HashMap<>();table.put("self",table);cyclic.table=table;
  var invalid=ApocBRPlayerRelationalSnapshot.copy(cyclic);
  try{new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(invalid,List.of());throw new AssertionError("cyclic table accepted");}catch(IllegalStateException expected){checks++;}
  Item oversized=new Item(2);oversized.name="x".repeat(65537);cyclic.inventory.items.add(oversized);
  try{ApocBRPlayerRelationalSnapshot.copy(cyclic);throw new AssertionError("oversized snapshot accepted");}catch(IllegalStateException expected){checks++;}
 }


 static <T> T nativeComponent(Class<T> type)throws Exception {
  var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);return ctor.newInstance();
 }
 static void field(Object object,String name,Object value)throws Exception {
  Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);
 }
 static byte[] nativeAttributes(zombie.entity.components.attributes.AttributeContainer container) {
  var buffer=java.nio.ByteBuffer.allocate(16384);container.save(buffer);return Arrays.copyOf(buffer.array(),buffer.position());
 }
 @SuppressWarnings("unchecked") static void componentCodecs()throws Exception {
  var attributes=nativeComponent(zombie.entity.components.attributes.AttributeContainer.class);
  var script=nativeComponent(zombie.entity.components.script.EntityScriptInfo.class);
  field(script,"originalIsItem",true);field(script,"originalScript","Base.TestItem");
  var registered=List.of(zombie.entity.components.attributes.Attribute.Quality,zombie.entity.components.attributes.Attribute.TestQuality,
   zombie.entity.components.attributes.Attribute.TestBool,zombie.entity.components.attributes.Attribute.TestString,
   zombie.entity.components.attributes.Attribute.TestItemType,zombie.entity.components.attributes.Attribute.TestCategories,
   zombie.entity.components.attributes.Attribute.TestTags);
  for(var type:registered)attributes.add(type);
  ((zombie.entity.components.attributes.AttributeInstance.EnumSet)attributes.getAttribute(zombie.entity.components.attributes.Attribute.TestCategories)).getValue().clear();
  attributes.set(zombie.entity.components.attributes.Attribute.Quality,73);
  attributes.set(zombie.entity.components.attributes.Attribute.TestQuality,42.25f);
  attributes.set(zombie.entity.components.attributes.Attribute.TestBool,true);
  attributes.set(zombie.entity.components.attributes.Attribute.TestString,"a; text with Unicode: ação");
  var tags=(zombie.entity.components.attributes.AttributeInstance.EnumStringSet)attributes.getAttribute(zombie.entity.components.attributes.Attribute.TestTags);
  tags.getValue().add(zombie.entity.components.attributes.TestEnum.TestValueB);tags.getValue().add("free;form");tags.getValue().add("another");
  Object[] numeric={(byte)-128,(short)-32768,Long.MIN_VALUE,-0.125d};String[] kinds={"Byte","Short","Long","Double"};
  for(int i=0;i<kinds.length;i++) {
   Class<?> type=Class.forName("zombie.entity.components.attributes.AttributeType$"+kinds[i]);
   var ctor=Arrays.stream(type.getDeclaredConstructors()).filter(c->c.getParameterCount()==3).findFirst().orElseThrow();ctor.setAccessible(true);
   attributes.add((zombie.entity.components.attributes.AttributeType)ctor.newInstance((short)(200+i),"Fixture"+kinds[i],numeric[i]));
  }
  byte[] serialized=nativeAttributes(attributes);
  Map<String,Object> state=ApocBRPlayerRelationalSnapshot.componentState(attributes);
  detached(state,Collections.newSetFromMap(new IdentityHashMap<>()));
  List<Map<String,Object>> values=(List<Map<String,Object>>)(List<?>)state.get("attributes");
  Map<String,Map<String,Object>> byType=new HashMap<>();for(var value:values)byType.put((String)value.get("value_type"),value);
  check(byType.size()==11,"all eleven native attribute value types encoded");
  check(byType.get("Long").get("value").equals(Long.MIN_VALUE),"long attributes retain integer precision");
  check(byType.get("Double").get("value").equals(-0.125d)&&byType.get("Float").get("value").equals(42.25f),"decimal widths retained");
  check(byType.get("Boolean").get("value").equals(true)&&byType.get("String").get("value").equals("a; text with Unicode: ação"),"boolean and string values retained");
  check(((Map<?,?>)byType.get("Enum").get("value")).get("name").equals("TestValueA"),"enum uses stable name");
  check(((Map<?,?>)byType.get("Enum").get("value")).get("native_byte_id").equals(1),"enum native ID retained");
  check(byType.get("EnumSet").get("enum_class").equals("zombie.entity.components.attributes.TestEnum")&&((List<?>)byType.get("EnumSet").get("value")).isEmpty(),"empty enum sets retain enum class");
  var mixed=(Map<String,Object>)byType.get("EnumStringSet").get("value");
  check(((List<?>)mixed.get("enums")).size()==1&&mixed.get("strings").equals(List.of("free;form","another")),"mixed set keeps enum/string separation and string order");
  check(Arrays.equals(serialized,nativeAttributes(attributes)),"export leaves native attribute save bytes unchanged");
  P player=new P();Item item=new Item(1);player.inventory.items.add(item);item.components.add(attributes);item.components.add(script);item.componentSize=2;
  UUID character=UUID.randomUUID();Map<Integer,UUID> aliases=Map.of(1,UUID.randomUUID());
  var before=new ApocBRPlayerRelationalCapture(character,aliases,Long.MAX_VALUE).capture(player,List.of());
  check(before.issues().isEmpty(),"both new codecs have complete coverage");
  var frozen=ApocBRPlayerRelationalSnapshot.copy(player);detached(frozen.player(),Collections.newSetFromMap(new IdentityHashMap<>()));
  attributes.set(zombie.entity.components.attributes.Attribute.Quality,1);tags.getValue().clear();field(script,"originalScript","Base.After");field(script,"originalIsItem",false);
  var after=new ApocBRPlayerRelationalCapture(character,aliases,Long.MAX_VALUE).capture(frozen,List.of());
  check(canonical(before).equals(canonical(after)),"new component snapshots survive native attribute and script mutations");
  check(after.rows().stream().filter(r->r.table().equals("component")&&r.values().get("coverage").equals("complete")).count()==2,"new components persisted through typed node rows");
  check(after.rows().stream().anyMatch(r->r.table().equals("value_node")&&Objects.equals(r.values().get("integer_value"),Long.MIN_VALUE)),"typed SQL rows preserve full-width attribute integers");
  Fake fake=new Fake();UUID dataset=UUID.randomUUID();fake.dataset=dataset;
  var db=new ApocBRPlayerRelationalDatabase(fake::open);var candidate=new ApocBRPlayerRelationalDatabase.Candidate(dataset,"test","world","alice",false,0,1,249,"hash",List.of());
  check(db.persist(candidate,new ApocBRPlayerRelationalDatabase.Receipt(character,null,aliases,false),after)&&fake.headWrites==1,"supported component graph publishes completion receipt");
  item.components.add(new UnreviewedComponent());item.componentSize=3;
  var unsupported=new ApocBRPlayerRelationalCapture(character,aliases,Long.MAX_VALUE).capture(ApocBRPlayerRelationalSnapshot.copy(player),List.of());
  check(unsupported.issues().contains("UNSUPPORTED_COMPONENT_UnreviewedComponent"),"unreviewed components still prevent complete coverage");
  check(db.persist(candidate,new ApocBRPlayerRelationalDatabase.Receipt(character,null,aliases,false),unsupported)&&fake.headWrites==1&&fake.status.equals("incomplete"),"unknown component graph never publishes a new completion receipt");
  var empty=nativeComponent(zombie.entity.components.attributes.AttributeContainer.class);
  check(((List<?>)ApocBRPlayerRelationalSnapshot.componentState(empty).get("attributes")).isEmpty(),"empty attribute container supported");
  var unset=nativeComponent(zombie.entity.components.script.EntityScriptInfo.class);
  check(ApocBRPlayerRelationalSnapshot.componentState(unset).containsKey("originalScript")&&ApocBRPlayerRelationalSnapshot.componentState(unset).get("originalScript")==null,"absent original script preserved");
 }
 static ApocBRPlayerRelationalDatabase.Candidate candidate(int i) {
  return new ApocBRPlayerRelationalDatabase.Candidate(UUID.nameUUIDFromBytes(new byte[]{1}),"test","world","live"+i,false,0,i,249,null,List.of());
 }

 static byte[] nativeComponentBytes(Object component)throws Exception {
  Class<?> type=component.getClass();Method save=null;
  while(type!=null&&save==null){try{save=type.getDeclaredMethod("save",java.nio.ByteBuffer.class);}catch(NoSuchMethodException ignored){type=type.getSuperclass();}}
  save.setAccessible(true);var buffer=java.nio.ByteBuffer.allocate(65536);save.invoke(component,buffer);return Arrays.copyOf(buffer.array(),buffer.position());
 }
 static void remainingComponentCodecs()throws Exception {
  P player=new P();Item item=new Item(1);player.inventory.items.add(item);UUID character=UUID.randomUUID();int audited=0;
  for(var type:zombie.entity.ComponentType.values()) {
   Class<?> cls=type.GetComponentClass();if(cls==null||!type.isValidGameEntityType(zombie.entity.GameEntityType.InventoryItem))continue;
   if(Set.of("AttributeContainer","EntityScriptInfo","Durability").contains(cls.getSimpleName()))continue;
   Object component=nativeComponent(cls);check(ApocBRPlayerRelationalCodecs.supports(component),"every remaining native inventory-valid component has an explicit codec: "+type);audited++;
   if(cls.getSimpleName().equals("UiConfig")){field(component,"xuiSkinName","default");field(component,"entityStyleName","fixture-style");field(component,"uiEnabled",true);}
   if(cls.getSimpleName().equals("CraftBench")){
    field(component,"recipeTagQuery","fixture-recipe");
    ((zombie.entity.util.enums.EnumBitStore<zombie.entity.components.resources.ResourceChannel>)ApocBRPlayerRelationalAccess.get(component,"fluidInputChannels")).add(zombie.entity.components.resources.ResourceChannel.Channel_Blue);
   }
   byte[] before=nativeComponentBytes(component);var state=ApocBRPlayerRelationalCodecs.component(component);detached(state,Collections.newSetFromMap(new IdentityHashMap<>()));
   check(Arrays.equals(before,nativeComponentBytes(component)),"component inspection never mutates native payload: "+type);
   if(!Set.of("UiConfig","CraftBench").contains(cls.getSimpleName()))check(before.length==0&&state.isEmpty(),"explicit stateless components retain presence with a reviewed empty native payload");
   item.components.add(component);
  }
  check(audited==10,"all ten additional inventory component families audited against ComponentType");item.componentSize=item.components.size();
  player.extraInfoFlags=0b100101;player.offsetX=0.25f;player.offsetY=0.5f;
  item.byteData=java.nio.ByteBuffer.wrap(new byte[]{87,86,69,82,0,0,0,(byte)249,9});
  var before=liveGraph(player,character);check(before.issues().isEmpty(),"all native inventory-valid components and native byteData fields publish complete coverage");
  var state=before.rows().stream().filter(r->r.table().equals("character_state")).findFirst().orElseThrow().values();
  check(state.get("source_offset_x").equals(0.25f)&&state.get("source_offset_y").equals(0.5f),"native offsets are persisted instead of forced NULL");
  check(before.rows().stream().filter(r->r.table().equals("item")).findFirst().orElseThrow().values().get("save_type").equals(-1),"item save-type matches native constant sentinel -1");
  var flags=before.rows().stream().filter(r->r.table().equals("character_cheat")&&((String)r.values().get("cheat_key")).startsWith("extra.")).toList();
  check(flags.size()==6&&flags.stream().filter(r->Boolean.TRUE.equals(r.values().get("enabled"))).count()==3,"all six native extra-info bits retain their individual values");
  var frozen=ApocBRPlayerRelationalSnapshot.copy(player);detached(frozen.player(),Collections.newSetFromMap(new IdentityHashMap<>()));
  for(Object component:item.components) {
   if(component.getClass().getSimpleName().equals("UiConfig"))field(component,"uiEnabled",false);
   if(component.getClass().getSimpleName().equals("CraftBench"))field(component,"recipeTagQuery","changed-recipe");
  }
  item.byteData=java.nio.ByteBuffer.wrap(new byte[]{1,2,3});player.extraInfoFlags=0;player.offsetX=8;
  var isolated=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(frozen,List.of());
  check(ApocBRPlayerRelationalDelta.diff(ApocBRPlayerRelationalDelta.index(before.rows()),ApocBRPlayerRelationalDelta.index(isolated.rows())).isEmpty(),"new component/permission/byteData state is detached before enqueue");
  var changes=ApocBRPlayerRelationalDelta.diff(ApocBRPlayerRelationalDelta.index(before.rows()),ApocBRPlayerRelationalDelta.index(liveGraph(player,character).rows()));
  check(changes.stream().anyMatch(c->c.key().table().equals("value_node")&&c.columns().contains("opaque_value")),"native item byte-array edits produce targeted opaque-value updates");
  check(changes.stream().filter(c->c.key().table().equals("character_cheat")).count()==3,"permission mutations update exactly the changed flags");
  check(changes.stream().noneMatch(c->c.key().table().equals("component")),"component payload mutations retain component identities");
  resolve(zombie.characters.IsoPlayer.class,"extraInfoFlags");
 }
 static class AnimalBreed {public String name="fixture-breed",woolType="Wool";}
 static class AnimalData {
  public AnimalBreed breed=new AnimalBreed();public int age=12,pregnantTime=3,preferredHutchPosition=-1,fertilizedTime=2,lastImpregnateTime=3,clutchSize=4,eggsToday=2,attachedTreeX=123,attachedTreeY=456;
  public float size=1,milkQuantity=2,maxMilkActual=3,woolQty=4,weight=5,maxWool=6;public long lastPregnancyTime=Long.MAX_VALUE,lastMilkTimer=Long.MIN_VALUE;
  public boolean pregnant=true,canHaveMilk=true,fertilized=true;public Object attachedTree=new Object();
 }
 static class AnimalDescriptor {public boolean isFemale=true;}
 static class AnimalDef {public int eggsPerDay=1;}
 static class AnimalGene {public int id=123;public String name="weight";public zombie.characters.animals.AnimalAllele allele1=new zombie.characters.animals.AnimalAllele(),allele2=new zombie.characters.animals.AnimalAllele();}
 static class Zone {public UUID id=UUID.randomUUID();}
 static class Square {public int x=900,y=800,z=1;}
 static class Hook {public Square square=new Square();}
 static class EmbeddedAnimal {
  public float x=1,y=2,z=0,stressLevel=.1f,health=99,petTimer=2;public String forwardIsoDirection="SE",type="chicken",customName="fixture",migrationGroup="group";
  public long timeSinceLastUpdate=123456789L;public int attachBackToTreeX=Integer.MAX_VALUE,attachBackToTreeY=Integer.MAX_VALUE;
  public int itemId=7,animalId=8,attachBackToMother=9,milkRemoved=1,attachBackToHookX=100,attachBackToHookY=200,attachBackToHookZ=1;public short onlineID=123;
  public double hoursSurvived=1.25,virtualId=2.5;public boolean wild=false,onHook=true;
  public AnimalData data=new AnimalData();public AnimalDef adef=new AnimalDef();public AnimalDescriptor descriptor=new AnimalDescriptor();public Stats stats=new Stats();public Zone animalZone=new Zone();public Hook hook=new Hook();
  public Object mother;public Map<Object,Object> table=new LinkedHashMap<>(Map.of("custom","value")),playerAcceptanceList=new HashMap<>();public Map<String,AnimalGene> fullGenome=new HashMap<>();
 }
 static class AnimalInventoryItem extends Item {public EmbeddedAnimal animal=new EmbeddedAnimal();AnimalInventoryItem(int id){super(id);animal.itemId=id;}}
 static void embeddedAnimalCodec()throws Exception {
  EmbeddedAnimal animal=new EmbeddedAnimal();animal.playerAcceptanceList.put((short)1,.75f);var gene=new AnimalGene();gene.allele1.name="weight";gene.allele1.currentValue=.4f;gene.allele2.name="weight";gene.allele2.currentValue=.6f;animal.fullGenome.put("weight",gene);
  var state=ApocBRPlayerRelationalCodecs.animal(animal,123456789L);detached(state,Collections.newSetFromMap(new IdentityHashMap<>()));
  check(state.get("savedAtGameTimeMillis").equals(123456789L)&&((Map<?,?>)state.get("data")).get("lastMilkTimer").equals(Long.MIN_VALUE),"animal capture preserves game clock and full-width native timers");
  check(((Map<?,?>)state.get("data")).get("woolQty").equals(4f)&&((Map<?,?>)state.get("data")).get("eggsToday").equals(2),"breed/definition-conditional animal fields captured");
  check(((Map<?,?>)state.get("hook")).get("x").equals(100),"native saved hook coordinates take precedence when valid");
  animal.attachBackToHookX=0;check(((Map<?,?>)ApocBRPlayerRelationalCodecs.animal(animal,0).get("hook")).get("x").equals(900),"native hook-square fallback captured");
  animal.data.pregnant=false;animal.data.breed.woolType=null;animal.adef.eggsPerDay=0;animal.data.attachedTree=null;animal.onHook=false;
  var absent=ApocBRPlayerRelationalCodecs.animal(animal,0);var data=(Map<?,?>)absent.get("data");
  check(data.get("pregnantTime")==null&&data.get("woolQty")==null&&data.get("eggsToday")==null&&absent.get("hook")==null&&absent.get("attachedTree")==null,"animal optional data follows native writer conditions");
  animal.attachBackToTreeX=321;animal.attachBackToTreeY=654;animal.onHook=true;animal.hook=null;
  var pending=ApocBRPlayerRelationalCodecs.animal(animal,0);check(((Map<?,?>)pending.get("attachedTree")).get("x").equals(321)&&((Map<?,?>)pending.get("hook")).get("x").equals(0),"pending native tree/hook coordinates survive before external objects are mounted");
  P player=new P();AnimalInventoryItem item=new AnimalInventoryItem(7);item.animal=animal;player.inventory.items.add(item);UUID character=UUID.randomUUID();
  var snapshot=ApocBRPlayerRelationalSnapshot.copy(player);detached(snapshot.player(),Collections.newSetFromMap(new IdentityHashMap<>()));
  var before=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(snapshot,List.of());
  check(before.issues().isEmpty()&&before.rows().stream().anyMatch(r->r.table().equals("component")&&r.values().get("kind").equals("item.embeddedAnimal")&&r.values().get("coverage").equals("complete")),"carried animal state uses typed relational nodes with complete coverage");
  check(before.rows().stream().anyMatch(r->r.table().equals("value_node")&&Objects.equals(r.values().get("key_text"),"savedAtGameTimeMillis")&&Objects.equals(r.values().get("integer_value"),123456789L)),"initial import retains the loaded animal clock instead of resetting unprocessed offline time");
  var current=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(ApocBRPlayerRelationalSnapshot.copyLive(player),List.of());
  long gameClock=zombie.GameTime.getInstance().getCalender().getTimeInMillis();
  check(current.rows().stream().anyMatch(r->r.table().equals("value_node")&&Objects.equals(r.values().get("key_text"),"savedAtGameTimeMillis")&&Objects.equals(r.values().get("integer_value"),gameClock)),"live capture uses current native game clock while load capture preserves pending offline clock");
  animal.health=42;animal.data.weight=8;animal.table.put("custom","changed");
  var isolated=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(snapshot,List.of());
  check(ApocBRPlayerRelationalDelta.diff(ApocBRPlayerRelationalDelta.index(before.rows()),ApocBRPlayerRelationalDelta.index(isolated.rows())).isEmpty(),"animal state never escapes into worker through mutable references");
  var changes=ApocBRPlayerRelationalDelta.diff(ApocBRPlayerRelationalDelta.index(before.rows()),ApocBRPlayerRelationalDelta.index(liveGraph(player,character).rows()));
  check(changes.stream().filter(c->c.key().table().equals("value_node")&&c.columns().stream().anyMatch(n->n.endsWith("_value"))).count()>=3&&changes.stream().noneMatch(c->c.key().table().equals("component")),"animal health/weight/mod-data mutations patch stable typed nodes");
  String[] root={"x","y","z","type","customName","itemId","animalId","hoursSurvived","attachBackToMother","milkRemoved","stressLevel","health","virtualId","migrationGroup","petTimer","wild","onlineID","forwardIsoDirection","animalZone","data.breed.name","descriptor.isFemale","stats","table","fullGenome","data.age","data.size","data.pregnant","data.canHaveMilk","data.milkQuantity","data.maxMilkActual","data.preferredHutchPosition","data.fertilizedTime","data.fertilized","data.weight","data.lastPregnancyTime","data.lastMilkTimer","data.lastImpregnateTime","data.clutchSize","data.pregnantTime","data.maxWool","data.woolQty","adef.eggsPerDay","data.eggsToday","mother","data.attachedTree","data.attachedTreeX","data.attachedTreeY","hook","onHook","attachBackToHookX","attachBackToHookY","attachBackToHookZ","playerAcceptanceList","timeSinceLastUpdate","attachBackToTreeX","attachBackToTreeY"};
  for(String path:root){resolve(zombie.characters.animals.IsoAnimal.class,path);checks++;}
 }
 static Map<String,String> metricValues(String line) {
  Map<String,String> values=new HashMap<>();for(String entry:line.split("; ")){int at=entry.indexOf('=');if(at>=0)values.put(entry.substring(0,at),entry.substring(at+1));}return values;
 }
 static void sqlMetrics()throws Exception {
  LiveFake fake=new LiveFake();fake.reason="live_fields";var db=new ApocBRPlayerRelationalDatabase(fake::open);
  var field=new ApocBRPlayerRelationalMutations.Field("character_state","x",null);
  var batch=new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),fake.character,Map.of(field,new ApocBRPlayerRelationalMutations.Value(1,123f)),java.time.Instant.EPOCH);
  check(db.applyFields(batch),"measured scalar transaction commits");var report=metricValues(db.mutationMetrics());
  check(report.get("txCommits").equals("1")&&report.get("writeTx").equals("1")&&report.get("readTx").equals("0"),"actual commits are classified separately from worker acknowledgements");
  check(report.get("jdbcQueries").equals("2")&&report.get("jdbcCommands").equals("2")&&report.get("jdbcUpdates").equals("2")&&report.get("jdbcBatches").equals("1")&&report.get("batchEntries").equals("1"),"query, session-command, individual-update and batch executions counted independently");
  check(report.get("jdbcAffectedRows").equals("3")&&report.get("topTables").contains("character_state.update:1")&&report.get("topColumns").contains("character_state.x:1"),"physical affected rows include metadata while committed table/column counters isolate character changes");
  report=metricValues(db.mutationMetrics());check(report.get("topTables").equals("none")&&report.get("topColumns").equals("none"),"table and column rankings reset each reporting window");
  P player=new P();player.inventory.items.add(new Item(99));var source=new ApocBRPlayerRelationalMutations.Batch(candidate(1),batch.session(),fake.character,Map.of(),java.time.Instant.now(),ApocBRPlayerRelationalSnapshot.copy(player),1);
  db.projectLive(source);report=metricValues(db.mutationMetrics());check(report.get("readTx").equals("1")&&report.get("writeTx").equals("1"),"alias reads and their commit are reported as read transactions");
  fake.fail=true;var nutrition=new ApocBRPlayerRelationalMutations.Field("character_nutrition","calories",null);
  var failure=new ApocBRPlayerRelationalMutations.Batch(candidate(1),batch.session(),fake.character,Map.of(nutrition,new ApocBRPlayerRelationalMutations.Value(2,20f)),java.time.Instant.EPOCH);
  try{db.applyFields(failure);throw new AssertionError("metrics swallowed SQL exception");}catch(SQLException expected){check(expected.getSQLState().equals("08006"),"metrics preserve original SQLException/SQLState");}
  report=metricValues(db.mutationMetrics());check(report.get("txRollbacks").equals("1")&&report.get("jdbcFailures").equals("1")&&report.get("writeTx").equals("1")&&report.get("topColumns").equals("none"),"failed attempts count SQL work and rollback without counting committed dirty fields");
  check(fake.opened==fake.closed,"measurement proxies preserve connection lease ownership");
 }

 static void payloadOrigins()throws Exception {
  System.clearProperty("apocbr.postgres.serverId");
  for(String invalid:List.of("", "0", "-1", "apoc-1", "2147483648")) {
   System.setProperty("apocbr.postgres.serverId",invalid);
   try{ApocBRPayloadProvenance.configuredServerId();throw new AssertionError("invalid server ID accepted");}catch(IllegalArgumentException expected){checks++;}
  }
  System.setProperty("apocbr.postgres.serverId","3");check(ApocBRPayloadProvenance.configuredServerId()==3,"registered numeric configuration parsed without server key fallback");
  P p=new P();Item a=new Item(1),b=new Item(2);p.inventory.items.addAll(List.of(a,b));
  a.byteData=java.nio.ByteBuffer.wrap(new byte[]{1,2});b.byteData=java.nio.ByteBuffer.wrap(new byte[]{1,2});
  var oa=new ApocBRPayloadProvenance(1,UUID.randomUUID(),UUID.randomUUID());var ob=new ApocBRPayloadProvenance(2,UUID.randomUUID(),UUID.randomUUID());
  ApocBRPayloadProvenance.remember(a.byteData,new ApocBRPayloadProvenance.Metadata(oa,249));ApocBRPayloadProvenance.remember(b.byteData,new ApocBRPayloadProvenance.Metadata(ob,249));
  ((java.nio.ByteBuffer)a.byteData).position(1);
  UUID character=UUID.randomUUID();var before=liveGraph(p,character);
  var leaves=before.rows().stream().filter(r->r.table().equals("value_node")&&r.values().get("opaque_value")!=null).toList();
  check(leaves.size()==2&&leaves.stream().map(r->r.values().get("source_server_id")).collect(java.util.stream.Collectors.toSet()).equals(Set.of(1,2)),"identical bytes in distinct buffers retain independent dictionary origins in one graph");
  check(((java.nio.ByteBuffer)a.byteData).position()==1,"provenance capture leaves native cursor intact");
  ApocBRPayloadProvenance.remember(a.byteData,new ApocBRPayloadProvenance.Metadata(ob,250));
  var changes=ApocBRPlayerRelationalDelta.diff(ApocBRPlayerRelationalDelta.index(before.rows()),ApocBRPlayerRelationalDelta.index(liveGraph(p,character).rows()));
  check(changes.size()==1&&changes.getFirst().key().table().equals("value_node")&&new HashSet<>(changes.getFirst().columns()).equals(Set.of("source_server_id","source_dictionary_namespace_id","source_dictionary_view_id","payload_world_version")),"re-encoding identical bytes updates provenance alone through graph mutation diff");
  ApocBRPayloadProvenance.remember(a.byteData,null);var unknown=liveGraph(p,character);
  check(unknown.rows().stream().filter(r->r.table().equals("value_node")&&r.values().get("opaque_value")!=null).anyMatch(r->r.values().get("source_server_id")==null&&r.values().get("payload_world_version")==null),"unknown bytes are never stamped with configured local ID");
 }
 static void sharedLuaState()throws Exception {
  var child=new se.krka.kahlua.j2se.KahluaTableImpl(new LinkedHashMap<>());child.rawset("value",1.0);
  var parent=new se.krka.kahlua.j2se.KahluaTableImpl(new LinkedHashMap<>());parent.rawset("left",child);parent.rawset("right",child);
  var bytes=java.nio.ByteBuffer.allocate(4096);parent.save(bytes);bytes.flip();
  var restored=new se.krka.kahlua.j2se.KahluaTableImpl(new LinkedHashMap<>());restored.load(bytes,249);
  check(restored.rawget("left")!=restored.rawget("right"),"native Kahlua saves shared table values independently, without reference identity");
  P player=new P();player.table=parent;UUID character=UUID.randomUUID();var before=liveGraph(player,character);
  check(before.issues().isEmpty()&&before.rows().stream().filter(r->r.table().equals("value_node")&&Objects.equals(r.values().get("number_value"),1.0)).count()==2,"relational Lua codec matches native shared-table value semantics");
  child.rawset("value",2.0);var changes=ApocBRPlayerRelationalDelta.diff(ApocBRPlayerRelationalDelta.index(before.rows()),ApocBRPlayerRelationalDelta.index(liveGraph(player,character).rows()));
  check(changes.size()==2&&changes.stream().allMatch(c->c.key().table().equals("value_node")&&c.columns().equals(List.of("number_value"))),"mutating shared Lua child updates both persisted value paths without replacing components");
  child.rawset("cycle",parent);try{liveGraph(player,character);throw new AssertionError("cyclic Lua accepted");}catch(IllegalStateException expected){check(expected.getMessage().contains("Cyclic"),"cycles remain explicitly rejected");}
 }
 static void mutationQueue() {
  var q=new ApocBRPlayerRelationalMutations.DirtyQueue();var c=candidate(1);UUID session=UUID.randomUUID(),character=UUID.randomUUID();
  var x=new ApocBRPlayerRelationalMutations.Field("character_state","x",null);
  q.register(c.key(),session);q.mark(c,session,Map.of(x,1f),0,java.time.Instant.EPOCH);
  check(q.poll(0)==null&&q.unboundSize()==1,"live fields wait for initial character binding and report unbound pending work");q.bind(c.key(),session,character);
  check(q.unboundSize()==0,"binding clears the unbound pending diagnostic");
  var first=q.poll(0);check(first.fields().get(x).value().equals(1f),"first dirty value eligible immediately");
  q.mark(c,session,Map.of(x,2f),1,java.time.Instant.EPOCH);q.finish(first,true,2);
  check(q.size()==1&&q.poll(999_999_999L)==null,"in-flight acknowledgement preserves new marks and cadence");
  q.mark(c,session,Map.of(x,3f),999_999_999L,java.time.Instant.EPOCH);
  var second=q.poll(1_000_000_000L);check(second.fields().get(x).value().equals(3f),"repeated marks coalesce without postponing due time");
  q.finish(second,false,1_000_000_000L);check(q.poll(2_999_999_999L)==null,"failed patch backs off without losing dirty values");
  var retry=q.poll(3_000_000_000L);check(retry.fields().get(x).value().equals(3f),"failed value retained for retry");
  UUID replacement=UUID.randomUUID();check(!q.register(c.key(),replacement)&&q.size()==1,"replacement cannot discard an in-flight previous session");
  q.finish(retry,true,3_000_000_000L);check(q.register(c.key(),replacement),"replacement may register after previous work is handed off");q.mark(c,replacement,Map.of(x,4f),3_000_000_000L,java.time.Instant.EPOCH);
  q.finish(retry,true,3_000_000_000L);q.bind(c.key(),session,UUID.randomUUID());
  check(q.poll(3_000_000_000L)==null,"old session cannot bind or acknowledge replacement session");
  q.bind(c.key(),replacement,character);var current=q.poll(3_000_000_000L);check(current.fields().get(x).value().equals(4f),"replacement session retains its own latest value");q.finish(current,true,3_000_000_000L);
  q.mark(c,replacement,Map.of(x,5f),3_000_000_001L,java.time.Instant.EPOCH);
  check(q.poll(3_999_999_999L)==null,"cooldown survives a fully drained pending entry");q.finish(q.poll(4_000_000_000L),true,4_000_000_000L);
  var many=new ApocBRPlayerRelationalMutations.DirtyQueue();
  for(int i=0;i<512;i++){var ci=candidate(i);UUID si=UUID.randomUUID();check(many.register(ci.key(),si),"bounded registration accepted");many.bind(ci.key(),si,character);many.mark(ci,si,Map.of(x,(float)i),0,java.time.Instant.EPOCH);}
  check(!many.register(candidate(999).key(),UUID.randomUUID())&&many.size()==512,"registry and dirty cache stay bounded");
  for(int i=0;i<512;i++){var b=many.poll(0);check(b!=null,"due players are not starved");many.finish(b,true,0);}
  check(many.size()==0,"more than 200 independent players drain without lost work");many.prune(Set.of());check(many.register(candidate(999).key(),UUID.randomUUID()),"idle session bindings are reclaimable");
  P player=new P();player.forbidExportReads=true;
  var before=ApocBRPlayerRelationalMutations.read(player);player.x=1;var after=ApocBRPlayerRelationalMutations.read(player);
  check(after.entrySet().stream().filter(e->!Objects.equals(before.get(e.getKey()),e.getValue())).count()==1,"scalar sample detects one changed field without touching inventory/equipment");
  try{new ApocBRPlayerRelationalMutations.Field("item","name",null);throw new AssertionError("untracked SQL field accepted");}catch(IllegalArgumentException expected){checks++;}
 }
 static class LiveFake {
  UUID dataset=candidate(1).dataset(),character=UUID.randomUUID(),head=UUID.randomUUID(),nextHead;
  String reason="sqlite_load";boolean fail,missing,missingRow,locked=true,stale;int codec=ApocBRPlayerRelationalCapture.CODEC_VERSION;volatile int commits,rollbacks,opened,closed;long revision;
  List<ApocBRPlayerRelationalCapture.Row> graphRows=List.of();List<Object[]> aliasRows=List.of();int graphReads;
  List<String> queries=new ArrayList<>(),batchOrder=new ArrayList<>();List<Map<Integer,Object>> updates=new ArrayList<>();Map<String,List<Map<Integer,Object>>> bound=new LinkedHashMap<>();
  Connection open(){opened++;return(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(o,m,a)->{
   switch(m.getName()){
    case "close":closed++;return null;
    case "commit":commits++;if(nextHead!=null){head=nextHead;reason="live_fields";nextHead=null;}revision++;return null;
    case "rollback":rollbacks++;nextHead=null;return null;
    case "prepareStatement":String sql=(String)a[0];queries.add(sql);Map<Integer,Object> values=new HashMap<>();List<Map<Integer,Object>> batch=new ArrayList<>();
     return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,pm,pa)->{
      if(pm.getName().startsWith("set")&&pa.length>=2&&pa[0] instanceof Integer){values.put((int)pa[0],pa[1]);return null;}
      switch(pm.getName()){
       case "addBatch":batch.add(new HashMap<>(values));return null;
       case "clearBatch":batch.clear();return null;
       case "executeBatch":
        batchOrder.add(sql);
        if(sql.startsWith("UPDATE player_rel.character_nutrition")&&fail)throw new SQLException("simulated","08006");
        updates.addAll(batch);bound.computeIfAbsent(sql,k->new ArrayList<>()).addAll(batch);int[] results=new int[batch.size()];Arrays.fill(results,missingRow?0:1);return results;
       case "executeQuery":
        if(sql.startsWith("SELECT native_item_id,")||sql.startsWith("SELECT DISTINCT ON (native_item_id)")) {
         int[] at={-1};return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(r,rm,ra)->{if(rm.getName().equals("next"))return ++at[0]<aliasRows.size();if(rm.getName().startsWith("get"))return aliasRows.get(at[0])[(int)ra[0]-1];return zero(rm.getReturnType());});
        }
        if(sql.startsWith("SELECT snapshot_id,")) {
         graphReads++;String table=sql.split(" FROM player_rel.")[1].split(" ")[0];var selected=graphRows.stream().filter(row->row.table().equals(table)).toList();int[] at={-1};
         var names=ApocBRPlayerRelationalSchema.TABLES.get(table).stream().map(ApocBRPlayerRelationalSchema.Column::name).toList();
         return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(r,rm,ra)->{if(rm.getName().equals("next"))return ++at[0]<selected.size();if(rm.getName().equals("getObject"))return selected.get(at[0]).values().get(names.get((int)ra[0]-1));return zero(rm.getReturnType());});
        }
        boolean[] advanced={false};return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(r,rm,ra)->{
        switch(rm.getName()){
         case "next":boolean result=!advanced[0]&&!sql.startsWith("SELECT c.id FROM")&&(!missing||sql.contains("advisory"));advanced[0]=true;return result;
         case "getBoolean":return locked;
         case "getObject":int col=(int)ra[0];return col==1?character:col==2?head:dataset;
         case "getString":return (int)ra[0]==4||(int)ra[0]==3?"complete":reason;
         case "getInt":return (int)ra[0]==(sql.contains("FOR UPDATE")?5:4)?ApocBRPlayerRelationalCapture.SCHEMA_VERSION:codec;
         case "getLong":return revision;
        }return zero(rm.getReturnType());
       });
       case "executeUpdate":
        if(sql.startsWith("UPDATE player_rel.character_nutrition")&&fail)throw new SQLException("simulated","08006");
        if(sql.startsWith("UPDATE player_rel.character_state")||sql.startsWith("UPDATE player_rel.character_nutrition"))updates.add(new HashMap<>(values));
        if(sql.startsWith("UPDATE player_rel.character_head")){if(stale)return 0;nextHead=(UUID)values.get(1);}
        return 1;
       case "execute":return true;
      }return zero(pm.getReturnType());
     });
   }return zero(m.getReturnType());
  });}
 }
 static void mutationDatabase()throws Exception {
  LiveFake f=new LiveFake();var db=new ApocBRPlayerRelationalDatabase(f::open);UUID original=f.head;
  var x=new ApocBRPlayerRelationalMutations.Field("character_state","x",null);var calories=new ApocBRPlayerRelationalMutations.Field("character_nutrition","calories",null);
  var patch=new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),f.character,Map.of(x,new ApocBRPlayerRelationalMutations.Value(1,123f),calories,new ApocBRPlayerRelationalMutations.Value(2,456f)),java.time.Instant.EPOCH);
  check(db.applyFields(patch)&&f.commits==1&&!f.head.equals(original),"first live patch atomically forks and publishes a live head");
  check(f.queries.stream().anyMatch(s->s.startsWith("INSERT INTO player_rel.value_node")&&s.contains("source_server_id,source_dictionary_namespace_id,source_dictionary_view_id,payload_world_version")&&s.contains("SELECT ?,component_id")),"live fork copies every opaque origin column unchanged");
  check(f.queries.stream().noneMatch(s->s.startsWith("INSERT INTO player_rel.character_head")),"fork never clones the head metadata table");
  check(f.updates.stream().allMatch(v->!original.equals(v.get(2))),"initial baseline scalar rows remain untouched");
  check(f.queries.stream().anyMatch(s->s.startsWith("UPDATE player_rel.character_state SET x=? WHERE")),"only the dirty state column is updated");
  long copies=f.queries.stream().filter(s->s.startsWith("INSERT")).count();UUID live=f.head;
  check(db.applyFields(patch)&&f.head.equals(live)&&f.queries.stream().filter(s->s.startsWith("INSERT")).count()==copies,"later patches update live generation without copying inventory again");
  int committed=f.commits;f.fail=true;
  try{db.applyFields(patch);throw new AssertionError("SQL failure accepted");}catch(SQLException expected){checks++;}
  check(f.commits==committed&&f.rollbacks>0&&f.head.equals(live),"failed live patch rolls back without publishing");f.fail=false;
  f.missingRow=true;try{db.applyFields(patch);throw new AssertionError("missing scalar row accepted");}catch(SQLException expected){check(f.commits==committed,"zero-row updates never acknowledge missing baseline data");}f.missingRow=false;
  f.missing=true;check(!db.applyFields(patch)&&f.commits==committed,"missing baseline retains queued work");f.missing=false;
  f.locked=false;check(!db.applyFields(patch)&&f.commits==committed,"busy slot never blocks or acknowledges dirty fields");f.locked=true;
  f.codec=1;check(!db.applyFields(patch),"old codec head waits for baseline upgrade");f.codec=ApocBRPlayerRelationalCapture.CODEC_VERSION;
  f.character=UUID.randomUUID();try{db.applyFields(patch);throw new AssertionError("wrong character life accepted");}catch(SQLException expected){check(expected.getSQLState().equals("40001"),"character-life mismatch rejected");}f.character=patch.character();
  f.dataset=UUID.randomUUID();try{db.applyFields(patch);throw new AssertionError("foreign dataset accepted");}catch(SQLException expected){check(expected.getSQLState().equals("22023"),"foreign source rejected");}f.dataset=candidate(1).dataset();
  f.stale=true;try{db.applyFields(patch);throw new AssertionError("stale revision accepted");}catch(SQLException expected){check(expected.getSQLState().equals("40001"),"stale head publication rolls back");}
  check(f.opened==f.closed,"live transactions always return their lease");
 }
 static void extendedMutations()throws Exception {
  P player=new P();Descriptor descriptor=new Descriptor();player.descriptor=descriptor;
  var before=ApocBRPlayerRelationalMutations.read(player);var frozen=ApocBRPlayerRelationalSnapshot.copy(player);
  UUID character=UUID.randomUUID();var graph=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(frozen,List.of());
  for(var entry:before.entrySet()){
   var field=entry.getKey();var row=graph.rows().stream().filter(r->r.table().equals(field.table())&&(field.key()==null||field.key().equals(r.values().get("stat_key")))).findFirst().orElseThrow();
   check(Objects.equals(entry.getValue(),row.values().get(field.column())),"live field matches initial codec: "+field);
  }
  player.tagColor.r=0.25f;player.tagPrefix=null;player.displayName="After";player.allChatMuted=true;player.factionPvp=true;
  descriptor.forename="After";descriptor.characterProfession=null;descriptor.voicePitch=1.5f;descriptor.voiceType=3;
  player.bodyDamage.hasACold=true;player.bodyDamage.infectionTime=123.5f;player.bodyDamage.painReduction=0.75f;
  player.forbidExportReads=true;player.bodyDamage.bodyParts=List.of(new Object());
  var after=ApocBRPlayerRelationalMutations.read(player);Map<ApocBRPlayerRelationalMutations.Field,Object> changed=new LinkedHashMap<>();
  for(var entry:after.entrySet())if(!Objects.equals(entry.getValue(),before.get(entry.getKey())))changed.put(entry.getKey(),entry.getValue());
  Set<String> actual=new HashSet<>();for(var field:changed.keySet())actual.add(field.table()+"."+field.column());
  check(actual.equals(Set.of("character_presentation.tag_r","character_presentation.tag_prefix","character_presentation.display_name","character_presentation.all_chat_muted","character_presentation.faction_pvp",
   "character_descriptor.forename","character_descriptor.profession_key","character_descriptor.voice_pitch","character_descriptor.voice_type",
   "body_damage.has_a_cold","body_damage.infection_time","body_damage.pain_reduction")),"new scopes detect precisely edited scalar fields without scanning body parts or inventory");
  check(ApocBRPlayerRelationalMutations.read(frozen.player()).equals(before),"extended scalar reads accept detached snapshots and remain isolated from mutation");
  LiveFake fake=new LiveFake();fake.character=character;fake.reason="live_fields";var c=candidate(1);UUID session=UUID.randomUUID();
  var queue=new ApocBRPlayerRelationalMutations.DirtyQueue();queue.register(c.key(),session);queue.bind(c.key(),session,character);
  check(queue.mark(c,session,changed,0,java.time.Instant.EPOCH),"nullable extended fields enter bounded dirty queue");
  var batch=queue.poll(0);check(new ApocBRPlayerRelationalDatabase(fake::open).applyFields(batch),"new groups persist in one atomic live patch");queue.finish(batch,true,0);
  check(queue.size()==0&&fake.commits==1,"extended field acknowledgement follows transaction commit");
  for(String table:List.of("character_presentation","character_descriptor","body_damage"))check(fake.queries.stream().anyMatch(sql->sql.startsWith("UPDATE player_rel."+table+" SET ")),"extended row uses partial UPDATE: "+table);
  check(fake.bound.entrySet().stream().anyMatch(e->e.getKey().contains("tag_prefix=?")&&e.getValue().stream().anyMatch(values->values.containsValue(null))),"nullable presentation values bind as SQL null");
  check(fake.bound.entrySet().stream().anyMatch(e->e.getKey().contains("profession_key=?")&&e.getValue().stream().anyMatch(values->values.containsValue(null))),"nullable descriptor values bind as SQL null");
  player.descriptor=null;var absent=ApocBRPlayerRelationalMutations.read(player);
  check(absent.keySet().stream().noneMatch(f->f.table().equals("character_descriptor")),"absent optional descriptor produces no deletion or default writes");
  check(fake.queries.stream().noneMatch(sql->sql.startsWith("DELETE")),"scalar extension does not invent row lifecycle operations");
 }

 static ApocBRPlayerRelationalCapture.Graph liveGraph(P player,UUID character) {
  return new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(ApocBRPlayerRelationalSnapshot.copy(player),List.of());
 }
 static void orphanEquipment()throws Exception {
  long skipped=ApocBRPlayerRelationalCapture.skippedEquipmentReferences();
  UUID character=UUID.randomUUID();P player=new P();player.descriptor=new Descriptor();
  Item owned=new Item(1),stale=new Item(1),outside=new Item(99);player.inventory.items.add(owned);
  player.leftHandItem=stale;player.rightHandItem=owned;
  player.wornItems.add(new Worn(outside));player.attachedItems=List.of(new Worn(outside));
  var graph=liveGraph(player,character);
  check(graph.issues().isEmpty(),"unreachable equipment references do not reject the supported inventory graph");
  check(graph.items()==1&&graph.rows().stream().filter(r->r.table().equals("item_location")).count()==1,"orphan equipment never invents ownership or duplicates an item with the same native ID");
  var equipment=graph.rows().stream().filter(r->r.table().equals("equipment")).toList();
  check(equipment.size()==1&&equipment.getFirst().values().get("slot").equals("secondary"),"only actual reachable equipment is persisted");
  check(ApocBRPlayerRelationalCapture.skippedEquipmentReferences()==skipped+3,"unreachable hand, worn and attached references are counted explicitly");
  check(player.leftHandItem==stale&&player.wornItems.size()==1&&player.attachedItems.size()==1,"projection leaves live equipment untouched");
  player.leftHandItem=owned;player.inventory.items.add(outside);
  var recovered=liveGraph(player,character);
  check(recovered.rows().stream().filter(r->r.table().equals("equipment")).count()==4,"equipment references recover naturally once items become reachable");
  check(ApocBRPlayerRelationalCapture.skippedEquipmentReferences()==skipped+3,"reachable equipment does not increment the skipped-reference counter");
  LiveFake fake=new LiveFake();fake.character=character;fake.reason="live_fields";fake.graphRows=recovered.rows();
  var batch=new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),character,Map.of(),java.time.Instant.now());
  var db=new ApocBRPlayerRelationalDatabase(fake::open);
  check(db.applyFields(batch,graph),"orphan equipment graph reaches JDBC commit instead of infinite projection retry");
  check(fake.queries.stream().anyMatch(sql->sql.startsWith("DELETE FROM player_rel.equipment")),"obsolete equipment links are removed from persisted state");
 }

 static void duplicateNativeItems()throws Exception {
  long skipped=ApocBRPlayerRelationalCapture.skippedDuplicateItems();
  UUID character=UUID.randomUUID(),known=UUID.randomUUID();P player=new P();
  Item original=new Item(7),duplicate=new Item(7),following=new Item(8);
  original.name="original";original.condition=91;duplicate.name="duplicate";duplicate.condition=12;
  player.inventory.items.addAll(List.of(original,duplicate,following));
  player.leftHandItem=original;player.rightHandItem=duplicate;
  var snapshot=ApocBRPlayerRelationalSnapshot.copyLive(player);
  var graph=new ApocBRPlayerRelationalCapture(character,Map.of(7,known),Long.MAX_VALUE).capture(snapshot,List.of());
  check(graph.issues().isEmpty()&&graph.items()==2,"duplicate native ID does not reject the complete graph");
  var itemRows=graph.rows().stream().filter(r->r.table().equals("item")).toList();
  check(itemRows.size()==2&&itemRows.stream().filter(r->r.values().get("native_id").equals(7)).count()==1,"one row per native ID");
  var retained=itemRows.stream().filter(r->r.values().get("native_id").equals(7)).findFirst().orElseThrow().values();
  check(retained.get("id").equals(known)&&retained.get("name").equals("original")&&retained.get("condition").equals(91),"first object retains its alias and state without overwrite");
  var equipment=graph.rows().stream().filter(r->r.table().equals("equipment")).toList();
  check(equipment.size()==1&&equipment.getFirst().values().get("slot").equals("primary")&&equipment.getFirst().values().get("item_id").equals(known),"rejected copy cannot borrow retained object's equipment identity");
  check(graph.rows().stream().filter(r->r.table().equals("item_location")).count()==2&&graph.edges()==3&&graph.objects()==4&&graph.containers()==1,"counts and ownership exclude rejected copy");
  check(ApocBRPlayerRelationalCapture.skippedDuplicateItems()==skipped+1,"duplicate filtering is counted");
  check(player.inventory.items.equals(List.of(original,duplicate,following))&&player.rightHandItem==duplicate&&duplicate.id==7,"capture leaves engine inventory, equipment and native IDs untouched");
  var direct=new ApocBRPlayerRelationalCapture(character,Map.of(7,known),Long.MAX_VALUE).capture(player,List.of());
  check(canonical(direct).equals(canonical(graph)),"direct and detached projection use the same keep-first policy");
  original.condition=3;duplicate.name="changed-after-copy";
  var detached=new ApocBRPlayerRelationalCapture(character,Map.of(7,known),Long.MAX_VALUE).capture(snapshot,List.of());
  check(canonical(detached).equals(canonical(graph)),"worker projection never rereads changed live items");

  // Depth-first source order: a nested child precedes a later item at the root.
  P nested=new P();var bag=new InventoryContainer(10);Item inside=new Item(11),outside=new Item(11);
  inside.name="nested-first";outside.name="root-later";bag.container.items.add(inside);
  nested.inventory.items.addAll(List.of(bag,outside,new Item(12)));
  var nestedGraph=liveGraph(nested,character);
  check(nestedGraph.items()==3&&nestedGraph.rows().stream().anyMatch(r->r.table().equals("item")&&r.values().get("name").equals("nested-first")),"nested inventory uses depth-first order");
  check(nestedGraph.rows().stream().noneMatch(r->r.table().equals("item")&&r.values().get("name").equals("root-later")),"later root duplicate is skipped");

  // Reject an entire duplicate bag, including descendants, without orphan rows.
  P bags=new P();var keptBag=new InventoryContainer(20);var rejectedBag=new InventoryContainer(20);
  keptBag.container.items.add(new Item(21));rejectedBag.container.items.add(new Item(22));
  bags.inventory.items.addAll(List.of(keptBag,rejectedBag,new Item(23)));
  bags.wornItems.add(new Worn(rejectedBag));
  var bagGraph=liveGraph(bags,character);
  check(bagGraph.items()==3&&bagGraph.containers()==2&&bagGraph.edges()==3,"rejected bag adds no container, child or ownership edges");
  check(bagGraph.rows().stream().noneMatch(r->r.table().equals("item")&&r.values().get("native_id").equals(22)),"rejected bag contents are not merged into retained bag");
  check(bagGraph.rows().stream().noneMatch(r->r.table().equals("equipment")),"worn rejected bag is not rebound by native ID");
  check(bags.inventory.items.size()==3&&rejectedBag.container.items.size()==1,"rejected bag and contents remain in engine state");

  P weapons=new P();var weapon=new HandWeapon(30);Item attachment=new Item(31),duplicateAttachment=new Item(31);
  attachment.name="first-part";duplicateAttachment.name="second-part";
  weapon.attachmentList.addAll(List.of(attachment,duplicateAttachment));weapons.inventory.items.add(weapon);
  var weaponGraph=liveGraph(weapons,character);
  check(weaponGraph.items()==2&&weaponGraph.rows().stream().filter(r->r.table().equals("item_location")&&r.values().get("relation").equals("weapon_attachment")).count()==1,"weapon attachments use the same keep-first policy");

  // Normalization must not hide cyclic ownership or a repeated owned object.
  P invalid=new P();Item repeated=new Item(40);invalid.inventory.items.addAll(List.of(repeated,repeated));
  try{liveGraph(invalid,character);throw new AssertionError("duplicate object ownership accepted");}
  catch(IllegalStateException expected){check(expected.getMessage().equals("Cyclic or duplicate owned item"),"same-object ownership guard remains enforced");}
  invalid.inventory.items.clear();var cyclicBag=new InventoryContainer(41);invalid.inventory.items.add(cyclicBag);cyclicBag.container.items.add(cyclicBag);
  try{liveGraph(invalid,character);throw new AssertionError("inventory cycle accepted");}
  catch(IllegalStateException expected){check(expected.getMessage().equals("Cyclic or duplicate owned item"),"cycle guard remains enforced");}

  // Exercise worker projection, actual JDBC batch bindings and queue acknowledgement.
  LiveFake fake=new LiveFake();fake.character=character;fake.reason="live_fields";
  P empty=new P();fake.graphRows=liveGraph(empty,character).rows();
  var db=new ApocBRPlayerRelationalDatabase(fake::open);
  var queueField=ApocBRPlayerRelationalMutations.class.getDeclaredField("DIRTY");queueField.setAccessible(true);
  var queue=(ApocBRPlayerRelationalMutations.DirtyQueue)queueField.get(null);queue.clear();
  var c=candidate(1);UUID session=UUID.randomUUID();queue.register(c.key(),session);queue.bind(c.key(),session,character);
  player.fitness.regularityMap.put("squats",0.75f);
  check(queue.snapshot(c,session,ApocBRPlayerRelationalSnapshot.copyLive(player),0),"duplicate-containing snapshot enters normal dirty queue");
  check(ApocBRPlayerRelationalMutations.flushOne(db)&&queue.size()==0&&queue.bytes()==0,"duplicate-containing graph commits and drains pending work");
  check(fake.commits>=2&&fake.rollbacks==0&&fake.opened==fake.closed,"alias read and graph write commit and return leases");
  check(fake.bound.entrySet().stream().filter(e->e.getKey().startsWith("INSERT INTO player_rel.item (")).flatMap(e->e.getValue().stream()).count()==2,"JDBC inserts retained items only");
  check(fake.bound.entrySet().stream().filter(e->e.getKey().startsWith("INSERT INTO player_rel.fitness_value (")).flatMap(e->e.getValue().stream()).anyMatch(v->v.containsValue("regularityMap")&&v.containsValue(0.75f)),"inventory collision does not block fitness persistence");
  check(db.mutationMetrics().contains("; duplicateItemsSkipped="),"duplicate counter appears in live metrics");

  // The same normalized graph is stable; no DML is required on the next capture.
  var baseline=liveGraph(player,character);
  check(ApocBRPlayerRelationalDelta.diff(ApocBRPlayerRelationalDelta.index(baseline.rows()),ApocBRPlayerRelationalDelta.index(liveGraph(player,character).rows())).isEmpty(),"repeated collision capture is a no-op graph");
  queue.clear();
 }

 static void graphMutations()throws Exception {
  UUID character=UUID.randomUUID();P player=new P();player.descriptor=new Descriptor();
  var bag=new InventoryContainer(1);var a=new Item(2);var b=new Item(3);var removed=new Item(4);
  a.visual=new ItemVisual();a.table=new LinkedHashMap<>(Map.of("counter",1L,"nullable","before"));
  var fluid=new FluidContainer();a.components.add(fluid);a.componentSize=1;
  player.inventory.items.addAll(List.of(bag,a,b,removed));player.leftHandItem=a;player.rightHandItem=a;
  player.visual.blood=new byte[]{1,2};player.visual.bodyVisuals=List.of(new ItemVisual());
  var wound=new zombie.characters.BodyDamage.BodyPart(zombie.characters.BodyDamage.BodyPartType.Hand_L,null);player.bodyDamage.bodyParts=List.of(wound);
  var before=liveGraph(player,character);var indexed=ApocBRPlayerRelationalDelta.index(before.rows());
  check(ApocBRPlayerRelationalDelta.diff(indexed,ApocBRPlayerRelationalDelta.index(liveGraph(player,character).rows())).isEmpty(),"repeat capture retains every item/visual/component/value-node identity and emits zero changes");
  // Move a into a bag, swap root positions, delete one item, create another and switch equipment.
  player.inventory.items.clear();player.inventory.items.addAll(List.of(b,bag,new Item(5)));bag.container.items.add(a);player.leftHandItem=b;player.rightHandItem=null;
  a.condition=42;((ItemVisual)a.visual).blood[0]=99;((Map<String,Object>)a.table).put("counter",2L);((Map<?,?>)a.table).remove("nullable");fluid.fluids.get(0).amount=1;
  player.knownRecipes=List.of("NewRecipe","AnotherRecipe");player.characterTraits.knownTraits=List.of("base:brave","base:strong");player.xp.xpMap.put("base:fitness",123f);
  player.fitness.regularityMap.put("squats",0.5f);player.readLiterature.put("Book",5);player.visual.blood[1]=88;
  player.savedVehicleSeat=2;player.savedVehicleX=10;player.savedVehicleY=20;
  field(wound,"bandaged",true);field(wound,"bandageLife",5f);
  var after=liveGraph(player,character);var next=ApocBRPlayerRelationalDelta.index(after.rows());var changes=ApocBRPlayerRelationalDelta.diff(indexed,next);
  Set<String> tables=new HashSet<>();for(var c:changes)tables.add(c.key().table());
  check(tables.containsAll(Set.of("item","item_location","equipment","visual_body_value","value_node","character_recipe","character_trait","character_perk","fitness_value","character_literature","character_vehicle_return","body_part")),"all pending collection families participate in graph dirty checks");
  check(changes.stream().anyMatch(c->c.key().table().equals("item")&&c.columns().equals(List.of("condition"))),"item condition changes update only condition");
  var replay=new LinkedHashMap<>(indexed);for(var c:changes){if(c.after()==null)replay.remove(c.key());else replay.put(c.key(),c.after());}
  check(ApocBRPlayerRelationalDelta.diff(replay,next).isEmpty(),"CRUD replay reconstructs the entire new supported graph");
  check(changes.stream().filter(c->c.key().table().equals("item_location")&&c.before()!=null&&c.after()!=null).allMatch(ApocBRPlayerRelationalDelta.Change::replace),"ownership/position moves free old unique-index destinations");
  check(changes.stream().noneMatch(c->c.key().table().equals("component")),"fluid and Lua field changes preserve component IDs");
  LiveFake fake=new LiveFake();fake.character=character;fake.reason="live_fields";fake.graphRows=before.rows();var db=new ApocBRPlayerRelationalDatabase(fake::open);
  var batch=new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),character,Map.of(),java.time.Instant.now());
  check(db.applyFields(batch,after),"full graph delta commits against a locked complete head");
  String componentMetrics=metricValues(db.mutationMetrics()).get("topComponents");check(componentMetrics.contains("entity.FluidContainer:")&&componentMetrics.contains("lua.modData:"),"generic value-node changes are attributed to component kinds without logging user keys or values");
  check(fake.bound.keySet().stream().anyMatch(sql->sql.startsWith("UPDATE player_rel.item SET condition=? WHERE")),"JDBC uses partial item updates");
  check(fake.bound.entrySet().stream().anyMatch(e->e.getKey().startsWith("INSERT INTO player_rel.fitness_value")&&e.getValue().stream().anyMatch(values->values.containsValue("regularityMap")&&values.containsValue("squats")&&values.containsValue(0.5f))),"exercise regularity reaches actual JDBC batch bindings through detached capture and graph delta");
  check(fake.queries.stream().anyMatch(sql->sql.startsWith("UPDATE player_rel.source_item_alias SET retired_at"))&&fake.queries.stream().anyMatch(sql->sql.startsWith("INSERT INTO player_rel.source_item_alias")),"deleted/new items retire and allocate source alias generations");
  int firstInsert=100000,lastDelete=-1;for(int i=0;i<fake.queries.size();i++){String sql=fake.queries.get(i);if(sql.startsWith("DELETE FROM"))lastDelete=i;if(sql.startsWith("INSERT INTO player_rel.item_location"))firstInsert=Math.min(firstInsert,i);}
  check(lastDelete<firstInsert,"all deletes precede destination inserts for swaps");
  int reads=fake.graphReads;int statements=fake.queries.size();check(db.applyFields(batch,liveGraph(player,character)),"unchanged graph acknowledges successfully");
  check(fake.graphReads==reads&&fake.queries.subList(statements,fake.queries.size()).stream().noneMatch(sql->sql.startsWith("UPDATE")||sql.startsWith("INSERT")||sql.startsWith("DELETE")),"unchanged capture uses bounded worker baseline cache and performs no writes");
  a.condition=43;var failed=liveGraph(player,character);fake.stale=true;int commits=fake.commits;
  try{db.applyFields(batch,failed);throw new AssertionError("stale graph accepted");}catch(SQLException expected){check(fake.commits==commits,"stale graph rolls back atomically");}fake.stale=false;
  statements=fake.queries.size();check(db.applyFields(batch,failed),"failed graph remains retryable");
  check(fake.queries.subList(statements,fake.queries.size()).stream().anyMatch(sql->sql.startsWith("UPDATE player_rel.item SET condition=?")),"failed transaction never advances comparison cache");
  var bad=new ApocBRPlayerRelationalCapture.Graph(after.snapshot(),character,after.capturedAt(),after.rows(),List.of("UNSUPPORTED_COMPONENT_Test"),after.bytes(),after.objects(),after.items(),after.containers(),after.edges());
  int opened=fake.opened;try{db.applyFields(batch,bad);throw new AssertionError("incomplete graph accepted");}catch(SQLException expected){check(fake.opened==opened,"unsupported graph is rejected before SQL");}
  var incompleteMetrics=metricValues(db.mutationMetrics());check(incompleteMetrics.get("incompleteGraphs").equals("1")&&incompleteMetrics.get("incompleteReasons").contains("UNSUPPORTED_COMPONENT_Test:1"),"coverage failures remain visible even when they execute no SQL");
  player.descriptor=null;player.savedVehicleSeat=-1;player.table=null;var absent=liveGraph(player,character);
  var removals=ApocBRPlayerRelationalDelta.diff(next,ApocBRPlayerRelationalDelta.index(absent.rows()));
  check(removals.stream().anyMatch(c->c.key().table().equals("character_descriptor")&&c.after()==null)&&removals.stream().anyMatch(c->c.key().table().equals("character_vehicle_return")&&c.after()==null),"optional singleton deletion is represented explicitly");
  // Every supported table has an explicit immutable logical key (including nullable visual owners).
  for(var entry:ApocBRPlayerRelationalDelta.KEYS.entrySet())for(String column:entry.getValue())check(ApocBRPlayerRelationalSchema.TABLES.get(entry.getKey()).stream().anyMatch(c->c.name().equals(column)),"valid V5 logical key: "+entry.getKey()+"."+column);
  check(fake.opened==fake.closed,"graph transactions return every connection lease");
 }
 static void graphQueue() {
  var q=new ApocBRPlayerRelationalMutations.DirtyQueue();var c=candidate(1);UUID session=UUID.randomUUID(),character=UUID.randomUUID();
  q.register(c.key(),session);q.bind(c.key(),session,character);var x=new ApocBRPlayerRelationalMutations.Field("character_state","x",null);
  q.mark(c,session,Map.of(x,1f),0,java.time.Instant.EPOCH);var first=ApocBRPlayerRelationalSnapshot.copy(new P());
  check(q.snapshot(c,session,first,0)&&q.bytes()==first.bytes(),"detached graph queue accounts bytes and replaces older scalar marks");
  var batch=q.poll(0);check(batch.snapshot()==first&&batch.fields().isEmpty(),"graph and scalar marks share ordering and dispatch cadence");
  check(!q.register(c.key(),UUID.randomUUID())&&q.bytes()==first.bytes(),"replacement preserves in-flight detached graph and byte accounting");
  check(q.oldestAgeMs(5_000_000L)==5,"queue latency includes work awaiting an in-flight acknowledgement");
  P player=new P();player.x=2;var second=ApocBRPlayerRelationalSnapshot.copy(player);
  check(q.snapshot(c,session,second,1)&&q.bytes()==first.bytes()+second.bytes(),"final capture can replace an in-flight graph within byte bound");
  q.mark(c,session,Map.of(x,3f),2,java.time.Instant.EPOCH);q.finish(batch,true,3);
  check(q.bytes()==second.bytes(),"old graph acknowledgement preserves and accounts newer snapshot");
  var retry=q.poll(1_000_000_000L);check(retry.snapshot()==second&&retry.fields().get(x).value().equals(3f),"new scalar marks overlay the detached graph without regression");
  q.finish(retry,false,1_000_000_000L);check(q.bytes()==second.bytes(),"failed graph stays charged and retryable");
  retry=q.poll(3_000_000_000L);q.finish(retry,true,3_000_000_000L);check(q.bytes()==0&&q.size()==0,"graph commit drains exactly acknowledged bytes");
  UUID replacement=UUID.randomUUID();q.register(c.key(),replacement);check(!q.mark(c,session,Map.of(x,4f),4_000_000_000L,java.time.Instant.EPOCH),"superseded player object cannot overwrite replacement session");
 }

 static class Vehicle {public float x=123,y=456;public boolean isEngineRunning=true;public int getSeat(Object player){return 2;}}
 static void liveVehicle() {
  P player=new P();player.savedVehicleSeat=1;player.savedVehicleX=1;player.savedVehicleY=2;
  var nativeLoad=ApocBRPlayerRelationalSnapshot.copy(player);
  check(nativeLoad.player().get("savedVehicleSeat").equals(1),"initial load preserves saved return location");
  var onFoot=ApocBRPlayerRelationalSnapshot.copyLive(player);
  check(onFoot.player().get("savedVehicleSeat").equals(-1),"live snapshot clears stale saved vehicle after exit");
  player.vehicle=new Vehicle();var driving=ApocBRPlayerRelationalSnapshot.copyLive(player);
  check(driving.player().get("savedVehicleSeat").equals(2)&&driving.player().get("savedVehicleX").equals(123f)&&driving.player().get("savedVehicleRunning").equals(true),"live snapshot follows native save contract for current vehicle seat/position/engine");
 }
 static void characterLives()throws Exception {
  Fake f=new Fake();f.complete=true;f.dataset=candidate(1).dataset();f.character=UUID.randomUUID();f.head=UUID.randomUUID();
  var base=candidate(1);UUID life=UUID.randomUUID();var c=new ApocBRPlayerRelationalDatabase.Candidate(base.dataset(),base.label(),base.world(),base.identity(),base.coop(),base.slot(),0,base.worldVersion(),null,List.of(),life);
  var db=new ApocBRPlayerRelationalDatabase(f::open);var receipt=db.inspect(c);
  check(!receipt.complete()&&receipt.character().equals(life)&&receipt.previousCharacter().equals(f.character)&&receipt.head().equals(f.head)&&receipt.aliases().isEmpty(),"creation does not reuse complete predecessor receipt or its item aliases");
  var graph=liveGraph(new P(),life);check(db.persist(c,receipt,graph),"new character publishes against guarded predecessor head");
  var characterInsert=f.bound.entrySet().stream().filter(e->e.getKey().startsWith("INSERT INTO player_rel.character (")).findFirst().orElseThrow().getValue().getFirst();
  check(characterInsert.get(1).equals(life)&&characterInsert.get(3).equals(8),"respawn allocates a distinct UUID and next slot incarnation");
  var slotUpdate=f.bound.entrySet().stream().filter(e->e.getKey().startsWith("UPDATE player_rel.player_slot")).findFirst().orElseThrow().getValue().getFirst();
  check(slotUpdate.get(1).equals(life)&&slotUpdate.get(3).equals(f.character),"active character switches only from inspected predecessor");
  check(f.bound.keySet().stream().anyMatch(sql->sql.startsWith("UPDATE player_rel.character SET retired_at")),"predecessor retirement shares publication transaction");
  check(f.bound.entrySet().stream().filter(e->e.getKey().startsWith("INSERT INTO player_rel.player_slot")).findFirst().orElseThrow().getValue().getFirst().get(2)==null,"new character never invents a native SQLite row ID");
  check(f.bound.entrySet().stream().filter(e->e.getKey().startsWith("INSERT INTO player_rel.snapshot")).findFirst().orElseThrow().getValue().getFirst().containsValue("new_character"),"creation provenance is distinguished from SQLite loads");
  Fake incomplete=new Fake();incomplete.complete=true;incomplete.dataset=c.dataset();incomplete.character=f.character;incomplete.head=f.head;
  var bad=new ApocBRPlayerRelationalCapture.Graph(graph.snapshot(),life,graph.capturedAt(),graph.rows(),List.of("UNSUPPORTED_COMPONENT_Test"),graph.bytes(),graph.objects(),graph.items(),graph.containers(),graph.edges());
  check(new ApocBRPlayerRelationalDatabase(incomplete::open).persist(c,receipt,bad)&&incomplete.headWrites==0&&incomplete.bound.keySet().stream().noneMatch(sql->sql.startsWith("UPDATE player_rel.character SET retired_at")),"incomplete creation never retires previous life or publishes new head");
  incomplete.pendingLife=life;var recoveryDb=new ApocBRPlayerRelationalDatabase(incomplete::open);var recovered=recoveryDb.inspect(base);
  check(!recovered.complete()&&recovered.character().equals(life)&&recovered.previousCharacter().equals(f.character),"later native login resumes newer unpublished incarnation rather than skipping to previous complete life");
  check(recoveryDb.persist(base,recovered,graph)&&incomplete.headWrites==1,"recovered staged incarnation can publish against guarded predecessor");
 }

 static ApocBRPlayerRelationalCapture.Graph trackedGraph(P player,ApocBRPlayerRelationalSnapshot.ItemTracker tracker,UUID character,Map<Integer,UUID> aliases) {
  return new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(ApocBRPlayerRelationalSnapshot.copyLive(player,tracker,character,aliases),List.of());
 }
 static UUID itemIdentity(ApocBRPlayerRelationalCapture.Graph graph,int nativeId) {
  return (UUID)graph.rows().stream().filter(r->r.table().equals("item")&&r.values().get("native_id").equals(nativeId)).findFirst().orElseThrow().values().get("id");
 }
 static void playerItemLifecycles()throws Exception {
  P player=new P();InventoryContainer bag=new InventoryContainer(10);Item child=new Item(11),other=new Item(12);
  bag.container.items.add(child);player.inventory.items.addAll(List.of(bag,other));
  UUID character=UUID.randomUUID(),bagId=UUID.randomUUID(),childId=UUID.randomUUID(),otherId=UUID.randomUUID();
  var aliases=Map.of(10,bagId,11,childId,12,otherId);var tracker=new ApocBRPlayerRelationalSnapshot.ItemTracker(player);
  var before=trackedGraph(player,tracker,character,aliases);
  check(itemIdentity(before,10).equals(bagId)&&itemIdentity(before,11).equals(childId),"first capture seeds persisted UUIDs for a complete owned tree");
  bag.id=20;child.id=21;long changed=ApocBRPlayerRelationalSnapshot.nativeIdChanges();
  var after=trackedGraph(player,tracker,character,Map.of());
  check(itemIdentity(after,20).equals(bagId)&&itemIdentity(after,21).equals(childId),"same-instance ID changes preserve bag and nested item UUIDs without an alias read");
  check(ApocBRPlayerRelationalSnapshot.nativeIdChanges()==changed+2,"net native ID changes are measured");
  var delta=ApocBRPlayerRelationalDelta.diff(ApocBRPlayerRelationalDelta.index(before.rows()),ApocBRPlayerRelationalDelta.index(after.rows()));
  check(delta.size()==2&&delta.stream().allMatch(d->d.key().table().equals("item")&&d.before()!=null&&d.after()!=null&&d.columns().equals(List.of("native_id"))),"ID changes update item rows without rebuilding child/component/container identities");
  LiveFake fake=new LiveFake();fake.character=character;fake.reason="live_fields";fake.graphRows=before.rows();
  var db=new ApocBRPlayerRelationalDatabase(fake::open);
  var batch=new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),character,Map.of(),java.time.Instant.now());
  check(db.applyFields(batch,after),"reassigned graph commits through production JDBC adapter");
  var retired=fake.bound.entrySet().stream().filter(e->e.getKey().startsWith("UPDATE player_rel.source_item_alias")).flatMap(e->e.getValue().stream()).toList();
  var added=fake.bound.entrySet().stream().filter(e->e.getKey().startsWith("INSERT INTO player_rel.source_item_alias")).flatMap(e->e.getValue().stream()).toList();
  check(retired.size()==2&&retired.stream().map(v->v.get(3)).collect(java.util.stream.Collectors.toSet()).equals(Set.of(10,11)),"all old native aliases retired for UUID-preserving updates");
  check(added.size()==2&&added.stream().anyMatch(v->v.get(3).equals(20)&&v.get(4).equals(bagId))&&added.stream().anyMatch(v->v.get(3).equals(21)&&v.get(4).equals(childId)),"new aliases retain the existing persistent UUIDs");
  check(fake.batchOrder.indexOf(retiredSql(fake))<fake.batchOrder.indexOf(addedSql(fake)),"retirement batch executes before additions");
  // Coalescing can skip intermediate native IDs. The newest detached copy is sufficient.
  child.id=31;trackedGraph(player,tracker,character,Map.of());child.id=41;
  var newest=ApocBRPlayerRelationalSnapshot.copyLive(player,tracker,character,Map.of());
  var queue=new ApocBRPlayerRelationalMutations.DirtyQueue();var c=candidate(1);UUID session=UUID.randomUUID();queue.register(c.key(),session);queue.bind(c.key(),session,character);
  queue.snapshot(c,session,ApocBRPlayerRelationalSnapshot.copyLive(player,tracker,character,Map.of()),0);queue.snapshot(c,session,newest,1);
  var queued=queue.poll(1);check(itemIdentity(new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(queued.snapshot(),List.of()),41).equals(childId),"dirty queue coalescing keeps original persistent identity");
  java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(var out=new java.io.ObjectOutputStream(bytes)){out.writeObject(queued.snapshot());}
  ApocBRPlayerRelationalSnapshot.Snapshot restored;try(var in=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))){restored=(ApocBRPlayerRelationalSnapshot.Snapshot)in.readObject();}
  var replayed=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(restored,List.of());
  check(itemIdentity(replayed,41).equals(childId),"serialized detached replay retains reassignment identity");
  // ID swaps must not exchange object identities or insert before releasing old aliases.
  other.id=41;child.id=12;var swapped=trackedGraph(player,tracker,character,Map.of());
  check(itemIdentity(swapped,12).equals(childId)&&itemIdentity(swapped,41).equals(otherId),"exact-instance evidence wins during native ID swaps");
  LiveFake swapFake=new LiveFake();swapFake.character=character;swapFake.reason="live_fields";swapFake.graphRows=replayed.rows();
  check(new ApocBRPlayerRelationalDatabase(swapFake::open).applyFields(batch,swapped),"ID swap commits without exchanging UUIDs");
  check(swapFake.batchOrder.lastIndexOf(retiredSql(swapFake))<swapFake.batchOrder.indexOf(addedSql(swapFake)),"all old swap aliases are released before first new alias batch");
  // Removing the bag removes its entire tree; a return is a fresh lifecycle.
  player.inventory.items.remove(bag);var absent=trackedGraph(player,tracker,character,Map.of());
  var removed=ApocBRPlayerRelationalDelta.diff(ApocBRPlayerRelationalDelta.index(swapped.rows()),ApocBRPlayerRelationalDelta.index(absent.rows()));
  check(absent.items()==1&&removed.stream().filter(d->d.key().table().equals("item")&&d.after()==null).count()==2,"departure deletes bag and nested item rows from current graph");
  player.inventory.items.add(bag);var returned=trackedGraph(player,tracker,character,aliases);
  check(!itemIdentity(returned,20).equals(bagId)&&!itemIdentity(returned,12).equals(childId),"observed departure ends identities even for the same returning Java objects");
  // A reconstructed instance with the same ID is continuous only while present in the previous tree.
  Item reconstruction=new Item(41);player.inventory.items.set(0,reconstruction);var rebuilt=trackedGraph(player,tracker,character,Map.of());
  check(itemIdentity(rebuilt,41).equals(otherId),"same native ID can reconnect a reconstructed continuously present item");
  player.inventory.items.remove(reconstruction);trackedGraph(player,tracker,character,Map.of());
  Item incoming=new Item(41);player.inventory.items.add(incoming);var arrived=trackedGraph(player,tracker,character,aliases);
  check(!itemIdentity(arrived,41).equals(otherId),"incoming reused native ID cannot revive an ended lifecycle");
  // Same-instance state wins even if another object takes its previous ID.
  UUID incomingId=itemIdentity(arrived,41);incoming.id=51;Item collision=new Item(41);player.inventory.items.add(0,collision);
  var reallocated=trackedGraph(player,tracker,character,Map.of());
  check(itemIdentity(reallocated,51).equals(incomingId)&&!itemIdentity(reallocated,41).equals(incomingId),"new item cannot steal an existing instance's identity by native ID");
  long skipped=ApocBRPlayerRelationalCapture.skippedExternalReferences();
  // Use a real engine container for structured reference classification.
  zombie.inventory.ItemContainer foreign=new zombie.inventory.ItemContainer();
  player.table=new LinkedHashMap<>(Map.of("outside",foreign,"self",player));
  var external=trackedGraph(player,tracker,character,Map.of());
  check(external.issues().isEmpty()&&external.rows().stream().anyMatch(r->r.table().equals("value_node")&&"outside".equals(r.values().get("key_text"))&&r.values().get("value_kind").equals("null")&&r.values().get("reference_object_id")==null),"unowned structured reference persists as null without rejecting graph");
  check(((Map<?,?>)player.table).get("outside")==foreign&&ApocBRPlayerRelationalCapture.skippedExternalReferences()==skipped+1,"external reference handling never mutates live mod data and is measured");
  check(external.rows().stream().anyMatch(r->r.table().equals("value_node")&&"self".equals(r.values().get("key_text"))&&r.values().get("value_kind").equals("reference")&&character.equals(r.values().get("reference_object_id"))),"owned structured references remain valid and persist normally");
 }
 static String retiredSql(LiveFake fake){return fake.queries.stream().filter(s->s.startsWith("UPDATE player_rel.source_item_alias")).findFirst().orElseThrow();}
 static String addedSql(LiveFake fake){return fake.queries.stream().filter(s->s.startsWith("INSERT INTO player_rel.source_item_alias")).findFirst().orElseThrow();}
 static void aliasGenerations()throws Exception {
  P player=new P();player.inventory.items.add(new Item(99));var snapshot=ApocBRPlayerRelationalSnapshot.copy(player);LiveFake fake=new LiveFake();UUID old=UUID.randomUUID();
  fake.aliasRows=Collections.singletonList(new Object[]{99,old,4,Timestamp.from(java.time.Instant.EPOCH)});
  var batch=new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),fake.character,Map.of(),java.time.Instant.now(),snapshot,1);
  var db=new ApocBRPlayerRelationalDatabase(fake::open);var graph=db.projectLive(batch);
  UUID id=(UUID)graph.rows().stream().filter(row->row.table().equals("item")).findFirst().orElseThrow().values().get("id");
  check(!id.equals(old)&&id.equals(ApocBRPlayerRelationalDatabase.named("item:"+fake.character+":99:generation:5")),"retired native IDs receive a new object identity for the next generation");
  fake.aliasRows=Collections.singletonList(new Object[]{99,old,4,null});graph=db.projectLive(batch);
  check(graph.rows().stream().filter(row->row.table().equals("item")).findFirst().orElseThrow().values().get("id").equals(old),"active native alias retains object identity");
 }
 static void largeImport() {
  P player=new P();for(int i=0;i<600;i++)player.inventory.items.add(new Item(i+1));
  var input=ApocBRPlayerRelationalSnapshot.copy(player);
  var graph=new ApocBRPlayerRelationalCapture(UUID.randomUUID(),Map.of(),Long.MAX_VALUE).capture(input,List.of());
  check(graph.bytes()>2*1024*1024&&graph.items()==600,"initial import accepts graph larger than former 2MiB ceiling");
  check(graph.issues().isEmpty(),"large import is complete without discarding state");
 }
 static void sizeLimits(String mode) {
  check(ApocBRPlayerRelationalLimits.GRAPH_BYTES==1024*1024&&ApocBRPlayerRelationalLimits.SNAPSHOT_BYTES==1024*1024,"configured byte allowances apply");
  check(ApocBRPlayerRelationalLimits.QUEUE_BYTES==2*1024*1024,"queue floor admits one maximum snapshot/source pair");
  P player=new P();for(int i=0;i<600;i++)player.inventory.items.add(new Item(i+1));
  try{new ApocBRPlayerRelationalCapture(UUID.randomUUID(),Map.of(),Long.MAX_VALUE).capture(player,List.of());throw new AssertionError("configured graph limit ignored");}
  catch(ApocBRPlayerRelationalLimits.SizeExceeded error){
   check(error.getMessage().equals("CAPTURE_SIZE_BUDGET")&&error.rows>0&&error.bytes>0,"graph failure preserves size diagnostics");
   check(mode.equals("size-rows")?error.rows>ApocBRPlayerRelationalLimits.ROWS:error.bytes>error.byteLimit,"requested graph boundary enforced");
   check(error.diagnostic().contains("rowLimit=")&&error.diagnostic().contains("estimatedBytes="),"controlled counters included in diagnostics");
  }
  try{ApocBRPlayerRelationalSnapshot.copy(player);throw new AssertionError("configured snapshot limit ignored");}
  catch(ApocBRPlayerRelationalLimits.SizeExceeded error){check(error.bytes>error.byteLimit&&error.getMessage().equals("SNAPSHOT_SIZE_BUDGET"),"snapshot bound independent of graph bound");}
 }
 static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
 static class Fake {
  UUID pendingLife;
  int opened,closed,commits,rollbacks,inserts,headWrites,batches,codecVersion=ApocBRPlayerRelationalCapture.CODEC_VERSION;boolean complete,fail,stale;UUID dataset,character,head;String status;
  List<String> queries=new ArrayList<>(),itemNames=new ArrayList<>();Map<String,List<Map<Integer,Object>>> bound=new LinkedHashMap<>();
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
       if(name.equals("next")){boolean found=sql.startsWith("SELECT c.id FROM player_rel.character")&&pendingLife!=null||sql.startsWith("SELECT COALESCE(MAX(incarnation)")||sql.contains("advisory")||sql.equals("SELECT id FROM player_rel.player_slot WHERE world=? AND identity_mode=? AND identity_key=? AND player_index=?")||((complete||stale)&&!sql.startsWith("SELECT c.id FROM")&&sql.contains("active_character_id"));boolean result=found&&!advanced[0];advanced[0]=true;return result;}
       if(name.equals("getBoolean"))return true;
       if(name.equals("getInt"))return sql.startsWith("SELECT COALESCE(MAX(incarnation)")?8:((int)ra[0])==5?codecVersion:1;
       if(name.equals("getString"))return "complete";
       if(name.equals("getObject")){int index=(int)ra[0];if(sql.startsWith("SELECT c.id FROM"))return pendingLife;if(sql.startsWith("SELECT id FROM"))return UUID.randomUUID();return index==1?character:index==2?head:dataset;}
       return zero(rm.getReturnType());
      });
      case "addBatch":
       if(sql.startsWith("INSERT INTO player_rel.item (")){
        String[] columns=sql.substring(sql.indexOf('(')+1,sql.indexOf(')')).split(",");
        for(int i=0;i<columns.length;i++)if(columns[i].equals("name"))itemNames.add((String)values.get(i+1));
       }
       queued[0]++;return null;
      case "clearBatch":queued[0]=0;return null;
      case "executeBatch":if(fail)throw new SQLException("simulated","08006");batches++;int[] result=new int[queued[0]];Arrays.fill(result,1);return result;
      case "executeUpdate":bound.computeIfAbsent(sql,k->new ArrayList<>()).add(new HashMap<>(values));if(sql.startsWith("INSERT"))inserts++;if(sql.contains("INSERT INTO player_rel.character_head"))headWrites++;if(sql.startsWith("UPDATE player_rel.snapshot"))status=(String)values.get(1);return 1;
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
  fake.codecVersion=1;var older=db.inspect(candidate);
  check(!older.complete()&&older.character().equals(existing.character())&&older.head().equals(existing.head()),"older codec receipt requests a new snapshot while preserving character/head identity");
  fake.codecVersion=ApocBRPlayerRelationalCapture.CODEC_VERSION;
  check(!db.persist(candidate,receipt,graph)&&fake.headWrites==heads,"stale expected head rejected");
  fake.dataset=UUID.randomUUID();try{db.inspect(candidate);throw new AssertionError("foreign dataset accepted");}catch(SQLException expected){checks++;}
  check(fake.opened==fake.closed,"all JDBC leases closed");
  check(fake.queries.stream().noneMatch(q->q.contains("public.networkplayers")||q.startsWith("CREATE")||q.contains("LOCK TABLE")),"backfill never writes native store or runs DDL/table locks");
  try(var quality=ApocBRPlayerLoadQuality.begin()){
   ApocBRPlayerLoadQuality.issue("ITEM_SKIPPED");try(var nested=ApocBRPlayerLoadQuality.begin()){ApocBRPlayerLoadQuality.issue("INNER");check(nested.issues().equals(List.of("INNER")),"nested quality isolated");}
   ApocBRPlayerLoadQuality.issue("ITEM_SKIPPED");check(quality.issues().equals(List.of("ITEM_SKIPPED")),"quality scope restored and deduplicated");
  }
 }


 static void writeDiagnostics() {
  var q=new ApocBRPlayerRelationalMutations.DirtyQueue();var c=candidate(1);UUID session=UUID.randomUUID();
  var x=new ApocBRPlayerRelationalMutations.Field("character_state","x",null);
  q.register(c.key(),session);q.bind(c.key(),session,UUID.randomUUID());q.mark(c,session,Map.of(x,1f),0,java.time.Instant.EPOCH);
  var first=q.poll(0);String context=q.failureContext(first,0);
  check(context.contains("operation=1;")&&context.contains("attempt=1;")&&context.contains("retryInMs=2000"),"first failure identifies queue episode and retry delay");
  check(!context.contains(c.identity())&&!context.contains(session.toString()),"queue diagnostics exclude player and session identity");
  q.finish(first,false,0);var second=q.poll(2_000_000_000L);
  check(q.failureContext(second,2_000_000_000L)==null,"repeated failures for the same entry are rate limited");q.finish(second,false,2_000_000_000L);
  var third=q.poll(31_000_000_000L);context=q.failureContext(third,31_000_000_000L);
  check(context.contains("operation=1;")&&context.contains("attempt=3;")&&context.contains("pendingAgeMs=31000;")&&context.contains("retryInMs=8000"),"persistent failures retain correlation, age and attempt count");
  q.mark(c,session,Map.of(x,2f),31_000_000_001L,java.time.Instant.EPOCH);
  String recovery=q.finish(third,true,31_000_000_002L);
  check(recovery.contains("operation=1;")&&recovery.contains("attempt=3;")&&recovery.contains("queueDrained=false"),"recovery acknowledges committed batch without claiming concurrent changes drained");
  var fourth=q.poll(32_000_000_000L);context=q.failureContext(fourth,32_000_000_000L);
  check(context.contains("attempt=1;"),"commit resets consecutive failure count and diagnostic suppression");q.finish(fourth,false,32_000_000_000L);
  var fifth=q.poll(34_000_000_000L);recovery=q.finish(fifth,true,34_000_000_000L);
  check(recovery.contains("queueDrained=true")&&q.size()==0,"retry recovery reports a fully drained entry");
  q.mark(c,session,Map.of(x,3f),35_000_000_000L,java.time.Instant.EPOCH);var fresh=q.poll(35_000_000_000L);
  check(q.failureContext(fresh,35_000_000_000L).contains("operation=2;"),"new pending episode has a new correlation number");
  var other=candidate(2);UUID otherSession=UUID.randomUUID();q.register(other.key(),otherSession);q.bind(other.key(),otherSession,UUID.randomUUID());q.mark(other,otherSession,Map.of(x,4f),35_000_000_000L,java.time.Instant.EPOCH);
  check(q.failureContext(q.poll(35_000_000_000L),35_000_000_000L)!=null,"one failing entry cannot suppress diagnostics for another entry");
  var cause=new NullPointerException("private-player-value password=secret");
  cause.setStackTrace(new StackTraceElement[]{new StackTraceElement("zombie.savefile.ApocBRPlayerRelationalCapture","capture","ApocBRPlayerRelationalCapture.java",88)});
  var failure=new IllegalStateException("private-player-value",cause);
  String diagnostic=ApocBRPlayerRelationalMutations.writeFailure(failure);
  check(diagnostic.contains("SQLState=LOCAL")&&diagnostic.contains("exception=java.lang.IllegalStateException")&&diagnostic.contains("cause=java.lang.NullPointerException"),"local write diagnostics identify wrapped exception types");
  check(diagnostic.contains("ApocBRPlayerRelationalCapture.capture:88"),"local write diagnostics locate failure in production source");
  check(!diagnostic.contains("private-player-value")&&!diagnostic.contains("secret"),"write diagnostics exclude throwable messages and player values");
  check(ApocBRPlayerRelationalMutations.writeFailure(new SQLException("private-player-value","23505")).contains("SQLState=23505"),"write diagnostics retain SQLState");
  check(ApocBRPlayerRelationalMutations.writeFailure(new SQLException("private-player-value","secret\n")).contains("SQLState=UNKNOWN"),"invalid SQLState is sanitized");
  check(ApocBRPlayerRelationalMutations.writeFailure(new NoSuchMethodError("private-player-value")).contains("exception=java.lang.NoSuchMethodError"),"write diagnostics identify linkage errors");
  var badGraph=new ApocBRPlayerRelationalCapture(UUID.randomUUID(),Map.of(),Long.MAX_VALUE).capture(new P(),List.of("UNSUPPORTED_ITEM_SUBTYPE","private-player-value token=secret"));
  try{new ApocBRPlayerRelationalDatabase(new LiveFake()::open).applyFields(new ApocBRPlayerRelationalMutations.Batch(candidate(1),UUID.randomUUID(),badGraph.character(),Map.of(),java.time.Instant.EPOCH),badGraph);throw new AssertionError("incomplete graph accepted");}
  catch(SQLException expected){
   String details=ApocBRPlayerRelationalMutations.writeFailure(expected);
   check(details.contains("SQLState=22000")&&details.contains("stage=live_fields")&&details.contains("graphIssueCount=2"),"rejected live graph includes SQLState, stage and issue count");
   check(details.contains("UNSUPPORTED_ITEM_SUBTYPE")&&details.contains("UNCLASSIFIED_CAPTURE_ISSUE"),"graph diagnostics preserve controlled codes and sanitize unknown text");
   check(!details.contains("private-player-value")&&!details.contains("secret"),"graph diagnostics never expose raw load issue strings");
  }
  cause.initCause(failure);
  check(ApocBRPlayerRelationalMutations.writeFailure(failure).length()==diagnostic.length(),"cyclic causes terminate without duplicated diagnostics");
 }
 static void worker()throws Exception {
  {
   Field tracked=ApocBRPlayerRelationalMutations.class.getDeclaredField("TRACKED");tracked.setAccessible(true);
   Field selected=ApocBRPlayerRelationalMutations.class.getDeclaredField("graphPlayer");selected.setAccessible(true);
   var offline=new zombie.characters.IsoPlayer();var off=candidate(999);UUID offSession=ApocBRPlayerRelationalMutations.register(offline,off);ApocBRPlayerRelationalMutations.bind(off,offSession,UUID.randomUUID());
   List<zombie.characters.IsoPlayer> online=new ArrayList<>();
   for(int i=0;i<200;i++){var p=new zombie.characters.IsoPlayer();online.add(p);var c=candidate(i);UUID session=ApocBRPlayerRelationalMutations.register(p,c);ApocBRPlayerRelationalMutations.bind(c,session,UUID.randomUUID());Object cursor=((Map<?,?>)tracked.get(null)).get(p);Field sample=cursor.getClass().getDeclaredField("nextSample");sample.setAccessible(true);sample.setLong(cursor,Long.MAX_VALUE);}
   Set<Object> served=Collections.newSetFromMap(new IdentityHashMap<>());
   for(int i=0;i<200;i++) {
    ApocBRPlayerRelationalMutations.beginTick();for(var p:online)ApocBRPlayerRelationalMutations.observe(p);
    Object chosen=selected.get(null);check(chosen!=null&&chosen!=offline&&served.add(chosen),"oldest due online player receives a fair turn independently of map order/offline inspection");
    Object cursor=((Map<?,?>)tracked.get(null)).get(chosen);Field due=cursor.getClass().getDeclaredField("nextGraph");due.setAccessible(true);due.setLong(cursor,System.nanoTime()+100_000_000_000L);
   }
   ApocBRPlayerRelationalMutations.beginTick();ApocBRPlayerRelationalMutations.endTick();check(selected.get(null)==null,"tick selection releases its temporary player reference");
   ApocBRPlayerRelationalMutations.shutdown();
  }
  captures(); // Native login also loads the stat registry before the capture pump.
  Fake fake=new Fake();fake.complete=true;fake.dataset=ApocBRPlayerRelationalDatabase.named("sqlite-save:test-save");fake.character=UUID.randomUUID();fake.head=UUID.randomUUID();
  zombie.savefile.ApocBRPostgresConnector.connections=fake::open;
  System.setProperty("apocbr.postgres.enabled","true");System.setProperty("apocbr.postgres.players.enabled","false");
  var player=new zombie.characters.IsoPlayer();
  // PostgreSQL-loaded lives must bind without a SQLite receipt or snapshot copy.
  UUID importedDataset=UUID.randomUUID(),persistedLife=UUID.randomUUID(),persistedItem=UUID.randomUUID();
  var loadedProfile=new ApocBRPlayerRelationalReader.Profile(Map.of("snapshot",List.of(Map.of(
      "persisted_character_id",persistedLife.toString(),"source_dataset_id",importedDataset.toString(),"source_world_version",249)),
      "item",List.of(Map.of("native_id",700,"persisted_item_id",persistedItem.toString()))));
  var postgresPlayer=new zombie.characters.IsoPlayer();postgresPlayer.forbidExportReads=true;
  var loadedItem=new Item(700);postgresPlayer.inventory.items.add(loadedItem);
  System.setProperty("apocbr.postgres.playerRelational.sourceDatasetId",UUID.randomUUID().toString());
  try{ApocBRPlayerRelationalSync.registerPostgresPlayer(postgresPlayer,"world","imported",0,false,loadedProfile);}
  finally{System.clearProperty("apocbr.postgres.playerRelational.sourceDatasetId");}
  Field trackedField=ApocBRPlayerRelationalMutations.class.getDeclaredField("TRACKED");trackedField.setAccessible(true);
  Object cursor=((Map<?,?>)trackedField.get(null)).get(postgresPlayer);
  Field candidateField=cursor.getClass().getDeclaredField("candidate");candidateField.setAccessible(true);
  var loadedCandidate=(ApocBRPlayerRelationalDatabase.Candidate)candidateField.get(cursor);
  Field sessionField=cursor.getClass().getDeclaredField("session");sessionField.setAccessible(true);UUID loadedSession=(UUID)sessionField.get(cursor);
  Field loadedDirtyField=ApocBRPlayerRelationalMutations.class.getDeclaredField("DIRTY");loadedDirtyField.setAccessible(true);
  var loadedQueue=(ApocBRPlayerRelationalMutations.DirtyQueue)loadedDirtyField.get(null);
  check(loadedCandidate.dataset().equals(importedDataset),"loaded life retains actual snapshot dataset despite unrelated local config/save path");
  check(loadedQueue.canSnapshot(loadedCandidate.key(),loadedSession),"PostgreSQL login enables graph reconciliation immediately without an import receipt");
  check(fake.opened==0,"PostgreSQL registration performs no receipt SQL or initial mirror work");
  Field bindingsField=loadedQueue.getClass().getDeclaredField("bindings");bindingsField.setAccessible(true);
  Object binding=((Map<?,?>)bindingsField.get(loadedQueue)).get(loadedCandidate.key());
  Method getAliases=binding.getClass().getDeclaredMethod("aliases");getAliases.setAccessible(true);
  @SuppressWarnings("unchecked") Map<Integer,UUID> loadedAliases=(Map<Integer,UUID>)getAliases.invoke(binding);
  Field itemTrackerField=cursor.getClass().getDeclaredField("items");itemTrackerField.setAccessible(true);
  loadedItem.id=701;postgresPlayer.forbidExportReads=false;
  var identitySnapshot=ApocBRPlayerRelationalSnapshot.copyLive(postgresPlayer,(ApocBRPlayerRelationalSnapshot.ItemTracker)itemTrackerField.get(cursor),persistedLife,loadedAliases);
  var identityGraph=new ApocBRPlayerRelationalCapture(persistedLife,Map.of(),Long.MAX_VALUE).capture(identitySnapshot,List.of());
  check(loadedAliases.get(700).equals(persistedItem)&&itemIdentity(identityGraph,701).equals(persistedItem),"login profile UUID seeds tracking even when native ID changes before first live capture");
  try{ApocBRPlayerRelationalSync.registerPostgresPlayer(new zombie.characters.IsoPlayer(),"world","broken",0,false,new ApocBRPlayerRelationalReader.Profile(Map.of("snapshot",List.of(Map.of("source_world_version",249)))));throw new AssertionError("missing persisted identity accepted");}catch(RuntimeException expected){checks++;}
  ApocBRPlayerRelationalMutations.shutdown();
  ApocBRPlayerRelationalSync.offerLoadedPlayer(player,1,"world","alice",0,249,new byte[]{1},List.of());
  long until=System.nanoTime()+2_000_000_000L;
  Field pending=ApocBRPlayerRelationalSync.class.getDeclaredField("PENDING");pending.setAccessible(true);
  while(!((Map<?,?>)pending.get(null)).isEmpty()&&System.nanoTime()<until)Thread.sleep(10);
  check(((Map<?,?>)pending.get(null)).isEmpty()&&fake.inserts==0,"worker skips capture/write for committed receipt");
  fake.complete=false;
  java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
  zombie.savefile.ApocBRPostgresConnector.connections=()->{
   check(Thread.currentThread().getName().equals("ApocBR-player-relational"),"JDBC runs on exporter worker");
   try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return fake.open();
  };
  player.inventory.items.add(new Item(77));
  ApocBRPlayerRelationalSync.offerLoadedPlayer(player,1,"world","alice",0,249,new byte[]{1},List.of());
  player.inventory.items.clear();player.x=999;player.forbidExportReads=true;
  ApocBRPlayerRelationalSync.offerLoadedPlayer(player,1,"world","alice",0,249,new byte[]{1},List.of());
  check(((Map<?,?>)pending.get(null)).size()==1,"duplicate offer coalesced before reading snapshot");
  for(int i=0;i<15;i++)ApocBRPlayerRelationalSync.offerLoadedPlayer(new zombie.characters.IsoPlayer(),i+2,"world","queued"+i,0,249,new byte[]{1},List.of());
  var overflow=new zombie.characters.IsoPlayer();overflow.forbidExportReads=true;
  ApocBRPlayerRelationalSync.offerLoadedPlayer(overflow,99,"world","overflow",0,249,new byte[]{1},List.of());
  check(((Map<?,?>)pending.get(null)).size()==16,"pending cap defers overflow before copying");
  release.countDown();
  until=System.nanoTime()+3_000_000_000L;
  while(!((Map<?,?>)pending.get(null)).isEmpty()&&System.nanoTime()<until)Thread.sleep(10);
  check(fake.headWrites==16,"worker drains snapshots without a main-thread pump");
  check(fake.itemNames.equals(List.of("fixture")),"worker consumes pre-mutation inventory snapshot");
  Field bytes=ApocBRPlayerRelationalSync.class.getDeclaredField("QUEUED_BYTES");bytes.setAccessible(true);
  check(((java.util.concurrent.atomic.AtomicLong)bytes.get(null)).get()==0,"drained snapshots release queue byte reservations");
  LiveFake live=new LiveFake();live.dataset=ApocBRPlayerRelationalDatabase.named("sqlite-save:test-save");live.reason="live_fields";
  zombie.savefile.ApocBRPostgresConnector.connections=live::open;
  var moving=new zombie.characters.IsoPlayer();
  ApocBRPlayerRelationalSync.offerLoadedPlayer(moving,77,"world","moving",0,249,new byte[]{1},List.of());
  moving.x=123;ApocBRPlayerRelationalMutations.finalSample(moving);moving.x=999;
  until=System.nanoTime()+3_000_000_000L;
  while(live.commits<2&&System.nanoTime()<until)Thread.sleep(10);
  check(live.updates.stream().anyMatch(v->v.containsValue(123f))&&live.updates.stream().noneMatch(v->v.containsValue(999f)),"shared worker persists detached scalar changes after initial receipt binding");
  live.graphRows=liveGraph(moving,live.character).rows();var newItem=new Item(888);newItem.name="before-worker";moving.inventory.items.add(newItem);
  ApocBRPlayerRelationalMutations.finalSample(moving);newItem.name="after-worker";moving.forbidExportReads=true;
  Field dirtyField=ApocBRPlayerRelationalMutations.class.getDeclaredField("DIRTY");dirtyField.setAccessible(true);var dirty=(ApocBRPlayerRelationalMutations.DirtyQueue)dirtyField.get(null);
  until=System.nanoTime()+4_000_000_000L;while(dirty.size()>0&&System.nanoTime()<until)Thread.sleep(10);
  check(dirty.size()==0&&live.graphReads>0,"shared worker projects and commits full detached graph patches");
  check(live.bound.entrySet().stream().filter(e->e.getKey().startsWith("INSERT INTO player_rel.item (")).flatMap(e->e.getValue().stream()).anyMatch(v->v.containsValue("before-worker")),"worker persists captured inventory state");
  check(live.bound.values().stream().flatMap(List::stream).noneMatch(v->v.containsValue("after-worker")),"graph worker never reads mutable item after enqueue");
  check(dirty.bytes()==0,"worker graph acknowledgement releases detached snapshot bytes");
  ApocBRPlayerRelationalSync.shutdown();check(fake.opened==fake.closed,"shutdown returns worker lease");
  check(live.opened==live.closed,"live worker returns its lease at shutdown");
 }

 public static void main(String[] args)throws Exception {if(args.length>0&&args[0].startsWith("size-")){sizeLimits(args[0]);System.out.println("Size checks="+checks);return;}if(args.length>0){worker();System.out.println("Worker checks="+checks);return;}writeDiagnostics();accessors();captures();snapshots();payloadOrigins();componentCodecs();remainingComponentCodecs();embeddedAnimalCodec();sharedLuaState();mutationQueue();mutationDatabase();extendedMutations();graphMutations();orphanEquipment();duplicateNativeItems();graphQueue();liveVehicle();characterLives();playerItemLifecycles();aliasGenerations();sqlMetrics();largeImport();database();System.out.println("Relational checks="+checks);}
}
'''
with tempfile.TemporaryDirectory(prefix='apocbr_rel_') as folder:
 target=Path(folder);harness=target/'RelationalTest.java';native_harness=HARNESS[:HARNESS.index(' static void worker()')]+HARNESS[HARNESS.index(' public static void main'):];native_harness=native_harness.replace('if(args.length>0){worker();System.out.println(\"Worker checks=\"+checks);return;}','');harness.write_text(native_harness,encoding='utf-8')
 boundary=inactive_persistence_stubs(target)
 sources=[SRC/(name+'.java') for name in ['ApocBRPayloadProvenance','ApocBRPlayerRelationalLimits','ApocBRPlayerRelationalSchema','ApocBRPlayerRelationalAccess','ApocBRPlayerRelationalSnapshot','ApocBRPlayerRelationalCodecs','ApocBRPlayerRelationalCapture','ApocBRPlayerRelationalMutations','ApocBRPlayerRelationalDelta','ApocBRPlayerRelationalMetrics','ApocBRPlayerRelationalDatabase','ApocBRPlayerLoadQuality']]
 subprocess.run([str(JDK/'javac.exe'),'--release','25','-cp',str(GAME),'-d',folder,*map(str,boundary),*map(str,sources),str(harness)],check=True)
 subprocess.run([str(JDK/'java.exe'),'-cp',folder+os.pathsep+str(GAME),'zombie.savefile.RelationalTest'],check=True)
 for mode in ['size-bytes','size-rows']:
  options=['-Dapocbr.postgres.playerRelational.'+setting+'=1' for setting in ['maxGraphMiB','maxSnapshotMiB','maxSourceMiB','queuedMiB']]
  if mode=='size-rows':options.append('-Dapocbr.postgres.playerRelational.maxRows=50')
  subprocess.run([str(JDK/'java.exe'),*options,'-cp',folder+os.pathsep+str(GAME),'zombie.savefile.RelationalTest',mode],check=True)

 # Recompile a separate worker fixture against small engine boundary stubs.
 stubs={
 'zombie/savefile/ApocBRPlayerRelationalReader.java':'package zombie.savefile; final class ApocBRPlayerRelationalReader {record Profile(java.util.Map<String,java.util.List<java.util.Map<String,Object>>> tables){java.util.Map<String,Object> one(String name){return tables.get(name).getFirst();}java.util.List<java.util.Map<String,Object>> rows(String name){return tables.getOrDefault(name,java.util.List.of());}}}',
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
