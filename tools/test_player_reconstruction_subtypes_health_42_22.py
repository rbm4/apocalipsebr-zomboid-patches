"""42.22 subtype/cache and health/time baselines. Complete target reconstruction
with native weapon, device, map, UI, nutrition/body-part methods. Sprite assets,
corpse decoding, animal world setup and post-load ticks have explicit boundaries.
"""
from pathlib import Path
import sys

sys.dont_write_bytecode = True
from test_player_packet_algorithms import extract_method
from test_player_reconstruction_lifecycle_42_22 import main as run_fixture

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "42.22.0/decompiled/zombie"

JAVA = r'''
 static void near(double a,double b,String why){check(Math.abs(a-b)<0.00001,why+": "+a+" / "+b);}
 static class SpriteProbe extends zombie.inventory.types.Moveable {
  SpriteProbe(){super("Mod","Fixture","Fixture","");}int reads;boolean custom;
  public boolean ReadFromWorldSprite(String sprite){reads++;worldSprite=sprite;customItem=custom?"Mod.Custom":null;weight=actualWeight=13;return true;}
 }
 static void sprites()throws Exception{
  for(boolean custom:List.of(false,true)){
   var p=(SpriteProbe)blank(SpriteProbe.class);p.custom=custom;
   ApocBRPlayerRelationalReconstruction.set(p,"module","Mod");ApocBRPlayerRelationalReconstruction.set(p,"type","Custom");ApocBRPlayerRelationalReconstruction.set(p,"fullType","Mod.Custom");
   var row=Map.<String,Object>of("full_type","Mod.Custom","runtime_class",SpriteProbe.class.getName());
   check(ApocBRPlayerRelationalReconstruction.createSavedItem(row,Map.of("world_sprite","tile_1"),s->p)==p,"ordinary subtype factory retained");
   check(p.reads==1,"ordinary Moveable initializes saved sprite");
   check(p.getFullType().equals(custom?"Mod.Custom":"Mod.tile_1"),"native CustomItem/type derivation");
   near(p.getWeight(),13,"sprite initialization precedes saved scalar overrides");
  }
  var radio=(zombie.inventory.types.Radio)blank(zombie.inventory.types.Radio.class);
  ApocBRPlayerRelationalReconstruction.set(radio,"module","Mod");ApocBRPlayerRelationalReconstruction.set(radio,"type","Radio");
  var row=Map.<String,Object>of("full_type","Mod.Radio","runtime_class",zombie.inventory.types.Radio.class.getName());
  ApocBRPlayerRelationalReconstruction.createSavedItem(row,Map.of("world_sprite","radio_tile"),s->radio);
  check(radio.getFullType().equals("Mod.radio_tile"),"Radio participates in native saved-sprite type fallback");
 }
 static void weapons()throws Exception{
  var w=(HandWeapon)blank(HandWeapon.class);var part=(zombie.inventory.types.WeaponPart)blank(zombie.inventory.types.WeaponPart.class);
  ApocBRPlayerRelationalReconstruction.set(w,"attachments",new HashMap<>(Map.of("Scope",part)));
  ApocBRPlayerRelationalReconstruction.set(w,"attachmentList",new ArrayList<>(List.of(part)));
  ApocBRPlayerRelationalReconstruction.set(w,"activeLight",part);ApocBRPlayerRelationalReconstruction.set(w,"activeSight",part);
  w.clearAllWeaponParts();check(w.getAllWeaponParts().isEmpty()&&w.getActiveLight()==null&&w.getActiveSight()==null,"native factory part/cache clearing");
  var script=(zombie.scripting.objects.Item)blank(zombie.scripting.objects.Item.class);script.displayCategory="Explosives";
  ApocBRPlayerRelationalReconstruction.set(w,"scriptItem",script);w.setWeaponCategories(Set.of());w.setContainsClip(true);
  ApocBRPlayerRelationalReconstruction.restoreSubtypeBaseline(w,100,true);
  check(!w.isContainsClip(),"no-magazine target cannot retain clip");check(Boolean.TRUE.equals(get(w,"isExplosive"))&&!Boolean.TRUE.equals(get(w,"isMelee")),"weapon classification rebuilt from current definition");
  ApocBRPlayerRelationalReconstruction.set(w,"magazineType","Base.Magazine");w.setContainsClip(true);w.setWeaponCategories(Set.of(zombie.scripting.objects.WeaponCategory.AXE));
  ApocBRPlayerRelationalReconstruction.restoreSubtypeBaseline(w,100,true);check(w.isContainsClip()&&Boolean.TRUE.equals(get(w,"isMelee")),"valid magazine and melee category retained");
 }
 static void radioAndMap()throws Exception{
  var radio=(zombie.inventory.types.Radio)blank(zombie.inventory.types.Radio.class);
  var device=ApocBRPlayerRelationalReconstruction.restoreRadioDevice(radio);check(device!=null&&get(device,"parent")==radio,"missing device allocated and parent bound");
  ApocBRPlayerRelationalReconstruction.set(device,"powerDelta",0.25f);ApocBRPlayerRelationalReconstruction.set(device,"parent",null);
  check(ApocBRPlayerRelationalReconstruction.restoreRadioDevice(radio)==device,"existing saved device reused");check(get(device,"parent")==radio,"stale device parent repaired");near(((Number)get(device,"powerDelta")).floatValue(),0.25,"saved power survives parent repair");
  var symbol=new zombie.worldMap.symbols.WorldMapTextureSymbol();
  for(float rotation:new float[]{90,180,450,-90}){
   ApocBRPlayerRelationalReconstruction.project("map_symbol",symbol,Map.of("rotation",rotation,"min_zoom",-4f,"max_zoom",50f),"");
   ApocBRPlayerRelationalReconstruction.restoreMapSymbolBaseline(symbol);double angle=((Number)get(symbol,"rotation")).doubleValue();
   near(((Number)get(symbol,"cosA")).doubleValue(),Math.cos(Math.toRadians(angle)),"rotation cosine cache");near(((Number)get(symbol,"sinA")).doubleValue(),Math.sin(Math.toRadians(angle)),"rotation sine cache");
   near(((Number)get(symbol,"minZoom")).floatValue(),0,"native minimum zoom clamp");near(((Number)get(symbol,"maxZoom")).floatValue(),24,"native maximum zoom clamp");
  }
 }
 static void scriptAndSkin()throws Exception{
  var manager=zombie.scripting.ScriptManager.instance;
  var items=(Map<String,Object>)get(manager,"items.fullTypeToScriptMap");var entities=(Map<String,Object>)get(manager,"entities.fullTypeToScriptMap");
  String itemName="ReconstructionFixture.Item",entityName="ReconstructionFixture.Entity";
  Object oldItem=items.get(itemName),oldEntity=entities.get(entityName);
  var item=(zombie.scripting.objects.Item)blank(zombie.scripting.objects.Item.class);var entity=(zombie.scripting.entity.GameEntityScript)blank(zombie.scripting.entity.GameEntityScript.class);
  items.put(itemName,item);entities.put(entityName,entity);
  try{
   var c=ComponentType.Script.CreateComponent();
   ApocBRPlayerRelationalReconstruction.set(c,"originalIsItem",true);ApocBRPlayerRelationalReconstruction.set(c,"originalScript",itemName);
   ApocBRPlayerRelationalReconstruction.restoreEntityScriptReference(c);check(get(c,"script")==item,"item script reference resolved from saved name");
   ApocBRPlayerRelationalReconstruction.set(c,"originalIsItem",false);ApocBRPlayerRelationalReconstruction.set(c,"originalScript",entityName);
   ApocBRPlayerRelationalReconstruction.restoreEntityScriptReference(c);check(get(c,"script")==entity,"entity script reference replaces factory pointer");
   ApocBRPlayerRelationalReconstruction.set(c,"originalScript",null);ApocBRPlayerRelationalReconstruction.restoreEntityScriptReference(c);check(get(c,"script")==null,"absent script clears stale reference");
  }finally{if(oldItem==null)items.remove(itemName);else items.put(itemName,oldItem);if(oldEntity==null)entities.remove(entityName);else entities.put(entityName,oldEntity);}
  var f=zombie.scripting.ui.XuiManager.class.getDeclaredField("xuiSkins");f.setAccessible(true);var skins=(Map<String,Object>)f.get(null);
  String name="reconstruction-fixture-skin";Object oldSkin=skins.get(name);var skin=new zombie.scripting.ui.XuiSkin(name,null);skins.put(name,skin);
  try{
   var c=ComponentType.UiConfig.CreateComponent();var method=ApocBRPlayerRelationalReconstruction.class.getDeclaredMethod("restoreComponentState",Object.class,String.class,Object.class);method.setAccessible(true);
   method.invoke(null,c,"UiConfig",Map.of("xuiSkinName",name,"entityStyleName","fixture","uiEnabled",false));
   check(get(c,"skin")==skin,"saved skin name rebuilds resolved asset pointer");check(Boolean.FALSE.equals(get(c,"uiEnabled")),"UI saved enable state retained");
  }finally{if(oldSkin==null)skins.remove(name);else skins.put(name,oldSkin);}
 }
 static void clocksAndNutrition()throws Exception{
  var clothing=(zombie.inventory.types.Clothing)blank(zombie.inventory.types.Clothing.class);
  ApocBRPlayerRelationalReconstruction.set(clothing,"lastWetnessUpdate",3f);ApocBRPlayerRelationalReconstruction.set(clothing,"wetness",67f);
  ApocBRPlayerRelationalReconstruction.restoreSubtypeBaseline(clothing,200.5f,true);
  near(((Number)get(clothing,"lastWetnessUpdate")).floatValue(),200.5,"native wetness load baseline reset");near(clothing.getWetness(),67,"reset does not dry saved clothing");
  near(200.5-((Number)get(clothing,"lastWetnessUpdate")).doubleValue(),0,"first update has no offline wetness interval");
  var food=(zombie.inventory.types.Food)blank(zombie.inventory.types.Food.class);ApocBRPlayerRelationalReconstruction.set(food,"fullType","Base.FixtureFood");ApocBRPlayerRelationalReconstruction.set(food,"lastAged",-1f);
  ApocBRPlayerRelationalReconstruction.restoreSubtypeBaseline(food,200.5f,false);near(((Number)get(food,"lastAged")).floatValue(),-1,"client side does not initialize server aging sentinel");
  ApocBRPlayerRelationalReconstruction.restoreSubtypeBaseline(food,200.5f,true);near(((Number)get(food,"lastAged")).floatValue(),200.5,"server initializes aging sentinel");
  ApocBRPlayerRelationalReconstruction.set(food,"lastAged",100f);ApocBRPlayerRelationalReconstruction.restoreSubtypeBaseline(food,200.5f,true);near(((Number)get(food,"lastAged")).floatValue(),100,"normal food aging timestamp retained");
  var nutrition=(zombie.characters.BodyDamage.Nutrition)blank(zombie.characters.BodyDamage.Nutrition.class);
  ApocBRPlayerRelationalReconstruction.restoreNutrition(nutrition,Map.of("calories",9000f,"proteins",-900f,"lipids",2000f,"carbohydrates",500f,"weight",10f));
  near(nutrition.getCalories(),3700,"native calorie upper clamp");near(nutrition.getProteins(),-500,"native protein lower clamp");near(nutrition.getLipids(),1000,"native lipids upper clamp");near(nutrition.getCarbohydrates(),500,"valid carbs preserved");near(nutrition.getWeight(),35,"low weight clamped without dereferencing damage/event parent");
 }
 static class HealthPlayer extends IsoPlayer {
  HealthPlayer(){super((zombie.iso.IsoCell)null);}zombie.characters.BodyDamage.BodyDamage damage;
  public zombie.characters.BodyDamage.BodyDamage getBodyDamage(){return damage;}
 }
 static void bodyBaseline()throws Exception{
  var p=(HealthPlayer)blank(HealthPlayer.class);p.damage=(zombie.characters.BodyDamage.BodyDamage)blank(zombie.characters.BodyDamage.BodyDamage.class);
  var type=zombie.characters.BodyDamage.BodyPartType.Hand_L;var part=new zombie.characters.BodyDamage.BodyPart(type,null);var last=new zombie.characters.BodyDamage.BodyPartLast();
  ApocBRPlayerRelationalReconstruction.set(p.damage,"bodyParts",new ArrayList<>(List.of(part)));ApocBRPlayerRelationalReconstruction.set(p.damage,"bodyPartsLastState",new ArrayList<>(List.of(last)));
  var row=Map.<String,Object>of("character_id",1,"body_part_key",type.name(),"health",42f,"cut",true,"bitten",true,"bandaged",true,"bandage_life",0f);
  var profile=new ApocBRPlayerRelationalReader.Profile(Map.of("body_part",List.of(row)));
  var ctor=ApocBRPlayerRelationalReconstruction.class.getDeclaredConstructors()[0];ctor.setAccessible(true);var r=ctor.newInstance(profile,null);invoke(r,"health",p);
  check(last.bandaged()==part.bandaged()&&last.bitten()==part.bitten()&&last.isCut()==part.isCut(),"previous injury flags reflect restored state");check(last.isBandageDirty(),"previous dirty bandage state copied");near(part.getHealth(),42,"saved injury health retained");
  ApocBRPlayerRelationalReconstruction.set(part,"bandaged",false);check(last.bandaged()&&!part.bandaged(),"first subsequent bandage change remains detectable");
 }
 static class AnimalDataProbe {
  int age,preferredHutchPosition,milkEvents;float size,milkQty=10,maxMilkActual=10;boolean canHaveMilk=true;long lastMilkTimer=30;
  public float getMinSize(){return 0.5f;}public float getMaxSize(){return 2;}public float getMaxMilk(){return 100;}
  public void updateLastTimeMilked(){milkEvents++;lastMilkTimer=999;}
  // Exact native setters inserted below.
  ANIMAL_SETTERS
 }
 static class Definition {boolean canBeDomesticated=true;}
 static class WildProbe {Definition adef=new Definition();boolean wild=true;String migrationGroup="old";double virtualId=12;float stressLevel=42;}
 static void animalBaselines(){
  for(float milk:new float[]{-1,3,100}){
   var d=new AnimalDataProbe();var state=Map.<String,Object>of("age",100,"size",5f,"milkQuantity",milk,"maxMilkActual",7f,"preferredHutchPosition",2,"lastMilkTimer",123L,"canHaveMilk",true);
   ApocBRPlayerRelationalReconstruction.restoreAnimalData(d,state);near(d.size,2,"native size clamp retained");near(d.milkQty,Math.min(Math.max(milk,0),7),"native milk quantity clamp");
   check(d.lastMilkTimer==123&&d.milkEvents==0&&d.maxMilkActual==7,"restoration does not replay milking timers or capacity growth");check(d.age==100&&d.preferredHutchPosition==2,"age/hutch native setters");
  }
  var w=new WildProbe();ApocBRPlayerRelationalReconstruction.restoreAnimalWildState(w,false);check(!w.wild&&w.migrationGroup==null&&w.virtualId==0,"domestication clears migration metadata");near(w.stressLevel,42,"restore does not randomize saved stress");
  ApocBRPlayerRelationalReconstruction.restoreAnimalWildState(w,true);check(w.wild,"wild restored");near(w.stressLevel,42,"wild true does not replay stress RNG");
 }
 static ApocBRPlayerRelationalCapture.Graph scalarGraph(String table,Object source,String key)throws Exception{
  var c=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE);invoke(c,"project",table,source,new Object[]{key,itemIdentity});
  return new ApocBRPlayerRelationalCapture.Graph(UUID.randomUUID(),character,java.time.Instant.now(),(List<ApocBRPlayerRelationalCapture.Row>)get(c,"rows"),List.of(),0,0,0,0,0);
 }
 static Map<String,Object> animalDataRoundTrip(Store store,Map<String,Object> data)throws Exception{
  var c=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE);invoke(c,"componentValue",itemIdentity,"item.embeddedAnimal",data,"complete");
  store.save(new ApocBRPlayerRelationalCapture.Graph(UUID.randomUUID(),character,java.time.Instant.now(),(List<ApocBRPlayerRelationalCapture.Row>)get(c,"rows"),List.of(),0,0,0,0,0));
  var rows=store.profile().rows("value_node");Map<Integer,List<Map<String,Object>>> children=new HashMap<>();Map<String,Object> root=null;
  for(var row:rows)if(row.get("parent_id")==null)root=row;else children.computeIfAbsent(((Number)row.get("parent_id")).intValue(),k->new ArrayList<>()).add(row);
  var decode=ApocBRPlayerRelationalReconstruction.class.getDeclaredMethod("value",Map.class,Map.class,Set.class,int.class,Map.class);decode.setAccessible(true);
  return (Map<String,Object>)decode.invoke(null,root,children,new HashSet<>(),0,Map.of());
 }
 static void baselineMutations()throws Exception{
  var food=(zombie.inventory.types.Food)blank(zombie.inventory.types.Food.class);ApocBRPlayerRelationalReconstruction.set(food,"fullType","Base.FixtureFood");ApocBRPlayerRelationalReconstruction.set(food,"lastAged",10f);
  var store=new Store();store.save(scalarGraph("food_state",food,"item_id"));ApocBRPlayerRelationalReconstruction.set(food,"lastAged",-1f);store.save(scalarGraph("food_state",food,"item_id"));
  var loaded=(zombie.inventory.types.Food)blank(zombie.inventory.types.Food.class);ApocBRPlayerRelationalReconstruction.set(loaded,"fullType","Base.FixtureFood");
  ApocBRPlayerRelationalReconstruction.project("food_state",loaded,store.profile().rows("food_state").getFirst(),"item_id");near(((Number)get(loaded,"lastAged")).floatValue(),-1,"food sentinel survives mutation SQL");
  ApocBRPlayerRelationalReconstruction.restoreSubtypeBaseline(loaded,200,true);near(((Number)get(loaded,"lastAged")).floatValue(),200,"SQL sentinel normalized at load time");
  var clothing=(zombie.inventory.types.Clothing)blank(zombie.inventory.types.Clothing.class);ApocBRPlayerRelationalReconstruction.set(clothing,"lastWetnessUpdate",3f);ApocBRPlayerRelationalReconstruction.set(clothing,"wetness",67f);
  store=new Store();store.save(scalarGraph("clothing_state",clothing,"item_id"));var out=(zombie.inventory.types.Clothing)blank(zombie.inventory.types.Clothing.class);
  ApocBRPlayerRelationalReconstruction.project("clothing_state",out,store.profile().rows("clothing_state").getFirst(),"item_id");ApocBRPlayerRelationalReconstruction.restoreSubtypeBaseline(out,200,true);
  near(((Number)get(out,"lastWetnessUpdate")).floatValue(),200,"clothing SQL timestamp normalized to native load baseline");near(out.getWetness(),67,"wetness survives SQL while clock resets");
  var nutrition=(zombie.characters.BodyDamage.Nutrition)blank(zombie.characters.BodyDamage.Nutrition.class);ApocBRPlayerRelationalReconstruction.set(nutrition,"weight",10d);ApocBRPlayerRelationalReconstruction.set(nutrition,"calories",9000f);
  store=new Store();store.save(scalarGraph("character_nutrition",nutrition,"character_id"));var restored=(zombie.characters.BodyDamage.Nutrition)blank(zombie.characters.BodyDamage.Nutrition.class);
  ApocBRPlayerRelationalReconstruction.restoreNutrition(restored,store.profile().rows("character_nutrition").getFirst());near(restored.getWeight(),35,"SQL body weight normalized without damage parent");near(restored.getCalories(),3700,"SQL calories normalize through native setter");
  store=new Store();for(float milk:new float[]{5,2}){
   var data=animalDataRoundTrip(store,Map.of("age",100,"size",1f,"milkQuantity",milk,"maxMilkActual",7f,"preferredHutchPosition",2,"lastMilkTimer",123L,"canHaveMilk",true));
   var target=new AnimalDataProbe();ApocBRPlayerRelationalReconstruction.restoreAnimalData(target,data);near(target.milkQty,milk,"animal milk mutation SQL round trip");check(target.milkEvents==0&&target.lastMilkTimer==123,"animal SQL reload does not replay milking effects");
  }
 }
'''


if __name__ == "__main__":
    source = (ROOT / "42.22.0/src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java").read_text()
    inventory = extract_method(source, "private void inventory(IsoPlayer player)")
    assert inventory.index("clearAllWeaponParts") < inventory.index('project("item"') < inventory.index('attachWeaponPart')
    visuals = extract_method(source, "private void finalizeItemVisuals()")
    assert visuals.index("restoreSubtypeBaseline") < visuals.index("synchWithVisual")
    player = extract_method(source, "private IsoPlayer player(int slot)")
    assert player.index("components();") < player.index("finalizeItemVisuals();")
    subtype = extract_method(source, "static void restoreSubtypeBaseline(")
    assert 'CORPSE_ANIMAL' in subtype and '"initAnimalIcons"' in subtype
    animal = extract_method(source, "private static void restoreAnimal(Object owner,Object state)")
    assert "actor.setX(actor.setLastX(actor.setNextX" in animal
    assert animal.index("restoreAnimalData") < animal.index('"initAnimalData"')
    native = (NATIVE / "characters/animals/datas/AnimalData.java").read_text()
    setters = "\n".join(extract_method(native, sig) for sig in (
        "public void setAge(int age)", "public void setSize(float size)",
        "public void setMilkQuantity(float milkQty)", "public void setPreferredHutchPosition(int preferredHutchPosition)"))
    setters = setters.replace("PZMath.", "zombie.core.math.PZMath.").replace("Rand.", "zombie.core.random.Rand.")
    run_fixture(JAVA.replace("ANIMAL_SETTERS", setters), "sprites();weapons();radioAndMap();scriptAndSkin();clocksAndNutrition();bodyBaseline();animalBaselines();baselineMutations();", "subtype/health/lifecycle")
