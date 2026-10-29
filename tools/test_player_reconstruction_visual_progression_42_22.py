"""Compile full target reconstruction and run native clothing/XP/descriptor
fixtures plus lifecycle regression. Asset selection/rendering, JDBC transport
and world login are boundaries; no deployment/live database.
"""
from pathlib import Path
import sys

sys.dont_write_bytecode = True
from test_player_packet_algorithms import extract_method
from test_player_reconstruction_lifecycle_42_22 import main as run_fixture

ROOT = Path(__file__).resolve().parents[1]


JAVA = r'''
 static void near(float a,float b,String why){check(Math.abs(a-b)<0.00001f,why+": "+a+" / "+b);}
 static class VisualClothing extends zombie.inventory.types.Clothing {
  VisualClothing(){super("Base","Color","Color","","","");}
  zombie.core.skinnedmodel.visual.ItemVisual savedVisual;
  zombie.core.skinnedmodel.population.ClothingItem asset;
  ArrayList<zombie.characterTextures.BloodClothingType> bloodTypes;int synchronizations;
  public zombie.core.skinnedmodel.visual.ItemVisual getVisual(){return savedVisual;}
  public zombie.core.skinnedmodel.population.ClothingItem getClothingItem(){return asset;}
  public ArrayList<zombie.characterTextures.BloodClothingType> getBloodClothingType(){return bloodTypes;}
  public ArrayList<String> getIconsForTexture(){return null;}
  public void synchWithVisual(){synchronizations++;super.synchWithVisual();}
 }
 static VisualClothing clothing(boolean custom,boolean random)throws Exception{
  var item=(VisualClothing)blank(VisualClothing.class);
  item.savedVisual=new zombie.core.skinnedmodel.visual.ItemVisual();
  item.savedVisual.setTint(new zombie.core.ImmutableColor(0.1f,0.2f,0.3f));
  item.asset=(zombie.core.skinnedmodel.population.ClothingItem)blank(zombie.core.skinnedmodel.population.ClothingItem.class);
  item.asset.baseTextures=new ArrayList<>();item.asset.allowRandomTint=random;
  item.setCustomColor(custom);item.setColor(new zombie.core.Color(0.4f,0.5f,0.6f,0.7f));
  ApocBRPlayerRelationalReconstruction.set(item,"modelIndex",0);return item;
 }
 static ApocBRPlayerRelationalCapture.Graph rgbGraph(InventoryItem item)throws Exception{
  var capture=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE);
  var rgb=ApocBRPlayerRelationalCapture.savedItemRgb(item);
  if(rgb!=null)invoke(capture,"componentValue",itemIdentity,"item.rgb",rgb,"complete");
  return new ApocBRPlayerRelationalCapture.Graph(UUID.randomUUID(),character,java.time.Instant.now(),
    (List<ApocBRPlayerRelationalCapture.Row>)get(capture,"rows"),List.of(),0,0,0,0,0);
 }
 static void restoreRgbStore(Store store,VisualClothing item)throws Exception{
  var profile=store.profile();int id=((Number)profile.rows("component").getFirst().get("owner_object_id")).intValue();
  var tables=new HashMap<>(profile.tables());tables.put("item",List.of(Map.of("id",id)));
  var ctor=ApocBRPlayerRelationalReconstruction.class.getDeclaredConstructors()[0];ctor.setAccessible(true);var r=ctor.newInstance(new ApocBRPlayerRelationalReader.Profile(tables),null);
  var f=ApocBRPlayerRelationalReconstruction.class.getDeclaredField("objects");f.setAccessible(true);((Map<Integer,Object>)f.get(r)).put(id,item);
  ApocBRPlayerRelationalReconstruction.restoreItemRgb(item,null);invoke(r,"components");invoke(r,"finalizeItemVisuals");
 }
 static void visuals()throws Exception{
  for(boolean random:List.of(false,true)){
   var original=clothing(true,random);original.setColorRed(0.8f);original.setColorGreen(0.3f);original.setColorBlue(0.1f);
   var store=new Store();store.save(rgbGraph(original));original.setColorGreen(0.9f);store.save(rgbGraph(original));
   var out=clothing(true,random);restoreRgbStore(store,out);
   near(out.getColorRed(),0.8f,"raw RGB survives SQL");near(out.getColorGreen(),0.9f,"RGB-only delta survives SQL");
   near(out.getColor().r,0.8f,"custom RGB drives native visual synchronization");near(out.getColor().g,0.9f,"saved col does not overwrite independent RGB");check(out.synchronizations==1,"one final visual pass");
   near(out.savedVisual.getTint().r,0.1f,"independent saved tint retained");
  }
  var white=clothing(true,false);white.setColorRed(1);white.setColorGreen(1);white.setColorBlue(1);
  check(ApocBRPlayerRelationalCapture.savedItemRgb(white)!=null,"explicit white/custom RGB retained despite different col");
  var legacy=clothing(true,false);ApocBRPlayerRelationalReconstruction.restoreItemRgb(legacy,null);legacy.synchWithVisual();near(legacy.getColor().r,0.4f,"old custom col fallback avoids whitening");
  var random=clothing(false,true);ApocBRPlayerRelationalReconstruction.restoreItemRgb(random,null);random.synchWithVisual();near(random.getColor().r,0.1f,"random clothing uses saved visual tint");
  check(ApocBRPlayerRelationalCapture.savedItemRgb(random)==null,"ordinary white RGB need not add rows");
  var dirty=clothing(true,false);dirty.bloodTypes=new ArrayList<>(List.of(zombie.characterTextures.BloodClothingType.UpperBody));
  dirty.savedVisual.setBlood(zombie.characterTextures.BloodBodyPartType.Torso_Upper,0.5f);dirty.savedVisual.setDirt(zombie.characterTextures.BloodBodyPartType.Torso_Upper,0.25f);
  ApocBRPlayerRelationalReconstruction.restoreItemRgb(dirty,Map.of("r",1,"g",1,"b",1));dirty.synchWithVisual();
  near(((Number)get(dirty,"bloodLevel")).floatValue(),127/255f*100,"blood aggregate rebuilt from visual bytes");near(((Number)get(dirty,"dirtyness")).floatValue(),63/255f*100,"dirt aggregate rebuilt from visual bytes");
  for(Object bad:List.of(Map.of("r",Float.NaN,"g",1,"b",1),Map.of("r",-1,"g",1,"b",1),Map.of("r",1),"bad"))
   try{ApocBRPlayerRelationalReconstruction.restoreItemRgb(dirty,bad);throw new AssertionError("bad RGB accepted");}catch(IllegalStateException expected){checks++;}
  // Exact native item fields are copied into the optional detached node; mutations
  // after capture and Java serialization cannot change the saved RGB.
  var item=(InventoryItem)blank(InventoryItem.class);ApocBRPlayerRelationalReconstruction.set(item,"recordedMediaIndex",(short)-1);
  ApocBRPlayerRelationalReconstruction.set(item,"fullType","Base.Color");item.setColor(new zombie.core.Color(0.4f,0.5f,0.6f,1));item.setCustomColor(true);
  item.setColorRed(0.2f);item.setColorGreen(0.3f);item.setColorBlue(0.4f);
  var copyCtor=ApocBRPlayerRelationalSnapshot.class.getDeclaredConstructor();copyCtor.setAccessible(true);
  var node=(ApocBRPlayerRelationalSnapshot.Node)invoke(copyCtor.newInstance(),"item",item,0);item.setColorRed(0.9f);
  var bytes=new java.io.ByteArrayOutputStream();try(var output=new java.io.ObjectOutputStream(bytes)){output.writeObject(node);}
  try(var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))){node=(ApocBRPlayerRelationalSnapshot.Node)input.readObject();}
  near(((Number)ApocBRPlayerRelationalCapture.savedItemRgb(node).get("r")).floatValue(),0.2f,"RGB owner-thread snapshot survives journal encoding");
  node.itemRgb=null;check(ApocBRPlayerRelationalCapture.savedItemRgb(node)==null,"old journal preserves absence rather than inventing RGB");
 }
 static class BaselinePlayer extends IsoPlayer {
  BaselinePlayer(){super((zombie.iso.IsoCell)null);}IsoGameCharacter.XP savedXp;SurvivorDesc savedDesc;NetworkPlayerAI ai;
  public IsoGameCharacter.XP getXp(){return savedXp;}public SurvivorDesc getDescriptor(){return savedDesc;}
  public NetworkPlayerAI getNetworkCharacterAI(){return ai;}public int getXpForLevel(int level){return level*100;}
 }
 static BaselinePlayer baseline()throws Exception{
  var p=(BaselinePlayer)blank(BaselinePlayer.class);p.savedXp=p.new XP(p);p.savedDesc=(SurvivorDesc)blank(SurvivorDesc.class);
  ApocBRPlayerRelationalReconstruction.set(p.savedDesc,"xpBoostMap",new HashMap<>());
  p.ai=(NetworkPlayerAI)blank(NetworkPlayerAI.class);ApocBRPlayerRelationalReconstruction.set(p.ai,"character",p);
  ApocBRPlayerRelationalReconstruction.set(p.ai,"xpChecker",new NetworkCharacterAI.XpChecker());return p;
 }
 static void progression()throws Exception{
  for(int level=0;level<4;level++)for(float value:new float[]{0,50,(level+1)*100,(level+1)*100+1}){
   var p=baseline();p.savedXp.level=level;p.savedXp.totalXp=value;var reference=baseline();reference.savedXp.level=level;reference.savedXp.totalXp=value;
   nativeNormalize(reference);ApocBRPlayerRelationalReconstruction.restoreProgressionBaseline(p);
   near(p.savedXp.totalXp,reference.savedXp.totalXp,"native XP threshold normalization");check(p.savedXp.level==level,"character level preserved");
  }
  var p=baseline();var strength=zombie.characters.skills.PerkFactory.Perks.Strength;var fitness=zombie.characters.skills.PerkFactory.Perks.Fitness;
  p.savedXp.xpMap.put(strength,123f);p.savedDesc.getXPBoostMap().put(strength,2);
  var multiplier=new IsoGameCharacter.XPMultiplier();multiplier.multiplier=1.5f;p.savedXp.xpMapMultiplier.put(strength,multiplier);
  for(String field:List.of("xpMap","xpBoostMap","xpMapMultiplier"))((Map)get(p.ai,"xpChecker."+field)).put(fitness,99);
  int count=SurvivorDesc.getIDCount();ApocBRPlayerRelationalReconstruction.restoreProgressionBaseline(p);
  check(((Map)get(p.ai,"xpChecker.xpMap")).equals(Map.of(strength,123f)),"checker XP matches loaded progression only");
  check(((Map)get(p.ai,"xpChecker.xpBoostMap")).equals(Map.of(strength,2)),"checker descriptor boosts match saved state");
  check(((Map)get(p.ai,"xpChecker.xpMapMultiplier")).equals(Map.of(strength,1.5f)),"checker multipliers match saved state");
  check(SurvivorDesc.getIDCount()==count,"local baseline does not publish descriptor allocation");
  p.savedXp.xpMap.clear();p.savedXp.xpMapMultiplier.clear();p.savedDesc.getXPBoostMap().clear();ApocBRPlayerRelationalReconstruction.restoreProgressionBaseline(p);
  for(String field:List.of("xpMap","xpBoostMap","xpMapMultiplier"))check(((Map)get(p.ai,"xpChecker."+field)).isEmpty(),"repeat baseline removes stale checker entries");
 }
 static void identity()throws Exception{
  var world=zombie.iso.IsoWorld.instance;var original=world.survivorDescriptors;int count=SurvivorDesc.getIDCount();
  world.survivorDescriptors=new HashMap<>();
  try{
   SurvivorDesc.setIDCount(10);var p=baseline();p.savedDesc.setID(100);p.savedDesc.setInstance(p);
   check(world.survivorDescriptors.isEmpty(),"temporary restored descriptor is not globally published");
   ApocBRPlayerRelationalReconstruction.publishDescriptorIdentity(p);check(world.survivorDescriptors.get(100)==p.savedDesc,"lookup returns authoritative restored descriptor");
   check(SurvivorDesc.getIDCount()==101,"next allocated ID lies above restored ID");
   p.savedDesc.setID(5);ApocBRPlayerRelationalReconstruction.publishDescriptorIdentity(p);check(SurvivorDesc.getIDCount()==101,"lower IDs do not rewind allocation");
   for(int bad:new int[]{-1,Integer.MAX_VALUE}){p.savedDesc.setID(bad);try{ApocBRPlayerRelationalReconstruction.publishDescriptorIdentity(p);throw new AssertionError("bad ID published");}catch(IllegalStateException expected){checks++;}}
  }finally{world.survivorDescriptors=original;SurvivorDesc.setIDCount(count);}
 }
'''


if __name__ == "__main__":
    source = (ROOT / "42.22.0/src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java").read_text()
    player = extract_method(source, "private IsoPlayer player(int slot)")
    assert player.index("components();") < player.index("finalizeItemVisuals();") < player.index("equipment(player);")
    assert "publishDescriptorIdentity" not in player
    server = (ROOT / "42.22.0/src/zombie/savefile/ServerPlayerDB.java").read_text()
    finish = extract_method(server, "public IsoPlayer finishPostgresPlayer(")
    assert finish.index("registerPostgresPlayer") < finish.index("publishDescriptorIdentity") < finish.index("restoreEquipmentState(player,true)")
    baseline_source = extract_method(source, "static void restoreProgressionBaseline(IsoPlayer player)")
    assert "triggerEvent(" not in baseline_source
    native = (ROOT / "42.22.0/decompiled/zombie/characters/IsoGameCharacter.java").read_text()
    normalize = extract_method(native, "if (this.totalXp > IsoGameCharacter.this.getXpForLevel(this.getLevel() + 1))")
    normalize = normalize.replace("this.totalXp", "p.getXp().totalXp").replace("IsoGameCharacter.this", "p").replace("this.getLevel()", "p.getXp().getLevel()").replace("this.setTotalXP", "p.getXp().setTotalXP").replace("this.chr", "p")
    run_fixture(JAVA + "static void nativeNormalize(BaselinePlayer p){" + normalize + "}", "visuals();progression();identity();", "visual/progression/lifecycle")
