"""42.22.1 production healing policy, transactions, native adapter and packet builders with engine doubles."""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '42.22.1/src/zombie/apocalipse/humans'

REGISTRY_HARNESS = r'''
import java.util.*;
import zombie.apocalipse.humans.*;
import zombie.apocalipse.humans.profiles.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.healing.*;
public class HealingProfileTest {
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError();}
 static HumanProfile profile(String id,int priority,Map<String,Long> parameters){
  var base=ServerHumanProfiles.registry().require("militia-patrol");var modules=new ArrayList<>(base.modules());
  modules.add(new HumanModuleBinding("healing",priority,100,parameters));
  return new HumanProfile(id,1,"modules",Map.of(),"","none",false,false,modules,8,100,true);
 }
 static void reject(HumanProfile p){try{ServerHumanProfiles.registry().register(p);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}}
 public static void main(String[] args){
  var registry=ServerHumanProfiles.registry();
  for(var id:List.of("militia-patrol","lobby-guide","roaming-greeter","stationary"))
   check(registry.require(id).modules().stream().noneMatch(b->b.moduleId().equals("healing")));
  var profile=profile("healing-test",20,Map.of());registry.register(profile);
  var first=(HumanModuleRuntime)registry.create(profile);var second=(HumanModuleRuntime)registry.create(profile);
  check(first.module(HealingModule.class)!=null&&first.module(HealingModule.class)!=second.module(HealingModule.class));
  reject(profile("bad-before-survival",5,Map.of()));reject(profile("bad-after-combat",101,Map.of()));
  reject(profile("bad-clearance",20,Map.of("clearTiles",9L)));reject(profile("bad-supplies",20,Map.of("bandages",33L)));
  reject(profile("bad-key",20,Map.of("arbitrary",1L)));
  var groupModules=new ArrayList<>(profile.modules());groupModules.removeIf(b->b.moduleId().equals("walk"));
  groupModules.add(new HumanModuleBinding("group-patrol",1000,100,Map.of()));
  var point=new zombie.apocalipse.humans.walking.WalkingPatternSpec.Point(10,10,0);
  var pattern=new zombie.apocalipse.humans.walking.WalkingPatternSpec("background","waypoint-circuit",64,List.of(point),
   new zombie.apocalipse.humans.walking.WalkingPatternSpec.Area(0,0,20,20));
  var grouped=new HumanProfile("grouped-test",1,"modules",Map.of(),"","none",false,false,groupModules,8,100,true,List.of(pattern),
   profile.cheats(),profile.survival(),profile.encounter(),2);
  registry.register(grouped);var groupRuntime=(HumanModuleRuntime)registry.create(grouped);
  check(grouped.healthMultiplier()==2&&groupRuntime.module(zombie.apocalipse.humans.groups.GroupPatrolModule.class)!=null);
  check(groupRuntime.module(zombie.apocalipse.humans.groups.GroupPatrolModule.class)!=((HumanModuleRuntime)registry.create(grouped)).module(zombie.apocalipse.humans.groups.GroupPatrolModule.class));
  System.out.println("Healing profile registration: "+checks+" assertions passed (actual compiled engine classes and game JAR)");
 }
}
'''

STUBS = {
'zombie/apocalipse/humans/HumanLog.java': '''package zombie.apocalipse.humans;class HumanLog {enum Level {DEBUG}static void log(Level l,java.util.function.Supplier<String> s){s.get();}}''',
'zombie/GameTime.java': '''package zombie; public class GameTime {public static GameTime getInstance(){return new GameTime();}public float getTrueMultiplier(){return 1;}}''',
'zombie/core/raknet/UdpConnection.java': '''package zombie.core.raknet; public class UdpConnection {}''',
'zombie/network/PacketTypes.java': '''package zombie.network; public class PacketTypes {public enum PacketType { State, BodyPartSync }}''',
'zombie/network/packets/INetworkPacket.java': '''package zombie.network.packets; public interface INetworkPacket {static void send(Object viewer,Object type,Object... values){}}''',
'zombie/ai/State.java': '''package zombie.ai; public class State {public enum Stage{Enter,Exit,Execute}}''',
'zombie/ai/states/PlayerActionsState.java': '''package zombie.ai.states; public class PlayerActionsState {public static PlayerActionsState instance(){return new PlayerActionsState();}}''',
'zombie/network/fields/Variables.java': '''package zombie.network.fields;public class Variables {private final java.util.HashMap<String,String> values=new java.util.HashMap<>();public java.util.HashMap<String,String> get(){return values;}}''',
'zombie/network/packets/actions/StatePacket.java': '''package zombie.network.packets.actions;
import java.util.*;import zombie.ai.State;import zombie.core.raknet.UdpConnection;import zombie.network.PacketTypes;
public class StatePacket {
 public static final List<StatePacket> sent=new ArrayList<>();
 protected final ID characterId=new ID();protected final ID state=new ID();protected boolean isSubState;
 protected final Params params=new Params();protected final zombie.network.fields.Variables events=new zombie.network.fields.Variables();protected State.Stage stage;
 public static Thread owner=Thread.currentThread();
 public void set(float x,float y,float z){}public void sendToClient(PacketTypes.PacketType t,UdpConnection c){if(Thread.currentThread()!=owner)throw new AssertionError("worker sent native packet");sent.add(this);}
 public Map<String,Object> parameters(){return params.values;}public Map<String,String> eventValues(){return events.get();}public State.Stage stage(){return stage;}public boolean sub(){return isSubState;}
 public static class ID {public void set(Object v){}}public static class Params {final Map<String,Object> values=new HashMap<>();public void rawset(String k,Object v){values.put(k,v);}}
}''',
'zombie/inventory/InventoryItem.java': '''package zombie.inventory;
public class InventoryItem {public static int next;public final int id=++next;public final String type;public boolean infected;
 public InventoryItem(String type){this.type=type;}public int getID(){return id;}public String getFullType(){return type;}public boolean isInfected(){return infected;}
 public float getBandagePower(){return type.contains("Sheets")?1:4;}public boolean isAlcoholic(){return type.contains("Alcohol");}}''',
'zombie/inventory/InventoryItemFactory.java': '''package zombie.inventory;public class InventoryItemFactory {public static InventoryItem CreateItem(String type){return new InventoryItem(type);}}''',
'zombie/inventory/ItemContainer.java': '''package zombie.inventory;import java.util.*;public class ItemContainer {
 public final List<InventoryItem> items=new ArrayList<>();public int removes;
 public List<InventoryItem> getItems(){return items;}public void AddItem(InventoryItem i){items.add(i);}public void Remove(InventoryItem i){if(!items.remove(i))throw new AssertionError("duplicate consumption");removes++;}
 public InventoryItem getItemWithID(int id){return items.stream().filter(i->i.id==id).findFirst().orElse(null);}}''',
'zombie/characters/BodyDamage/BodyPartType.java': '''package zombie.characters.BodyDamage;public enum BodyPartType {Head,Neck,Torso_Lower,Groin,Torso_Upper,Hand_L,ForeArm_L,UpperArm_L,Hand_R,ForeArm_R,UpperArm_R,UpperLeg_L,LowerLeg_L,Foot_L,UpperLeg_R,LowerLeg_R,Foot_R}''',
'zombie/characters/BodyDamage/BodyPart.java': '''package zombie.characters.BodyDamage;public class BodyPart {
 public final BodyPartType type;public boolean bandaged;public float health=50,bleeding,scratch,cut,bite,deep,burn;public int applications;
 public BodyPart(BodyPartType type,float bleeding){this.type=type;this.bleeding=bleeding;}
 public BodyPartType getType(){return type;}public boolean bandaged(){return bandaged;}
 public float getBleedingTime(){return bleeding;}public float getScratchTime(){return scratch;}public float getCutTime(){return cut;}
 public float getBiteTime(){return bite;}public float getDeepWoundTime(){return deep;}public float getBurnTime(){return burn;}
 public float getDamageScaler(){return .0057142857F;}
 public float getHealth(){return health;}public void SetHealth(float value){health=value;}
 public void setBandaged(boolean value,float life,boolean alcoholic,String type){bandaged=value;applications++;}
}''',
'zombie/characters/IsoPlayer.java': '''package zombie.characters;import java.util.*;import zombie.inventory.*;import zombie.characters.BodyDamage.*;
public class IsoPlayer {public float x=10,y=10;public int z;public boolean dead,performing;public void setPerformingAnAction(boolean v){performing=v;}public int blood;public final ItemContainer inventory=new ItemContainer();public final Body body=new Body();
 public boolean isDead(){return dead;}public float getX(){return x;}public float getY(){return y;}public float getZ(){return z;}public int getZi(){return z;}
 public ItemContainer getInventory(){return inventory;}public Body getBodyDamage(){return body;}public void splatBloodFloor(){blood++;}
 public static class Body {public final List<BodyPart> parts=new ArrayList<>();public List<BodyPart> getBodyParts(){return parts;}}
}''',
'zombie/apocalipse/humans/brain/HumanActivityController.java': '''package zombie.apocalipse.humans.brain;public class HumanActivityController {
 public enum Mode {AUTONOMOUS,DIALOGUE,TRADE,STOPPED}public Mode mode=Mode.AUTONOMOUS;public Mode mode(){return mode;}}''',
'zombie/apocalipse/humans/brain/HumanActuator.java': '''package zombie.apocalipse.humans.brain;public interface HumanActuator {
 enum MotionStatus{RUNNING,SUCCEEDED,FAILED}default void stopMoving(){}default void tactical(TacticalIntent intent){}
 default HumanCombat.Port combat(){return HumanCombat.UNSUPPORTED;}default MotionStatus updateMovement(long now){return MotionStatus.SUCCEEDED;}
 default zombie.apocalipse.humans.survival.SurvivalPort survival(){return zombie.apocalipse.humans.survival.SurvivalPort.UNSUPPORTED;}
 default void moduleChanged(String before,String after){}}''',
'zombie/apocalipse/humans/profiles/HumanProfile.java': '''package zombie.apocalipse.humans.profiles;public class HumanProfile {public static void identifier(String value){if(!value.matches("[a-z][a-z0-9-]*"))throw new IllegalArgumentException();}}''',
'zombie/apocalipse/humans/ServerHumanRetaliation.java': '''package zombie.apocalipse.humans;class ServerHumanRetaliation {
 static Active module(ServerHuman human){return human.aggressor?new Active():null;}static class Active {Object aggressor(){return this;}}}''',
'zombie/apocalipse/humans/ServerHuman.java': '''package zombie.apocalipse.humans;
import zombie.characters.IsoPlayer;import zombie.core.raknet.UdpConnection;import zombie.apocalipse.humans.state.*;import zombie.apocalipse.humans.brain.*;
class ServerHuman {
 final Definition definition=new Definition();record Definition(){String key(){return "fixture";}}
 final Capture actuator=new Capture();final Speech speech=new Speech();boolean danger;
 class Capture {HumanObservation observe(long now){return HealingTest.observation(now,state.snapshot(),danger);}}
 static class Speech {int calls;void state(zombie.apocalipse.humans.speech.SpeechEvent event,long now){calls++;}}
 final IsoPlayer player=new IsoPlayer();final Store state=new Store();final Movement movement=new Movement();final Profile profile=new Profile();
 final HumanActivityController activity=new HumanActivityController();final java.util.Set<UdpConnection> viewers=new java.util.HashSet<>();final java.util.Set<UdpConnection> distant=new java.util.HashSet<>();final Physiology physiology=new Physiology();boolean aggressor;
 boolean isRelevant(UdpConnection c){return !distant.contains(c);}
 static class Store {HumanState value=HumanState.INITIAL;HumanState snapshot(){return value;}void dispatch(HumanMutation m){value=HumanStateReducer.reduce(value,m);}}
 static class Profile {boolean god;double capacity=1;Profile cheats(){return this;}boolean godMode(){return god;}double healthMultiplier(){return capacity;}}
 static class Movement {boolean moving,traversing;boolean traversing(){return traversing;}Movement snapshot(){return this;}boolean moving(){return moving;}}
 static class Physiology {void project(){}}
}''',
}

HARNESS = r'''
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.healing.*;
import zombie.apocalipse.humans.state.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.survival.*;
import zombie.characters.BodyDamage.*;
import zombie.inventory.*;
import zombie.network.packets.actions.StatePacket;
import zombie.network.fields.Variables;
public class HealingTest {
 static final long S=1_000_000_000L;static int checks;
 static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
 static void rejected(Runnable fn){try{fn.run();throw new AssertionError("accepted invalid setting");}catch(IllegalArgumentException|ArithmeticException expected){checks++;}}
 static HealingSettings settings(int supplies){return HealingSettings.from(Map.of("bandages",(long)supplies));}
 static ServerHuman wounded(){var h=new ServerHuman();h.state.dispatch(new HumanMutation.Damage(.4f,0,0,0));return h;}
 static ServerHumanHealing service(ServerHuman h,int supplies){h.viewers.add(new zombie.core.raknet.UdpConnection());var port=new ServerHumanHealing(h,settings(supplies));port.serverTick(0);return port;}
 static HumanObservation observation(long now,HumanState state,boolean threat){
  var contacts=threat?List.of(new HumanSenses.Contact(new HumanSenses.Identity(HumanSenses.Kind.ZOMBIE,(short)1,1,1),11,10,0,1,now,now,true)):List.<HumanSenses.Contact>of();
  var frame=new HumanSenses.Frame(true,now,contacts,HumanSenses.Coverage.COMPLETE,HumanSenses.Coverage.COMPLETE,false,new HumanSenses.Work(0,0,0,0,0));
  return new HumanObservation(now,List.of(),true,state,frame,new HumanTerritory(true,true,contacts.stream().map(HumanSenses.Contact::identity).collect(java.util.stream.Collectors.toSet())));
 }
 static class Actuator implements HumanActuator {int stops;public void stopMoving(){stops++;}}
 static void transactions(){
  class World implements MedicalExecution.World {int starts,commits,stops;boolean valid=true;boolean fail;
   public boolean valid(){return valid;}public boolean start(){starts++;return true;}public long durationNanos(){return 4*S;}
   public boolean commit(){commits++;if(fail)throw new IllegalStateException();return true;}public void stop(){stops++;}}
  var w=new World();var e=new MedicalExecution(w);e.update(0);for(int i=0;i<20;i++)e.update(S);
  check(w.starts==1&&w.commits==0,"polling neither restarts nor commits early");e.update(4*S);e.update(9*S);e.cancel();
  check(w.commits==1&&w.stops==1&&e.status()==HumanHealingPort.Status.COMPLETED,"commit exactly once");
  w=new World();e=new MedicalExecution(w);e.update(0);e.cancel();e.update(5*S);check(w.commits==0&&w.stops==1,"cancel has no late commit");
  w=new World();e=new MedicalExecution(w);e.update(0);w.valid=false;e.update(4*S);check(w.commits==0&&w.stops==1,"revalidate at deadline");
  w=new World();w.fail=true;e=new MedicalExecution(w);e.update(0);try{e.update(4*S);}catch(IllegalStateException expected){}e.update(9*S);
  check(w.commits==1&&w.stops==1&&e.status()==HumanHealingPort.Status.FAILED,"throwing commit cannot replay");
 }
 static void nativeTreatment() throws Exception {
  StatePacket.sent.clear();var h=wounded();var first=new BodyPart(BodyPartType.Neck,20);var second=new BodyPart(BodyPartType.Foot_R,10);
  h.player.body.parts.addAll(List.of(first,second));var port=service(h,3);float rate=h.state.snapshot().injuries().bleedingPerSecond();
  check(rate>0&&h.player.blood==1,"native wounds import bleeding and emit bounded blood effect");
  float before=h.state.snapshot().vitals().health();h.state.dispatch(new HumanMutation.Advance(.1f,0,0,0,0));
  check(Math.abs(h.state.snapshot().vitals().health()-(before-rate*.1f))<.000001,"one reducer applies bleeding once");
  var e=port.begin(0);port.serverTick(0);
  check(h.player.performing,"server keeps medical action flag while dressing");
  check(StatePacket.sent.size()==1,"one native action start");var packet=StatePacket.sent.getFirst();
  var vars=(Variables)packet.parameters().get("variables");
  check(packet.sub()&&packet.stage()==zombie.ai.State.Stage.Enter&&vars.get().get("PerformingAction").equals("Bandage")
   &&vars.get().get("BandageType").equals("Head")&&packet.eventValues().containsKey("EventBandage")&&packet.parameters().get("override").equals(true),"stock player bandage action fields");
  for(int i=1;i<=42;i++){e.update(i*S/10);port.serverTick(i*S/10);}
  check(first.bandaged()&&!second.bandaged()&&first.applications==1&&h.player.inventory.removes==1,"one item dresses only the selected wound");
  check(h.state.snapshot().injuries().bleedingPerSecond()>0&&h.state.snapshot().injuries().bleedingPerSecond()<rate,"other wound continues bleeding");
  check(e.update(5*S)==HumanHealingPort.Status.COMPLETED,"terminal result returned to worker");
  check(StatePacket.sent.getLast().stage()==zombie.ai.State.Stage.Exit,"native exit clears first aid");
  e=port.begin(5*S);port.serverTick(5*S);
  var late=new zombie.core.raknet.UdpConnection();h.viewers.add(late);port.serverTick(5*S+S/10);
  check(StatePacket.sent.getLast().stage()==zombie.ai.State.Stage.Enter,"late observer receives current action");
  for(int i=51;i<=92;i++){e.update(i*S/10);port.serverTick(i*S/10);}
  check(second.bandaged()&&h.player.inventory.removes==2&&h.state.snapshot().injuries().bleedingPerSecond()==0,"second bandage stops remaining bleeding");
  before=h.state.snapshot().vitals().health();check(port.begin(10*S)==null,"passive recovery cannot acquire a treatment lease");
  h.movement.moving=true;
  for(int i=100;i<=110;i++){h.player.x+=.2f;port.serverTick(i*S/10);}
  check(h.state.snapshot().vitals().health()>before&&h.player.inventory.removes==2,"passive recovery restores health while moving without consuming another dressing");
  check(h.speech.calls==2,"healing speech fires on two real dressing starts, not passive recovery ticks");
  check(first.getHealth()>50&&second.getHealth()>50,"native body parts recover with logical health without erasing wound timers");
  final var workerExecution=e;int sent=StatePacket.sent.size();
  var workerFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  Thread worker=new Thread(()->{try{workerExecution.update(12*S);workerExecution.cancel();port.snapshot();}catch(Throwable problem){workerFailure.set(problem);}});worker.start();worker.join();
  check(workerFailure.get()==null&&StatePacket.sent.size()==sent,"worker commands do not touch packet/native presentation");port.serverTick(12*S);
 }
 static void cancellation(){
  for(String cause:List.of("hurt","combat","supplies","move","dialogue","lease","close","aggressor","death","traversal")){
   var h=wounded();var part=new BodyPart(BodyPartType.Hand_L,10);h.player.body.parts.add(part);var p=service(h,1);var e=p.begin(0);p.serverTick(0);
   switch(cause){case "hurt"->p.hurt(S/10);case "combat"->p.combat(S/10);case "supplies"->h.player.inventory.items.clear();case "move"->h.player.x++;
    case "dialogue"->h.activity.mode=HumanActivityController.Mode.DIALOGUE;case "close"->p.close();case "aggressor"->h.aggressor=true;
    case "death"->h.state.dispatch(new HumanMutation.Kill());case "traversal"->h.movement.traversing=true;default->{}}
   if(!cause.equals("lease"))e.update(S/10);p.serverTick(cause.equals("lease")?S:S/10);p.serverTick(5*S);
   check(!part.bandaged()&&h.player.inventory.removes==0,"cancel without consumption: "+cause);
   check(StatePacket.sent.getLast().stage()==zombie.ai.State.Stage.Exit&&!h.player.performing,"cancel clears remote action and movement flag: "+cause);
  }
  var h=wounded();h.player.body.parts.add(new BodyPart(BodyPartType.Hand_R,10));var p=service(h,0);
  check(p.snapshot().supply()&&p.snapshot().wounded(),"fallback supplies available with empty inventory");
  var fallback=p.begin(0);p.serverTick(0);
  for(int i=1;i<=57;i++){fallback.update(i*S/10);p.serverTick(i*S/10);}
  check(h.player.body.parts.getFirst().bandaged()&&h.player.inventory.removes==0&&h.player.inventory.items.isEmpty(),"fallback wraps wound without creating or consuming inventory");
  p.serverTick(6*S);check(h.player.body.parts.getFirst().applications==1&&!h.player.performing,"fallback commits once and clears action flag");
  h=wounded();h.player.body.parts.add(new BodyPart(BodyPartType.Hand_R,10));p=service(h,0);
  h.player.inventory.AddItem(new InventoryItem("Base.RippedSheets"));p.serverTick(0);var e=p.begin(0);p.serverTick(0);
  for(int i=1;i<=55;i++){e.update(i*S/10);p.serverTick(i*S/10);}check(h.player.inventory.removes==0,"sheets have longer duration");
  e.update(56*S/10);p.serverTick(56*S/10);check(h.player.inventory.removes==1,"sheets used once at deadline");
  h=wounded();h.state.dispatch(new HumanMutation.Damage(0,.3f,.2f,.001f));p=service(h,1);e=p.begin(0);p.serverTick(0);
  for(int i=1;i<=42;i++){e.update(i*S/10);p.serverTick(i*S/10);}
  check(h.state.snapshot().injuries().equals(HumanState.Injuries.NONE),"persisted aggregate injuries remain treatable after reactivation");
 }
 static void viewersAndOwnership(){
  StatePacket.sent.clear();var h=wounded();h.player.body.parts.add(new BodyPart(BodyPartType.LowerLeg_L,10));var p=service(h,1);
  var viewer=h.viewers.iterator().next();var e=p.begin(0);p.serverTick(0);int before=StatePacket.sent.size();
  p.requestAnnouncement(viewer);e.update(S/10);p.serverTick(S/10);
  check(StatePacket.sent.size()==before+1&&StatePacket.sent.getLast().stage()==zombie.ai.State.Stage.Enter,"reannouncement resends action on server thread");
  h.distant.add(viewer);p.serverTick(2*S/10);e.cancel();p.serverTick(3*S/10);before=StatePacket.sent.size();
  h.distant.clear();p.serverTick(4*S/10);
  check(StatePacket.sent.size()==before+1&&StatePacket.sent.getLast().stage()==zombie.ai.State.Stage.Exit,"returning viewer clears cancelled action");
  var stale=e;var fresh=p.begin(S);p.serverTick(S);stale.cancel();fresh.update(11*S/10);p.serverTick(11*S/10);
  check(fresh.update(11*S/10)==HumanHealingPort.Status.RUNNING,"stale execution cannot cancel newer treatment token");
  var other=wounded();other.player.body.parts.add(new BodyPart(BodyPartType.Head,10));var q=service(other,1);var isolated=q.begin(0);q.serverTick(0);
  fresh.cancel();p.serverTick(12*S/10);isolated.update(S/10);q.serverTick(S/10);
  check(isolated.update(S/10)==HumanHealingPort.Status.RUNNING,"two actors own independent execution state");
  q.hurt(S/10);q.serverTick(2*S/10);check(!q.snapshot().available(),"damage starts quiet window");q.serverTick(52*S/10);
  check(q.snapshot().available(),"quiet window releases with valid living actor");
  var god=wounded();god.profile.god=true;god.player.body.parts.add(new BodyPart(BodyPartType.Neck,20));var g=service(god,1);
  check(god.state.snapshot().injuries().bleedingPerSecond()==0&&god.player.blood==0,"protection does not invent bleeding damage");
  check(ServerHumanHealing.region(BodyPartType.Torso_Lower).equals("LowerBody")&&ServerHumanHealing.region(BodyPartType.ForeArm_R).equals("RightArm")
   &&ServerHumanHealing.region(BodyPartType.Foot_L).equals("LeftLeg")&&ServerHumanHealing.region(BodyPartType.UpperLeg_R).equals("RightLeg"),"native anatomical region mapping");
 }
 static void recoveryLoop(){
  var h=wounded();h.state.dispatch(new HumanMutation.Damage(.4f,0,0,0));h.player.body.parts.add(new BodyPart(BodyPartType.Torso_Upper,10));
  var p=service(h,1);var survival=new SurvivalModule(SurvivalSettings.DEFAULT);survival.bindHealing(p,settings(1));var a=new Actuator();
  boolean released=false;
  for(int i=0;i<5000;i++){
   long now=i*S/10;p.serverTick(now);h.state.dispatch(new HumanMutation.Advance(.1f,0,0,0,0));
   survival.update(observation(now,h.state.snapshot(),false),a);
   if(i>10&&survival.phase()==SurvivalModule.Phase.SAFE){released=true;break;}
  }
  check(released&&h.state.snapshot().vitals().health()>=SurvivalSettings.DEFAULT.reengageHealth(),"wounded survivor treats, rests and reaches reengagement threshold");
  check(h.player.inventory.removes==1&&h.state.snapshot().injuries().bleedingPerSecond()==0,"recovery does not replenish or double-spend medical supplies");
  var ordinary=wounded();ordinary.player.body.parts.add(new BodyPart(BodyPartType.Head,10));service(ordinary,0);
  var doubled=wounded();doubled.profile.capacity=2;doubled.player.body.parts.add(new BodyPart(BodyPartType.Head,10));service(doubled,0);
  check(Math.abs(ordinary.state.snapshot().injuries().bleedingPerSecond()/2-doubled.state.snapshot().injuries().bleedingPerSecond())<.000001,"double health also scales logical bleeding once");
 }
 static void openingDwell(){
  class A implements HumanActuator {
   int routes;
   public zombie.apocalipse.humans.survival.SurvivalPort survival(){return new zombie.apocalipse.humans.survival.SurvivalPort(){
    public boolean escape(List<HumanSenses.Contact> threats,SurvivalSettings settings,long now){routes++;return false;}
    public HumanSenses.Contact openingTarget(List<HumanSenses.Contact> candidates){return candidates.isEmpty()?null:candidates.getFirst();}
   };}
   public HumanCombat.Port combat(){return (r,n)->HumanCombat.finished(new HumanCombat.Result(HumanCombat.Status.COMPLETED,HumanCombat.Reason.NONE));}
  }
  var a=new A();var module=new SurvivalModule(SurvivalSettings.DEFAULT);
  var low=HumanStateReducer.reduce(HumanState.INITIAL,new HumanMutation.Damage(.8f,0,0,0));
  module.update(observation(0,low,true),a);module.update(observation(2*S,low,true),a);
  check(module.phase()==SurvivalModule.Phase.CREATE_OPENING&&a.routes==2,"repeated blocked escape starts opening dwell");
  for(int i=21;i<40;i++)module.update(observation(i*S/10,low,true),a);
  check(module.phase()==SurvivalModule.Phase.CREATE_OPENING&&a.routes==2,"successful strikes cannot trigger subsecond flee/fight retries");
  for(int i=40;i<=46;i++)module.update(observation(i*S/10,low,true),a);
  check(a.routes==3,"opening may retry after its minimum two-second commitment; routes="+a.routes+" phase="+module.phase());
 }
 static void policy(){
  var h=wounded();h.player.body.parts.add(new BodyPart(BodyPartType.Torso_Upper,10));var p=service(h,1);var module=new HealingModule(settings(1));module.bind(p);var a=new Actuator();
  var safe=observation(0,h.state.snapshot(),false);check(module.eligible(safe)&&module.eligible(safe)&&a.stops==0,"eligibility is pure");
  check(!module.eligible(observation(0,h.state.snapshot(),true)),"nearby zombie prevents bandaging");
  check(!module.eligible(new HumanObservation(0,List.of(),true,h.state.snapshot())),"missing sensing fails closed");
  check(!module.eligible(observation(S,h.state.snapshot(),false)),"stale native sample prevents starting");
  module.update(safe,a);p.serverTick(0);module.interrupt(S/10,a);p.serverTick(S/10);
  check(h.player.inventory.removes==0,"module preemption cancels native action");
  module.suspend(S/10,a);check(!module.eligible(safe),"dialogue suspends policy");module.resume(null,S/10,a);module.stop(S/10,a);check(!module.available(),"stop closes policy");
  var survival=new SurvivalModule(SurvivalSettings.DEFAULT);survival.bindHealing(p,settings(1));
  h.state.dispatch(new HumanMutation.Damage(.4f,0,0,0));p.serverTick(S);survival.update(observation(S,h.state.snapshot(),false),a);p.serverTick(S);
  check(survival.phase()==SurvivalModule.Phase.RECOVERING,"low-health survival enters recovery with treatment available");
  check(StatePacket.sent.getLast().stage()==zombie.ai.State.Stage.Enter,"survival itself starts treatment instead of starving healing module");
  survival.update(observation(S+S/10,h.state.snapshot(),true),a);p.serverTick(S+S/10);
  check(StatePacket.sent.getLast().stage()==zombie.ai.State.Stage.Exit,"new danger interrupts recovery treatment");
  check(h.player.inventory.removes==0,"survival interruption preserves supplies");
  var resting=wounded();var restPort=service(resting,0);var restModule=new HealingModule(settings(0));restModule.bind(restPort);
  for(int i=0;i<=11;i++){long at=i*S/10;restPort.serverTick(at);restModule.update(observation(at,resting.state.snapshot(),false),a);}
  check(!restModule.eligible(observation(11*S/10,resting.state.snapshot(),false)),"passive recovery never owns the healing module or stops patrol");
  check(resting.state.snapshot().vitals().health()>.6f,"passive recovery works without selected medical execution");
  resting.danger=true;float before=resting.state.snapshot().vitals().health();restPort.serverTick(12*S/10);
  check(resting.state.snapshot().vitals().health()==before,"nearby danger prevents passive healing too");
 }
 public static void main(String[] args)throws Exception {
  openingDwell();transactions();nativeTreatment();cancellation();policy();viewersAndOwnership();recoveryLoop();
  rejected(()->HealingSettings.from(Map.of("bandages",33L)));rejected(()->HealingSettings.from(Map.of("wat",1L)));
  rejected(()->HealingSettings.from(Map.of("clearTiles",Long.MAX_VALUE)));rejected(()->HealingSettings.from(Map.of("recoveryPermillePerSecond",11L)));
  var dead=HumanStateReducer.reduce(HumanState.INITIAL,new HumanMutation.Kill());check(HumanStateReducer.reduce(dead,new HumanMutation.Heal(1))==dead,"medicine cannot revive dead actors");
  System.out.println("Healing: "+checks+" assertions passed (42.22.1 production classes; engine/network doubles)");
 }
}
'''

def main():
    production = list((SOURCE / 'healing').glob('*.java'))
    production += [SOURCE / name for name in ('ServerHumanHealing.java', 'ServerHumanMedicalPacket.java','speech/SpeechEvent.java')]
    production += [SOURCE / 'state' / name for name in ('HumanState.java','HumanMutation.java','HumanStateReducer.java')]
    production += [SOURCE / 'brain' / name for name in ('HumanObservation.java','HumanSenses.java','HumanTerritory.java','TacticalIntent.java',
        'HumanBehavior.java','HumanModule.java','HumanModuleRuntime.java','HumanCombat.java','HumanCombatInventory.java','HumanCombatTactics.java','HumanEngagement.java')]
    production += [SOURCE / 'survival' / name for name in ('SurvivalModule.java','SurvivalSettings.java','SurvivalPort.java','ThreatAssessment.java')]
    with tempfile.TemporaryDirectory(prefix='apocbr-healing-') as folder:
        work=Path(folder);sources=list(production)
        for name,content in {**STUBS,'zombie/apocalipse/humans/HealingTest.java':HARNESS}.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-ea','-cp',str(work/'classes'),'zombie.apocalipse.humans.HealingTest'],check=True)
    if len(sys.argv)>1:
        classes=Path(sys.argv[1]).resolve()
        jar=Path('Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar')
        with tempfile.TemporaryDirectory(prefix='apocbr-healing-profiles-') as folder:
            work=Path(folder);source=work/'HealingProfileTest.java';source.write_text(REGISTRY_HARNESS,encoding='utf-8')
            classpath=str(classes)+';'+str(jar)
            subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-cp',classpath,'-d',str(work),str(source)],check=True)
            subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-ea','-cp',str(work)+';'+classpath,'HealingProfileTest'],check=True)

if __name__=='__main__':main()
