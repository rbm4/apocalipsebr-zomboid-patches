"""Production seating adapter and State-packet builders against explicit native doubles.

Exercises movement/collision ports, native fields, late-viewer receipts and recovery.
Actual game State serialization, animation playback and physics remain runtime checks.
"""
from pathlib import Path
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from test_human_furniture_native import FIXTURE, method

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "42.22.2/src/zombie/apocalipse/humans"

HARNESS = r'''
package zombie.apocalipse.humans;
import java.util.*;import zombie.iso.*;import zombie.iso.sprite.*;import zombie.characters.*;import zombie.core.raknet.*;
import zombie.apocalipse.humans.seating.*;import zombie.apocalipse.humans.seating.HumanSeating.*;import zombie.apocalipse.humans.furniture.*;
import zombie.apocalipse.humans.brain.*;import zombie.apocalipse.humans.state.*;import zombie.network.packets.actions.StatePacket;
public class NativeSeatingTest {
 static int checks;static final long SEC=1_000_000_000L;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static class Catalog implements ServerHumanFurnitureWorld.Catalog {
  public ServerHumanFurnitureWorld.Descriptor describe(IsoSprite s){return s==null?null:new ServerHumanFurnitureWorld.Descriptor("S",new zombie.apocalipse.humans.furniture.FurnitureAffordances.Seat(.1f,.08f,.2f,Set.of("Front","Left","Right")));}public void clear(){}
 }
 static class Scene {
  final IsoCell cell=new IsoCell();final IsoObject chair=new IsoObject();final ServerHuman human;final ServerHumanFurniture furniture;
  Scene(){
   zombie.pathfind.PolygonalMap2.blockSouth=false;zombie.iso.LosUtil.blocked=false;zombie.network.packets.INetworkPacket.variables.clear();StatePacket.sent.clear();
   zombie.network.GameServer.Players.clear();
   Object room=new Object();for(int x=7;x<15;x++)for(int y=7;y<15;y++)cell.put(x,y,0,room);
   zombie.iso.IsoWorld.instance.currentCell=cell;
   var square=cell.getGridSquare(10,10,0);square.solid=true;chair.setSquare(square);chair.sprite=new IsoSprite("chair");square.objects.add(chair);
   furniture=new ServerHumanFurniture(new ServerHumanFurnitureWorld(new Catalog()));human=new ServerHuman(cell);furniture.register(human);
   human.seating=new ServerHumanSeating(human,furniture,true);human.viewers.add(new UdpConnection());
  }
  Execution begin(long duration){var c=furniture.candidateAt(human,10,10,0,"Front",0);return human.seating.begin(new Request(c.token(),"Front",duration),0);}
 }
 static void cycle(){
  var s=new Scene();var handle=s.begin(10000);check(handle!=null,"native seating intent admitted");
  check(handle.update(0)==Status.RUNNING&&s.human.player.isSittingOnFurniture(),"native sit commits after bounded approach");
  var enter=StatePacket.sent.getFirst();check(enter.state instanceof zombie.ai.states.PlayerSitOnFurnitureState&&!enter.substate&&enter.enter,
   "sitting uses native main-state enter");
  check(enter.params.get("sit_object")==s.chair&&enter.params.get("dir")==IsoDirections.S&&enter.params.get("before_sit_dir").equals("Front")&&enter.events.containsKey("EventSitOnFurniture"),"native sit parameter types/names and event");
  var safe=s.human.seating.checkpoint();check(safe.y()>11,"checkpoint stays outside solid seat");
  handle.update(1_250_000_000L);check(handle.seated()&&s.human.seating.phase()==Phase.SEATED&&Math.abs(s.human.player.x-10.6)<.0001&&Math.abs(s.human.player.y-10.58)<.0001,"seated authority reaches native anchor");
  check(s.human.player.getVariableString("SitOnFurnitureAnim").equals("Idle"),"native seated idle variables");
  var other=new ServerHuman(s.cell);other.player.x=12.5f;other.player.y=12.5f;s.furniture.register(other);other.seating=new ServerHumanSeating(other,s.furniture,true);
  // A second actor cannot claim a currently seated object, even without square membership.
  var observed=s.furniture.candidateAt(other,10,10,0,"Front",2*SEC);check(observed==null,"live detached occupancy excludes second claimant");s.furniture.remove(other);
  check(!s.human.motionAllowed(),"external movement requests safe stand rather than stealing posture");
  zombie.pathfind.PolygonalMap2.blockSouth=true;handle.update(1_400_000_000L);
  check(s.human.seating.phase()==Phase.STAND_UP&&s.human.seating.plan().side().equals("Left"),"blocked original exit chooses another permitted side");
  var up=StatePacket.sent.stream().filter(p->p.state instanceof zombie.ai.states.PlayerGetUpState&&p.enter).findFirst().orElseThrow();
  check(up.params.get("force").equals(true)&&up.params.get("moving").equals(false)&&up.params.get("iso_direction")==IsoDirections.S,"native get-up payload");
  handle.update(2_700_000_000L);check(handle.update(2_800_000_000L)==Status.CANCELLED&&!s.human.player.isSittingOnFurniture()&&s.human.player.getSitOnFurnitureObject()==null,"cancelled action completes physical cleanup");
  check(s.human.motionAllowed()&&s.human.player.x>11,"movement admitted only after clear exit");s.human.seating.close();s.furniture.remove(s.human);
 }
 static void viewers(){
  var s=new Scene();var h=s.begin(20000);h.update(0);h.update(1_250_000_000L);
  var late=new UdpConnection();s.human.viewers.add(late);s.human.seating.transport.announce(late);
  float x=s.human.player.x,y=s.human.player.y;h.update(2*SEC);
  check(StatePacket.sent.stream().anyMatch(p->p.viewer==late&&p.enter),"late observer gets native seat reconstruction");
  check(s.human.player.x==x&&s.human.player.y==y,"per-viewer entry reconstruction never moves authoritative actor");
  check(!s.human.seating.transport.movementAllowed(late),"late observer movement waits for its entry settling window");
  h.update(3_300_000_000L);check(s.human.seating.transport.movementAllowed(late),"late observer resumes coherent heartbeat after idle publication");
  h.update(4_600_000_000L);h.update(5_900_000_000L);h.update(7_200_000_000L);h.update(8_500_000_000L);
  long enters=StatePacket.sent.stream().filter(p->p.viewer==late&&p.enter&&p.state instanceof zombie.ai.states.PlayerSitOnFurnitureState).count();
  check(enters==3,"world-readiness retries bounded to three attempts");
  long resets=zombie.network.packets.INetworkPacket.variables.stream().filter(v->v.viewer()==late&&v.key().equals("SitOnFurnitureStarted")&&Boolean.FALSE.equals(v.value())).count();
  check(resets==1,"retry does not replay a healthy client's sit animation");s.human.seating.close();s.furniture.remove(s.human);
 }
 static void reaction(){
  var s=new Scene();var h=s.begin(10000);h.update(0);h.update(1_250_000_000L);s.human.player.setVariable("HitReaction","hit");
  var v=HumanState.Vitals.HEALTHY;s.human.state.value=new HumanState(1,new HumanState.Vitals(.8f,1,0,0,0,0,0,0,0,0,0),HumanState.Injuries.NONE);
  h.update(1_400_000_000L);check(h.update(1_500_000_000L)==Status.FAILED&&!s.human.player.isSittingOnFurniture(),"damage cancels seating authority");
  check(StatePacket.sent.stream().noneMatch(p->p.enter&&p.state instanceof zombie.ai.states.PlayerGetUpState),"get-up never overwrites native hit reaction");
  check(!s.human.motionAllowed()&&s.human.seating.status().startsWith("RECOVERY"),"solid-seat recovery retains movement ownership");
  s.human.player.clearVariable("HitReaction");s.human.seating.tick(3*SEC);check(s.human.motionAllowed()&&s.human.player.y>11,"safe placement occurs after reaction owner releases");
  s.human.seating.close();s.furniture.remove(s.human);
 }
 static void refusal(){
  var s=new Scene();var candidate=s.furniture.candidateAt(s.human,10,10,0,"Front",0);var r=new Request(candidate.token(),"Front",1000);
  var unsupported=new ServerHumanSeating(s.human,s.furniture,false);check(unsupported.begin(r,0)==null&&unsupported.status().contains("unsupported_assets"),"modified animations never use guessed motion");
  s.human.activity.beginDialogue(new HumanObservation.Identity((short)7,1),0,s.human.actuator);check(s.human.seating.begin(r,0)==null,"exclusive dialogue cannot be bypassed by a native seating port");
  s.human.activity.finishDialogue(0,s.human.actuator);zombie.iso.LosUtil.blocked=true;check(s.human.seating.begin(r,0)==null&&!s.human.player.isSittingOnFurniture(),"wall/door/window passage rejection happens before sit commit");
  s.human.seating.close();s.furniture.remove(s.human);
 }
 static void playerPriority(){
  var s=new Scene();var h=s.begin(10000);var player=new IsoPlayer(s.cell);player.seated=s.chair;s.chair.sat=true;zombie.network.GameServer.Players.add(player);
  check(h.update(0)==Status.FAILED&&s.chair.sat&&!s.human.player.isSittingOnFurniture(),"cancelled approach never clears another player's chair flag");s.human.seating.close();s.furniture.remove(s.human);
  s=new Scene();h=s.begin(10000);h.update(0);h.update(1_250_000_000L);player=new IsoPlayer(s.cell);player.seated=s.chair;zombie.network.GameServer.Players.add(player);
  h.update(1_400_000_000L);check(h.update(1_500_000_000L)==Status.FAILED&&s.chair.sat,"player takeover keeps its native occupancy flag while NPC yields");s.human.seating.close();s.furniture.remove(s.human);
 }
 static Object call(String name,Object... args){var table=(se.krka.kahlua.vm.KahluaTable)zombie.Lua.LuaManager.env.rawget("ApocBRSeating");var frame=new se.krka.kahlua.vm.LuaCallFrame(args);((se.krka.kahlua.vm.JavaFunction)table.rawget(name)).call(frame,args.length);return frame.result;}
 static void commands(){
  ServerHumanSeatingCommands.install();check(Boolean.TRUE.equals(call("sitAt","npc",10.0,11.0,0.0,"Left",1000.0)),"trusted Lua command passes validated typed intent");
  int calls=ServerHumanFactory.INSTANCE.calls;
  check(Boolean.FALSE.equals(call("sitAt","npc",10.25,11.0,0.0,"Left",1000.0))&&ServerHumanFactory.INSTANCE.calls==calls,"fractional coordinates never reach native command");
  check(Boolean.FALSE.equals(call("sitAt","npc",10.0,11.0,0.0,"Left",Double.NaN)),"nonfinite Lua timing rejected");
  check(Boolean.FALSE.equals(call("sitAt","npc",10.0,11.0,0.0,"Back",1000.0)),"unknown side rejected at Lua boundary");
  check(Boolean.TRUE.equals(call("stand","npc"))&&call("status","npc").equals("TEST"),"stand and status routed without a client authority path");
 }
 public static void main(String[] args){cycle();viewers();reaction();refusal();playerPriority();commands();System.out.println("PASS: "+checks+" production native seating/presentation/recovery assertions");}
}
'''


def main():
    f = dict(FIXTURE)
    f["zombie/network/GameServer.java"] = f["zombie/network/GameServer.java"].replace("public class GameServer {", "public class GameServer {public static boolean server=true;")
    f["se/krka/kahlua/vm/JavaFunction.java"] = "package se.krka.kahlua.vm;public interface JavaFunction {int call(LuaCallFrame frame,int count);}"
    f["se/krka/kahlua/vm/LuaCallFrame.java"] = "package se.krka.kahlua.vm;public class LuaCallFrame {Object[] args;public Object result;public LuaCallFrame(Object[] a){args=a;}public Object get(int i){return args[i];}public void push(Object value){result=value;}}"
    f["se/krka/kahlua/vm/KahluaTable.java"] = "package se.krka.kahlua.vm;public class KahluaTable {java.util.Map<Object,Object> data=new java.util.HashMap<>();public void rawset(Object k,Object v){data.put(k,v);}public Object rawget(Object k){return data.get(k);}}"
    f["zombie/Lua/LuaManager.java"] = "package zombie.Lua;import se.krka.kahlua.vm.*;public class LuaManager {public static KahluaTable env=new KahluaTable();public static final Platform platform=new Platform();public static class Platform {public KahluaTable newTable(){return new KahluaTable();}}}"
    f["zombie/apocalipse/humans/ServerHumanFactory.java"] = "package zombie.apocalipse.humans;class ServerHumanFactory {static final ServerHumanFactory INSTANCE=new ServerHumanFactory();int calls;static ServerHumanFactory getInstance(){return INSTANCE;}boolean sitAt(String key,int x,int y,int z,String side,long ms){calls++;return true;}boolean stand(String key){return true;}String seatingStatus(String key){return \"TEST\";}}"
    f["zombie/iso/IsoGridSquare.java"] = f["zombie/iso/IsoGridSquare.java"].replace("public int x,y,z;", "public int x,y,z;public boolean solid;public boolean isSolid(){return solid;}public boolean isSolidTrans(){return false;}")
    f["zombie/iso/IsoDirections.java"] = "package zombie.iso;public enum IsoDirections {N,NW,W,SW,S,SE,E,NE;public static IsoDirections fromString(String s){return valueOf(s);}public IsoDirections RotRight(int n){return values()[(ordinal()-n+8)%8];}public IsoDirections RotLeft(int n){return values()[(ordinal()+n)%8];}}"
    f["zombie/iso/IsoWorld.java"] = "package zombie.iso;public class IsoWorld {public static final IsoWorld instance=new IsoWorld();public IsoCell currentCell;}"
    f["zombie/iso/LosUtil.java"] = "package zombie.iso;public class LosUtil {public static boolean blocked;public enum TestResults{Clear,Blocked,ClearThroughClosedDoor,ClearThroughWindow}public static TestResults lineClear(IsoCell c,int x,int y,int z,int a,int b,int d,boolean e){return blocked?TestResults.Blocked:TestResults.Clear;}}"
    f["zombie/iso/SpriteDetails/IsoFlagType.java"] = "package zombie.iso.SpriteDetails;public enum IsoFlagType{solid,solidtrans}"
    f["zombie/util/StringUtils.java"] = "package zombie.util;public class StringUtils {public static boolean isNullOrEmpty(String s){return s==null||s.isEmpty();}}"
    f["zombie/pathfind/PolygonalMap2.java"] = r'''package zombie.pathfind;import zombie.iso.*;public class PolygonalMap2 {
public static final PolygonalMap2 instance=new PolygonalMap2();public static boolean blockSouth;
public boolean canStandAt(float x,float y,int z,zombie.characters.IsoPlayer p,boolean a,boolean b){var s=p.cell.getGridSquare((int)Math.floor(x),(int)Math.floor(y),z);return s!=null&&!s.solid&&!(blockSouth&&y>=11&&x<11);}
public boolean lineClearCollide(float x,float y,float a,float b,int z,Object o,boolean c,boolean d){return false;}}
'''
    f["zombie/characters/IsoPlayer.java"] = r'''package zombie.characters;import java.util.*;import zombie.iso.*;
public class IsoPlayer {public IsoCell cell;public float x=10.5f,y=12.5f,realx,realy;public int z;public byte realz;public boolean dead,sitting;public IsoObject seated;public int occupancyReads;public IsoDirections direction=IsoDirections.S;public final Map<String,String> vars=new HashMap<>();
public IsoPlayer(IsoCell c){cell=c;}public IsoCell getCell(){return cell;}public boolean isDead(){return dead;}public int getXi(){return (int)Math.floor(x);}public int getYi(){return (int)Math.floor(y);}public int getZi(){return z;}
public float getX(){return x;}public float getY(){return y;}public float getZ(){return z;}public void setX(float v){x=v;}public void setY(float v){y=v;}public void setZ(float v){z=(int)v;}
public short getOnlineID(){return 768;}public IsoObject getSitOnFurnitureObject(){return seated;}public void setSitOnFurnitureObject(IsoObject o){seated=o;}
public void setSitOnFurnitureDirection(IsoDirections d){}public void setSittingOnFurniture(boolean b){sitting=b;}public boolean isSittingOnFurniture(){return sitting;}
public void setForwardIsoDirection(IsoDirections d){direction=d;}public void setVariable(String k,String v){vars.put(k,v);}public void setVariable(String k,boolean v){vars.put(k,""+v);}
public String getVariableString(String k){return vars.getOrDefault(k,"");}public void clearVariable(String k){vars.remove(k);}}
'''
    f["zombie/apocalipse/humans/brain/HumanActuator.java"] = r'''package zombie.apocalipse.humans.brain;public interface HumanActuator {default boolean seated(){return false;}default void stopSeating(){}
enum MotionStatus{RUNNING,SUCCEEDED,FAILED}default void stopEmote(){}default void stopMoving(){}default void tactical(TacticalIntent i){}default boolean face(HumanObservation.Identity i){return true;}
default boolean startMovement(zombie.apocalipse.humans.walking.MovementRequest r,long n){return false;}default MotionStatus updateMovement(long n){return MotionStatus.FAILED;}
default boolean say(String s){return true;}default boolean startEmote(String s){return true;}default boolean returnHome(){return true;}
default zombie.apocalipse.humans.seating.HumanSeating seating(){return zombie.apocalipse.humans.seating.HumanSeating.UNSUPPORTED;}}
'''
    f["zombie/core/raknet/UdpConnection.java"] = "package zombie.core.raknet;public class UdpConnection {public boolean relevant=true;}"
    f["zombie/ai/State.java"] = "package zombie.ai;public class State {public enum Stage{Enter,Exit,Execute}}"
    for name in ("PlayerSitOnFurnitureState", "PlayerGetUpState"):
        f[f"zombie/ai/states/{name}.java"] = f"package zombie.ai.states;public class {name} extends zombie.ai.State {{private static final {name} I=new {name}();public static {name} instance(){{return I;}}}}"
    f["zombie/network/PacketTypes.java"] = "package zombie.network;public class PacketTypes {public enum PacketType{State,VariableSync,PlayerUpdateReliable}}"
    f["zombie/network/packets/INetworkPacket.java"] = r'''package zombie.network.packets;import java.util.*;import zombie.core.raknet.*;import zombie.network.*;
public interface INetworkPacket {record Variable(UdpConnection viewer,String key,Object value){}List<Variable> variables=new ArrayList<>();static void send(UdpConnection v,PacketTypes.PacketType t,Object... args){variables.add(new Variable(v,(String)args[1],args[2]));}}
'''
    f["zombie/network/packets/actions/StatePacket.java"] = r'''package zombie.network.packets.actions;import java.util.*;import zombie.ai.*;import zombie.core.raknet.*;import zombie.network.*;
public class StatePacket {public static final List<Sent> sent=new ArrayList<>();public static class Sent {public UdpConnection viewer;public State state;public boolean substate,enter;public Map<String,Object> params;public Map<String,String> events;
public Sent(UdpConnection v,State s,boolean sub,boolean e,Map<String,Object> p,Map<String,String> ev){viewer=v;state=s;substate=sub;enter=e;params=p;events=ev;}}
protected final ID characterId=new ID();protected final S state=new S();protected boolean isSubState;protected State.Stage stage;protected final Table params=new Table();protected final Events events=new Events();
public void set(float x,float y,float z){}public static class ID{public void set(Object p){}}public static class S{State value;public void set(State v){value=v;}}
public static class Table extends HashMap<String,Object>{public void rawset(String k,Object v){put(k,v);}}public static class Events{Map<String,String> v=new HashMap<>();public Map<String,String> get(){return v;}}
public void sendToClient(PacketTypes.PacketType type,UdpConnection viewer){sent.add(new Sent(viewer,state.value,isSubState,stage==State.Stage.Enter,new HashMap<>(params),new HashMap<>(events.get())));}}
'''
    f["zombie/apocalipse/humans/ServerHumanTransport.java"] = "package zombie.apocalipse.humans;import zombie.core.raknet.*;import zombie.apocalipse.humans.furniture.FurnitureAffordances.Position;class ServerHumanTransport {static void seatingPosition(ServerHuman h,UdpConnection v,Position p){}static void movement(ServerHuman h){}}"
    f["zombie/apocalipse/humans/ServerHumanSeatingAssets.java"] = "package zombie.apocalipse.humans;class ServerHumanSeatingAssets {static boolean supported(){return true;}}"
    f["zombie/apocalipse/humans/ServerHuman.java"] = r'''package zombie.apocalipse.humans;import java.util.*;import zombie.characters.*;import zombie.iso.*;import zombie.core.raknet.*;import zombie.apocalipse.humans.brain.*;import zombie.apocalipse.humans.state.*;import zombie.apocalipse.humans.profiles.*;import zombie.apocalipse.humans.walking.*;
class ServerHuman {final Thread owner=Thread.currentThread();final IsoPlayer player;final HumanActuator actuator=new HumanActuator(){};final String incarnation=UUID.randomUUID().toString();final Set<UdpConnection> viewers=new LinkedHashSet<>();ServerHumanSeating seating;
final HumanProfile profile=new HumanProfile(List.of(WalkingPatternSpec.indoor()));record Definition(String key,int z){}final Definition definition=new Definition("npc",0);final Store state=new Store();static class Store {HumanState value=HumanState.INITIAL;HumanState snapshot(){return value;}}
final HumanActivityController activity=new HumanActivityController(new HumanBehavior(){public boolean available(){return true;}public void update(HumanObservation o,HumanActuator a){}public void suspend(long n,HumanActuator a){}public void resume(HumanObservation.Identity i,long n,HumanActuator a){}public void stop(long n,HumanActuator a){}},true,true);
final Moving movement=new Moving();final Walking walking=new Walking();ServerHuman(IsoCell c){player=new IsoPlayer(c);}void requireAuthority(){if(Thread.currentThread()!=owner)throw new IllegalStateException();}boolean isRelevant(UdpConnection v){return v.relevant;}
MOTION_GATE
class Moving {boolean traversing(){return false;}IsoGridSquare square(int x,int y){return player.cell.getGridSquare(x,y,0);}boolean bodyClear(float x,float y,float a,float b){return true;}}
class Walking {zombie.apocalipse.humans.walking.WalkingPatternSpec.Point goal;boolean start(MovementRequest r,long n){if(!motionAllowed())return false;goal=((MovementRequest.To)r).destination();return true;}
HumanActuator.MotionStatus update(long n){player.x=goal.x()+.5f;player.y=goal.y()+.5f;return HumanActuator.MotionStatus.SUCCEEDED;}void stop(){}boolean permitted(zombie.apocalipse.humans.movement.MovementGrid.Tile t){var s=movement.square(t.x(),t.y());return s!=null&&!s.solid;}}
}
'''
    native = (ROOT / "42.22.2/src/zombie/iso/IsoObject.java").read_text(encoding="utf-8")
    f["zombie/iso/IsoObject.java"] = """package zombie.iso;import zombie.iso.sprite.IsoSprite;public class IsoObject {public IsoGridSquare square;public IsoSprite sprite;private transient long apocbrLifetimeRevision;public boolean sat;
public IsoGridSquare getSquare(){return square;}public IsoSprite getSprite(){return sprite;}public void setSatChair(boolean b){sat=b;}
public Props getProperties(){return new Props();}public static class Props {public boolean has(zombie.iso.SpriteDetails.IsoFlagType f){return false;}}
""" + "\n".join(method(native, s) for s in ("public final long getApocbrLifetimeRevision()", "public void setSquare(IsoGridSquare square)", "public void removeFromSquare()")) + "}"
    actual_human = (SOURCE / "ServerHuman.java").read_text(encoding="utf-8")
    f["zombie/apocalipse/humans/ServerHuman.java"] = f["zombie/apocalipse/humans/ServerHuman.java"].replace("MOTION_GATE", method(actual_human, "boolean motionAllowed()"))
    f["zombie/apocalipse/humans/NativeSeatingTest.java"] = HARNESS
    for file, signature in (("ServerHumanWalking.java", "boolean start(MovementRequest request, long now)"), ("ServerHumanMovement.java", "boolean start(List<Tile> requested, MovementGrid policy, Pace pace, long now)")):
        body = method((SOURCE / file).read_text(encoding="utf-8"), signature)
        assert body.index("human.motionAllowed()") < body.index("stop();")
    with tempfile.TemporaryDirectory(prefix="apocbr-seating-native-") as directory:
        work = Path(directory);sources = []
        for name, content in f.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding="utf-8");sources.append(path)
        for package in ("furniture", "interactions", "seating"):
            sources.extend(sorted((SOURCE/package).glob("*.java")))
        for name in ("ServerHumanFurniture", "ServerHumanFurnitureWorld", "ServerHumanSeatingCatalog", "ServerHumanSeating", "ServerHumanSeatingGeometry", "ServerHumanSeatingPacket", "ServerHumanSeatingTransport", "ServerHumanSeatingCommands", "brain/HumanBehavior", "brain/HumanObservation", "brain/HumanSenses", "brain/HumanTerritory", "brain/TacticalIntent", "brain/HumanAction", "brain/HumanActionExecution", "brain/HumanActivityController", "state/HumanState", "walking/MovementRequest", "walking/WalkingPatternSpec", "movement/MovementGrid"):
            sources.append(SOURCE/(name+".java"))
        sources.append(ROOT/"42.22.2/src/zombie/util/list/MutationTrackedArrayList.java")
        args=work/"javac.args";args.write_text("\n".join('"'+str(p).replace("\\","/")+'"' for p in sources),encoding="utf-8")
        subprocess.run([str(ROOT/"jdk/bin/javac.exe"),"--release","25","-encoding","UTF-8","-d",str(work/"classes"),"@"+str(args)],check=True)
        subprocess.run([str(ROOT/"jdk/bin/java.exe"),"-cp",str(work/"classes"),"zombie.apocalipse.humans.NativeSeatingTest"],check=True)


if __name__ == "__main__":
    main()
