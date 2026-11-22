"""42.22.1 movement/transport with native prediction and variable codecs.

Runs complete production locomotion against explicit world/traversal doubles.
Extracts factory publication and transport methods to avoid unrelated services.
No installation, deployment, persistence, native animation or RakNet execution.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.1"
SOURCE = V / "src/zombie/apocalipse/humans"


def block(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


STUBS = {
    "zombie/network/IConnection.java": "package zombie.network;public interface IConnection {}",
    "zombie/network/JSONField.java": "package zombie.network;public @interface JSONField {}",
    "zombie/network/fields/INetworkPacketField.java": """package zombie.network.fields;
import zombie.network.*;import zombie.core.network.*;
public interface INetworkPacketField {void parse(ByteBufferReader b,IConnection c);void write(ByteBufferWriter b);
default int getPacketSizeBytes(){return 0;}default String getDescription(){return "";}}""",
    "zombie/network/packets/INetworkPacket.java": """package zombie.network.packets;
import zombie.core.raknet.*;import zombie.network.*;
public interface INetworkPacket extends zombie.network.fields.INetworkPacketField {
static void send(UdpConnection c,PacketTypes.PacketType type,Object... values){c.injuries++;}}""",
    "zombie/network/PacketTypes.java": "package zombie.network;public class PacketTypes {public enum PacketType {PlayerUpdateReliable,PlayerInjuries}}",
    "zombie/core/network/ByteBufferWriter.java": """package zombie.core.network;import java.nio.*;
public class ByteBufferWriter {public final ByteBuffer bb;public ByteBufferWriter(ByteBuffer b){bb=b;}
public void putByte(int x){bb.put((byte)x);}public void putShort(short x){bb.putShort(x);}
public void putFloat(float x){bb.putFloat(x);}public void putBoolean(boolean x){bb.put((byte)(x?1:0));}
public <E extends Enum<E>> void putEnum(E x){putByte(x.ordinal()&255);}}""",
    "zombie/core/network/ByteBufferReader.java": """package zombie.core.network;import java.nio.*;
public class ByteBufferReader {public final ByteBuffer bb;public ByteBufferReader(ByteBuffer b){bb=b;}
public byte getByte(){return bb.get();}public short getShort(){return bb.getShort();}
public float getFloat(){return bb.getFloat();}public boolean getBoolean(){return bb.get()!=0;}
public <E extends Enum<E>> E getEnum(Class<E> c){return c.getEnumConstants()[getByte()&255];}}""",
    "zombie/core/raknet/UdpConnection.java": """package zombie.core.raknet;import java.util.*;
public class UdpConnection {public boolean relevant=true;public int injuries;public List<byte[]> wire=new ArrayList<>();
public Runnable afterSend=()->{};}""",
    "zombie/iso/Vector3.java": """package zombie.iso;public class Vector3 {public float x,y,z;
public Vector3 set(float x,float y,float z){this.x=x;this.y=y;this.z=z;return this;}
public Vector3 set(Vector3 o){return set(o.x,o.y,o.z);}}""",
    "zombie/core/math/PZMath.java": "package zombie.core.math;public class PZMath {public static float lerp(float a,float b,float p){return a+(b-a)*p;}}",
    "zombie/GameTime.java": "package zombie;public class GameTime {public static final GameTime instance=new GameTime();public float delta=.1f;public float getTimeDelta(){return delta;}}",
    "zombie/iso/IsoMovingObject.java": """package zombie.iso;public class IsoMovingObject {public float x=.5f,y=.5f;
public float getX(){return x;}public float getY(){return y;}public int getID(){return 1;}}""",
    "zombie/characters/IsoGameCharacter.java": """package zombie.characters;
public class IsoGameCharacter extends zombie.iso.IsoMovingObject {public boolean isDead(){return false;}public float getHealth(){return 100;}}""",
    "zombie/characters/IsoPlayer.java": """package zombie.characters;import java.util.*;import zombie.iso.*;
public class IsoPlayer extends IsoGameCharacter {public float realx,realy,angle;public boolean grapple,performing;public boolean isPerformingAnAction(){return performing;}
public final Map<String,Float> vars=new HashMap<>();public String action="run";
public final AI ai=new AI();public final IsoCell cell=new IsoCell();public static class AI {public float walkSpeed,runSpeed;}
public AI getNetworkCharacterAI(){return ai;}public int getZi(){return 0;}public int getXi(){return (int)Math.floor(x);}
public int getYi(){return (int)Math.floor(y);}public void setX(float v){x=v;}public void setY(float v){y=v;}
public void setDirectionAngle(float degrees){angle=(float)Math.toRadians(degrees);}
public float getDirectionAngleRadians(){return angle;}public IsoCell getCell(){return cell;}
public void setRunning(boolean v){}public void setSprinting(boolean v){}public void setSneaking(boolean v){}
public boolean isDoContinueGrapple(){return grapple;}public void setVariable(String k,float v){vars.put(k,v);}
public float getVariableFloat(String k,float fallback){return vars.getOrDefault(k,fallback);}public String getActionStateName(){return action;}}""",
    "zombie/iso/IsoCell.java": """package zombie.iso;import java.util.*;public class IsoCell {
public Map<String,IsoGridSquare> squares=new HashMap<>();public IsoGridSquare getGridSquare(int x,int y,int z){
return squares.computeIfAbsent(x+","+y,k->new IsoGridSquare(x,y));}}""",
    "zombie/iso/IsoGridSquare.java": """package zombie.iso;import java.util.*;public class IsoGridSquare {
public final int x,y;public boolean free=true;public List<IsoMovingObject> bodies=new ArrayList<>();
public IsoGridSquare(int x,int y){this.x=x;this.y=y;}public boolean isFree(boolean moving){return free&&(!moving||bodies.isEmpty());}
public List<IsoMovingObject> getMovingObjects(){return bodies;}}""",
    "zombie/pathfind/PolygonalMap2.java": """package zombie.pathfind;public class PolygonalMap2 {
public static final PolygonalMap2 instance=new PolygonalMap2();public int calls;public boolean blocked;
public boolean lineClearCollide(float x,float y,float nx,float ny,int z,Object ignored,boolean a,boolean b){calls++;return blocked;}}""",
    "zombie/apocalipse/humans/brain/HumanActuator.java": "package zombie.apocalipse.humans.brain;public interface HumanActuator {enum MotionStatus {RUNNING,SUCCEEDED,FAILED}}",
    "zombie/apocalipse/humans/HumanLog.java": "package zombie.apocalipse.humans;public class HumanLog {enum Level {DEBUG}static void log(Level l,java.util.function.Supplier<String> s){}}",
    "zombie/apocalipse/humans/ServerHuman.java": """package zombie.apocalipse.humans;import java.util.*;
import zombie.characters.*;import zombie.core.raknet.*;import zombie.apocalipse.humans.movement.MovementModifiers.Animation;
class ServerHuman {final IsoPlayer player=new IsoPlayer();final Definition definition=new Definition();float postX,postY;
record Definition(){int z(){return 0;}String key(){return "fixture";}}
final List<UdpConnection> viewers=new ArrayList<>();final Map<UdpConnection,Animation> movementPresentation=new IdentityHashMap<>();
final ServerHumanMovement movement=new ServerHumanMovement(this,new zombie.apocalipse.humans.traversal.TraversalWorld());
boolean isRelevant(UdpConnection v){return v.relevant;}}""",
    "zombie/apocalipse/humans/traversal/TraversalWorld.java": "package zombie.apocalipse.humans.traversal;public class TraversalWorld {}",
    "zombie/apocalipse/humans/traversal/TraversalExecution.java": "package zombie.apocalipse.humans.traversal;public class TraversalExecution {public enum Result {RUNNING,FAILED,SUCCEEDED}}",
    "zombie/apocalipse/humans/traversal/BodyOccupancy.java": """package zombie.apocalipse.humans.traversal;import zombie.iso.*;
public class BodyOccupancy {public static boolean blocks(IsoMovingObject b,Object p){return b!=p;}
public static boolean free(IsoGridSquare s,Object p){return s.isFree(false)&&s.bodies.stream().noneMatch(b->blocks(b,p));}}""",
    "zombie/apocalipse/humans/traversal/HumanTraversal.java": """package zombie.apocalipse.humans.traversal;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;import zombie.characters.*;import java.util.function.*;import zombie.core.raknet.*;
public class HumanTraversal {public static HumanTraversal last;public boolean active;public TraversalEdge.Kind kind=TraversalEdge.Kind.WALK;
final Consumer<Boolean> presentation;final Runnable changed;
public HumanTraversal(IsoPlayer p,int z,TraversalWorld w,BiPredicate<Tile,Tile> policy,Consumer<Boolean> c,Runnable r){last=this;presentation=c;changed=r;}
public boolean active(){return active;}public void clock(long n){}public void cancel(){active=false;}public void close(){}
public void send(UdpConnection c,boolean enter){}public void announce(UdpConnection c){}public String reason(){return "fixture";}
public boolean walkable(Tile t){return true;}public TraversalEdge inspect(Tile a,Tile b){return new TraversalEdge(a,b,0,kind,null);}
public void start(TraversalEdge e,long n){active=true;presentation.accept(true);}
public TraversalExecution.Result update(long n){return TraversalExecution.Result.RUNNING;}
public void landed(){active=false;changed.run();}}""",
}

HARNESS = r"""package zombie.apocalipse.humans;
import java.util.*;import java.nio.*;import zombie.core.raknet.*;import zombie.core.network.*;
import zombie.apocalipse.humans.movement.*;import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.movement.MovementModifiers.*;import zombie.network.fields.character.*;
import zombie.characters.NetworkPlayerVariables.Flags;import zombie.apocalipse.humans.traversal.*;
import zombie.pathfind.PolygonalMap2;
public class ReplicationTest {
static int checks;static final long STEP=100_000_000L;
static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
static void near(float a,float b,String m){check(Math.abs(a-b)<.0001,m+" "+a+" != "+b);}
record Received(Prediction prediction,short flags,PlayerVariables variables){}
static Received read(byte[] bytes){var b=ByteBuffer.wrap(bytes);check(b.getShort()==768,"reserved ID retained");
var p=new Prediction();p.parse(new ByteBufferReader(b),null);short flags=b.getShort();
var variables=new PlayerVariables();variables.parse(new ByteBufferReader(b),null);
check(b.get()==0&&b.getShort()==-1&&!b.hasRemaining(),"native envelope complete");return new Received(p,flags,variables);}
static Received last(UdpConnection c){return read(c.wire.getLast());}
static boolean flag(Received r,Flags f){return (r.flags()&(1<<f.ordinal()))!=0;}
static List<Tile> straight(int count){var r=new ArrayList<Tile>();for(int x=0;x<count;x++)r.add(new Tile(x,0));return r;}
static ServerHuman actor(UdpConnection... viewers){var h=new ServerHuman();h.viewers.addAll(List.of(viewers));return h;}
static void tick(ServerHuman h,long now){var previous=h.movement.presentation();float direction=h.player.angle;
h.movement.update(now);h.movement.refresh(now);
if(ServerHumanFactory.shouldPublishMotion(now%(5*STEP)==0,direction!=h.player.angle,previous,h.movement.presentation()))ServerHumanTransport.movement(h);}
public static void main(String[] args){
var a=new UdpConnection();var b=new UdpConnection();var excluded=new UdpConnection();excluded.relevant=false;
var h=actor(a,b,excluded);h.player.grapple=true;h.movement.start(straight(20),h.movement,Pace.RUN,0);
for(int i=1;i<=9;i++){tick(h,i*STEP);check(a.wire.size()==i&&b.wire.size()==i,"constant running publishes every actual tick");
near(last(a).prediction.speed,4,"native physical run speed");check(flag(last(a),Flags.isRunning)&&flag(last(a),Flags.hasDeferredMovement),"run/moving flags");}
check(excluded.wire.isEmpty(),"irrelevant viewer excluded");check(a.injuries==1&&b.injuries==1,"injury cache not spammed at 10Hz");
check(flag(last(a),Flags.isDoContinueGrapple),"grapple continuation retained");
var replica=new zombie.characters.IsoPlayer();last(a).variables.apply(replica);
near(replica.getVariableFloat("WalkSpeed",-1),h.movement.snapshot().animation().runSpeed(),"native movement variables survive wire roundtrip");
int calls=PolygonalMap2.instance.calls;ServerHumanTransport.movement(h);int oneCapture=PolygonalMap2.instance.calls-calls;
h.viewers.remove(b);calls=PolygonalMap2.instance.calls;ServerHumanTransport.movement(h);
check(PolygonalMap2.instance.calls-calls==oneCapture&&oneCapture>0,"prediction collision work independent of viewer count");h.viewers.add(b);
var captured=h.movement.snapshot();float capturedX=captured.x();a.afterSend=()->{h.player.x+=10;};ServerHumanTransport.movement(h);
check(Arrays.equals(a.wire.getLast(),b.wire.getLast()),"all viewers receive identical captured payload even if actor changes during send");
near(last(b).prediction.x,capturedX,"snapshot coordinates retained");h.player.x=capturedX;a.afterSend=()->{};
var late=new UdpConnection();h.viewers.add(late);ServerHumanTransport.idle(h,late);
check(late.wire.size()==1&&late.injuries==1,"late viewer gets current motion and modifiers");
var previous=h.movement.presentation();h.movement.stop();
check(ServerHumanFactory.shouldPublishMotion(false,false,previous,h.movement.presentation()),"stop publishes without heartbeat");
ServerHumanTransport.movement(h);var stopped=last(a);check(!flag(stopped,Flags.hasDeferredMovement)&&!flag(stopped,Flags.isRunning),"stop clears movement flags");
check(stopped.prediction.type==0&&stopped.prediction.distance==0&&stopped.prediction.speed==0,"stop cannot continue old prediction");
int idlePackets=a.wire.size();for(int i=1;i<=5;i++){
var idle=h.movement.presentation();if(ServerHumanFactory.shouldPublishMotion(i==5,false,idle,idle))ServerHumanTransport.movement(h);}
check(a.wire.size()==idlePackets+1,"idle retains only 500ms heartbeat");
check(ServerHumanFactory.shouldPublishMotion(false,true,h.movement.presentation(),h.movement.presentation()),"idle turn still publishes");
var idle=h.movement.presentation();h.movement.conditions(new Conditions(Health.HEALTHY,
new Context(true,false,Posture.RELAXED,1,1,1,1),Effects.NONE));h.movement.refresh(STEP);
check(ServerHumanFactory.shouldPublishMotion(false,false,idle,h.movement.presentation()),"idle modifier change publishes immediately");
ServerHumanTransport.movement(h);check(flag(last(a),Flags.isSneaking),"sneak flag retained");
// One-tile route: clipped prediction expires inside a tick and then becomes static.
var shortViewer=new UdpConnection();var shortRun=actor(shortViewer);shortRun.movement.start(straight(2),shortRun.movement,Pace.RUN,0);
tick(shortRun,STEP);var clipped=last(shortViewer);check(clipped.prediction.distance==3,"headroom and eighth-tile clipping retained");
near(clipped.prediction.predictionTimeRemaining,.09375f,"native decode expires before next old heartbeat");
zombie.GameTime.instance.delta=.1f;clipped.prediction.update();float terminal=clipped.prediction.position.x;
clipped.prediction.update();near(clipped.prediction.position.x,terminal,"native prediction expires");
tick(shortRun,2*STEP);check(shortViewer.wire.size()==2&&last(shortViewer).prediction.type==0,"zero-horizon packet refreshed before heartbeat");
check(last(shortViewer).prediction.x>clipped.prediction.x,"static target follows authoritative progress");
tick(shortRun,3*STEP);check(!flag(last(shortViewer),Flags.hasDeferredMovement),"route completion sends stop immediately");
// Sprint is admitted by flight conditions, not by altering packet layout.
var s=actor(new UdpConnection());s.movement.conditions(new Conditions(Health.HEALTHY,Context.NORMAL,
new Effects(new Window(true,100*STEP),Pace.SPRINT,Window.NONE,Window.NONE,BurstAdmission.REFUSED)));
s.movement.start(straight(20),s.movement,Pace.SPRINT,0);tick(s,STEP);var sprint=last(s.viewers.getFirst());
near(sprint.prediction.speed,6,"sprint physical speed");check(flag(sprint,Flags.isSprinting)&&!flag(sprint,Flags.isRunning),"sprint native flags");
var conditions=new Conditions(Health.HEALTHY,new Context(false,false,Posture.RELAXED,1,1,.5f,1),Effects.NONE);
s.movement.conditions(conditions);tick(s,2*STEP);check(s.viewers.getFirst().injuries==2,"modifier change updates injury cache");
// Guarded movement can change travel heading while facing a fixed direction.
var g=actor(new UdpConnection());g.movement.tactical(.5f,10f,false);
g.movement.start(List.of(new Tile(0,0),new Tile(1,0),new Tile(1,1)),g.movement,Pace.WALK,0);tick(g,STEP);
var guarded=last(g.viewers.getFirst());near(guarded.prediction.direction,(float)Math.atan2(10-g.player.y,.5f-g.player.x),"guard faces target");
near(guarded.prediction.moveDirection,0,"prediction uses travel heading independent of facing");
var before=g.movement.presentation();
var newHeading=new ServerHumanMovement.Presentation(before.gait(),before.speed(),(float)Math.PI/2,before.animation());
check(!before.equals(newHeading),"travel-heading change is visible even with unchanged gait/modifiers");
// Real corner is not extrapolated through, even at running speed.
var c=actor(new UdpConnection());c.movement.start(List.of(new Tile(0,0),new Tile(1,0),new Tile(1,1),new Tile(1,2)),c.movement,Pace.RUN,0);
tick(c,STEP);var corner=last(c.viewers.getFirst());check(corner.prediction.distance<=3,"prediction clipped before corner");
tick(c,2*STEP);tick(c,3*STEP);check(last(c.viewers.getFirst()).prediction.moveDirection>1,"new segment heading published immediately");
var slow=actor(new UdpConnection());slow.movement.start(straight(20),slow.movement,Pace.RUN,0);tick(slow,STEP);
float slowX=slow.player.x;int slowPackets=slow.viewers.getFirst().wire.size();tick(slow,20*STEP);
near(slow.player.x-slowX,.6f,"late simulation retains 150ms integration cap");
check(slow.viewers.getFirst().wire.size()==slowPackets+1,"late simulation sends one fresh update without catch-up burst");
// Traversal callbacks broadcast once and suppress ordinary extrapolation.
var t=actor(new UdpConnection(),new UdpConnection());var traversal=HumanTraversal.last;
t.movement.start(straight(3),t.movement,Pace.RUN,0);traversal.kind=TraversalEdge.Kind.FENCE;t.movement.update(STEP);
check(t.viewers.getFirst().wire.size()==1&&last(t.viewers.getFirst()).prediction.type==0,"traversal entry sends static motion");
traversal.landed();check(t.viewers.getFirst().wire.size()==2,"landing callback broadcasts");
// Blocked prediction never bypasses collision; removal has no queued snapshots.
check(s.movement.snapshot().predictionUnits()>0,"running actor has prediction before new obstacle");
PolygonalMap2.instance.blocked=true;ServerHumanTransport.movement(s);
check(last(s.viewers.getFirst()).prediction.type==0,"new obstacle suppresses extrapolation without extending unsafe horizon");
var recoveryHealth=new Health(.7f,1,0,false,false,false,true);
var recoveryMotion=MovementModifiers.resolve(Pace.RUN,new Conditions(recoveryHealth,Context.NORMAL,Effects.NONE),Settings.DEFAULT,0,0);
check(recoveryMotion.pace()==Pace.WALK&&recoveryMotion.speed()<2&&recoveryMotion.animation().walkInjury()==.5f,"recovering state requests slowed native light-limp walking after bandaging");
var leftInjury=MovementModifiers.resolve(Pace.RUN,new Conditions(new Health(.7f,1,-.8f,false,false,false,true),Context.NORMAL,Effects.NONE),Settings.DEFAULT,0,0);
check(leftInjury.animation().walkInjury()==-.8f,"recovery preserves actual signed leg injury instead of replacing its side");
// Neighbor tile overlap can block recentering even when the origin has no bodies.
PolygonalMap2.instance.blocked=false;
var overlap=actor();overlap.player.x=2.604f;overlap.player.y=2.5f;
var body=new zombie.characters.IsoGameCharacter();body.x=1.97f;body.y=2.5f;
overlap.player.cell.getGridSquare(1,2,0).bodies.add(body);
overlap.movement.tactical(body.x,body.y,true);
check(overlap.movement.start(List.of(new Tile(2,2),new Tile(3,2)),overlap.movement,Pace.RUN,0),"admit escape from neighboring-body overlap");
check(overlap.movement.nextTile().equals(new Tile(3,2)),"unsafe centering is skipped even with empty origin moving-object list");
var status=overlap.movement.update(STEP);check(status==zombie.apocalipse.humans.brain.HumanActuator.MotionStatus.RUNNING&&overlap.player.x>2.604f,"escape separates outward while retaining body clearance; status="+status+" x="+overlap.player.x+" failure="+overlap.movement.lastFailure());
var medical=actor(new UdpConnection());medical.player.performing=true;ServerHumanTransport.movement(medical);
check(flag(last(medical.viewers.getFirst()),Flags.isPerformingAction),"movement preserves active bandage action");
medical.player.performing=false;ServerHumanTransport.movement(medical);
check(!flag(last(medical.viewers.getFirst()),Flags.isPerformingAction),"movement clears action after completion/cancellation");
h.viewers.clear();ServerHumanTransport.movement(h);h.movement.close();
System.out.println("42.22.1 NPC movement replication: "+checks+" checks passed");
}}
"""


def main():
    files = dict(STUBS)
    transport = (SOURCE / "ServerHumanTransport.java").read_text(encoding="utf-8")
    methods = [block(transport, signature) for signature in (
        "static void idle(", "static void movement(", "private static PlayerPacketReliable movementPacket(",
        "private static void movementPresentation(")]
    files["zombie/apocalipse/humans/ServerHumanTransport.java"] = """package zombie.apocalipse.humans;
import zombie.core.raknet.*;import zombie.characters.NetworkPlayerVariables;import zombie.network.*;
import zombie.network.packets.*;import zombie.network.packets.character.*;
class ServerHumanTransport {static void aim(ServerHuman h,boolean b){}\n""" + "\n".join(methods) + "}"
    factory = (SOURCE / "ServerHumanFactory.java").read_text(encoding="utf-8")
    # Fail if the fixture seam stops being the real factory publication decision.
    assert "if (shouldPublishMotion(heartbeat, turned, previousMotion, human.movement.presentation()))" in factory
    assert "ServerHumanTransport.movement(human);" in factory
    files["zombie/apocalipse/humans/ServerHumanFactory.java"] = (
        "package zombie.apocalipse.humans;class ServerHumanFactory {"
        + block(factory, "static boolean shouldPublishMotion(") + "}")
    native = V / "decompiled/zombie"
    flags = block((native / "characters/NetworkPlayerVariables.java").read_text(encoding="utf-8"), "public static enum Flags")
    files["zombie/characters/NetworkPlayerVariables.java"] = (
        "package zombie.characters;public class NetworkPlayerVariables {interface OrdinalShortFlag {default void assertOrdinalValid(){}}"
        + flags + "}")
    envelope = block((native / "network/packets/character/PlayerPacket.java").read_text(encoding="utf-8"), "public void write(ByteBufferWriter b)")
    files["zombie/network/packets/character/PlayerPacketReliable.java"] = """package zombie.network.packets.character;
import java.nio.*;import zombie.network.*;import zombie.network.fields.character.*;import zombie.core.network.*;
import zombie.characters.*;import zombie.core.raknet.*;
public class PlayerPacketReliable {
public final PlayerField player=new PlayerField();public final Prediction prediction=new Prediction();
public short booleanVariables;public boolean disconnected;public final Vehicle hitVehicleId=new Vehicle();
public final PlayerVariables variables=new PlayerVariables();
public static class PlayerField {public void set(IsoPlayer p){}public void write(ByteBufferWriter b){b.putShort((short)768);}}
public static class Vehicle {public void set(Object v){}public void write(ByteBufferWriter b){b.putShort((short)-1);}}
public void sendToClient(PacketTypes.PacketType type,UdpConnection c){var b=ByteBuffer.allocate(128);write(new ByteBufferWriter(b));
c.wire.add(java.util.Arrays.copyOf(b.array(),b.position()));c.afterSend.run();}
""" + envelope + "}"
    files["zombie/apocalipse/humans/ReplicationTest.java"] = HARNESS
    production = [SOURCE / "ServerHumanMovement.java", SOURCE / "movement/MovementGrid.java",
                  SOURCE / "movement/MovementModifiers.java", SOURCE / "traversal/TraversalEdge.java",
                  native / "network/fields/character/Prediction.java", native / "network/fields/character/PlayerVariables.java"]
    with tempfile.TemporaryDirectory(prefix="npc_movement_42_22_1_") as folder:
        tmp = Path(folder)
        sources = []
        for name, content in files.items():
            path = tmp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        args = tmp / "sources.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources + production), encoding="utf-8")
        output = tmp / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.humans.ReplicationTest"], check=True)


if __name__ == "__main__":
    main()
