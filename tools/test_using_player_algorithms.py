"""Differential active-user traversal against the exact vanilla update body.
Production EntityBucket/index/system are compiled; GameEntity mutation methods
are extracted from production into a small fixture instead of stubbing their logic.
"""
from pathlib import Path
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from test_simulation_algorithms import ROOT, STUBS, method


def fixtures():
    files = dict(STUBS)
    production = (ROOT / "42.21.0/src/zombie/entity/GameEntity.java").read_text()
    entity = files["zombie/entity/GameEntity.java"]
    entity = entity.replace("public void setUsingPlayer(zombie.characters.IsoPlayer p){usingPlayer=p;ServerUsingPlayerIndex.changed(this);}", "")
    entity = entity.replace("public void reset(){drying=false;}", "")
    entity = entity.replace("private zombie.characters.IsoPlayer usingPlayer;", "private zombie.characters.IsoPlayer usingPlayer; public boolean addedToEntityManager, scheduledDelayedAddToEngine, scheduledForBucketUpdate, addedToWorldOrEquipped; private Comp components; static class Comp {void release(){}} public int sends; void sendUpdateUsingPlayer(){sends++;} ")
    entity = entity.replace("package zombie.entity;", "package zombie.entity; import zombie.characters.IsoPlayer; import zombie.network.GameClient; import zombie.network.GameServer; import zombie.network.IConnection; import zombie.core.network.ByteBufferReader; import zombie.core.Core;")
    entity = entity.rsplit("}", 1)[0] + "\n" + "\n".join(method(production, signature) for signature in (
        "public void setUsingPlayer(IsoPlayer player)", "public void reset()",
        "protected final void receiveUpdateUsingPlayer(ByteBufferReader input, IConnection senderConnection)")) + "\n}"
    files["zombie/entity/GameEntity.java"] = entity
    files["zombie/entity/GameEntityType.java"] = files["zombie/entity/GameEntityType.java"].replace("MetaEntity;", "MetaEntity, IsoObject;", 1)
    files["zombie/entity/GameEntityManager.java"] = "package zombie.entity; public class GameEntityManager {public static boolean debugMode, processing; public static java.util.function.Consumer<GameEntity> unregister=e->{};public static boolean isEngineProcessing(){return processing;}public static void UnregisterEntity(GameEntity e){unregister.accept(e);}}"
    files["zombie/entity/EngineSystem.java"] = "package zombie.entity; public class EngineSystem {public EngineSystem(boolean u,boolean s,int p){}public void addedToEngine(Engine e){}public void update(){}}"
    files["zombie/entity/Engine.java"] = "package zombie.entity; public class Engine {public EntityBucket getIsoObjectBucket(){return null;}}"
    files["zombie/network/GameClient.java"] = "package zombie.network; public class GameClient {public static boolean client;public static final GameClient instance=new GameClient();public static zombie.characters.IsoPlayer player;public zombie.characters.IsoPlayer getPlayerByOnlineID(short id){return player;}}"
    files["zombie/network/GameServer.java"] = files["zombie/network/GameServer.java"].replace("public static boolean server=true;", "public static boolean server=true;public static zombie.characters.IsoPlayer player;public static zombie.characters.IsoPlayer getPlayerByUserName(String n){return player;}")
    files["zombie/network/IConnection.java"] = "package zombie.network; public interface IConnection {}"
    files["zombie/core/network/ByteBufferReader.java"] = "package zombie.core.network; public class ByteBufferReader {private final boolean assigned;public ByteBufferReader(boolean a){assigned=a;}public boolean getBoolean(){return assigned;}public String getUTF(){return \"user\";}public short getShort(){return 0;}}"
    files["zombie/characters/IsoPlayer.java"] = files["zombie/characters/IsoPlayer.java"].replace("public float x,y,dist;", "public float x,y,z,dist;public float getZ(){return z;}")
    vanilla = (ROOT / "42.21.0/decompiled/zombie/entity/UsingPlayerUpdateSystem.java").read_text().replace("UsingPlayerUpdateSystem", "VanillaUsingPlayerUpdateSystem")
    files["zombie/entity/VanillaUsingPlayerUpdateSystem.java"] = vanilla
    files["zombie/entity/UsingPlayerIndexTest.java"] = HARNESS
    return files


HARNESS = r"""package zombie.entity;
import java.util.*; import zombie.characters.IsoPlayer; import zombie.network.GameServer; import zombie.network.GameClient; import zombie.core.network.ByteBufferReader;
public class UsingPlayerIndexTest {
 static int assertions; static void check(boolean v,String why){assertions++;if(!v)throw new AssertionError(why);}
 static class Entity extends zombie.iso.IsoObject {
  int id,validations; float x,y,z; Runnable onX; java.util.List<Integer> clearOrder; Entity(int id){this.id=id;}
  @Override public void setUsingPlayer(IsoPlayer p){if(p==null && getUsingPlayer()!=null && clearOrder!=null)clearOrder.add(id);super.setUsingPlayer(p);}
  @Override public float getX(){if(onX!=null){Runnable action=onX;onX=null;action.run();}return x;}
  @Override public float getY(){return y;} @Override public float getZ(){return z;}
  @Override public GameEntityType getGameEntityType(){return GameEntityType.IsoObject;}
 }
 static class Custom extends Entity {
  IsoPlayer external; Custom(int id){super(id);}
  @Override public IsoPlayer getUsingPlayer(){return external;}
  @Override public void setUsingPlayer(IsoPlayer p){external=p;}
 }
 static class CustomValidity extends Entity {
  int checks; CustomValidity(int id){super(id);}
  @Override public boolean isValidEngineEntity(){checks++;return super.isValidEngineEntity();}
 }
 static class World {
  final EntityBucket bucket=new EntityBucket.IsoObjectBucket(0);
  final UsingPlayerUpdateSystem optimized=new UsingPlayerUpdateSystem(0);
  final VanillaUsingPlayerUpdateSystem vanilla=new VanillaUsingPlayerUpdateSystem(0);
  final ArrayList<Entity> entities=new ArrayList<>(); final ArrayList<Integer> clearOrder=new ArrayList<>();
  World(){optimized.isoEntities=bucket;vanilla.isoEntities=bucket;}
  Entity add(int id){Entity e=new Entity(id);e.clearOrder=clearOrder;entities.add(e);bucket.updateMembership(e);return e;}
  void remove(Entity e){e.removingFromEngine=true;bucket.updateMembership(e);e.removingFromEngine=false;e.addedToEngine=false;}
  void update(boolean index){if(index)optimized.update();else vanilla.update();}
 }
 static IsoPlayer player(float x,float y,float z,boolean dead){IsoPlayer p=new IsoPlayer();p.x=x;p.y=y;p.z=z;p.dead=dead;return p;}
 static void compare(World a,World b){check(a.entities.size()==b.entities.size(),"entity count");check(a.clearOrder.equals(b.clearOrder),"clear callback/packet order");for(int i=0;i<a.entities.size();i++){Entity x=a.entities.get(i),y=b.entities.get(i);check((x.getUsingPlayer()==null)==(y.getUsingPlayer()==null),"assignment diverged at "+i);check(x.sends==y.sends,"packet send count/order behavior at "+i);}}
 static void differential(){
  Random random=new Random(724681);World a=new World(),b=new World();
  for(int i=0;i<400;i++){a.add(i);b.add(i);}
  for(int pass=0;pass<120;pass++){
   for(int j=0;j<30;j++){
    int i=random.nextInt(a.entities.size());Entity x=a.entities.get(i),y=b.entities.get(i);int action=random.nextInt(7);
    if(action==0){x.setUsingPlayer(null);y.setUsingPlayer(null);}
    else if(action==1){float px=random.nextInt(31)-15,py=random.nextInt(31)-15,pz=random.nextInt(3);boolean dead=random.nextBoolean();x.setUsingPlayer(player(px,py,pz,dead));y.setUsingPlayer(player(px,py,pz,dead));}
    else if(action==2){x.scheduledForEngineRemoval=y.scheduledForEngineRemoval=random.nextBoolean();}
    else if(action==3){x.x=y.x=random.nextInt(20);x.y=y.y=random.nextInt(20);x.z=y.z=random.nextInt(3);}
    else if(action==4 && x.addedToEngine){a.remove(x);b.remove(y);}
    else if(action==5 && !x.addedToEngine){x.addedToEngine=y.addedToEngine=true;a.bucket.updateMembership(x);b.bucket.updateMembership(y);}
    else if(action==6){a.add(a.entities.size());b.add(b.entities.size());}
   }
   a.update(false);b.update(true);compare(a,b);
  }
 }
 static void boundaries(){
  for(float distance:new float[]{-10.001f,-10,-9.999f,0,9.999f,10,10.001f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})
   for(boolean dead:new boolean[]{false,true})for(float z:new float[]{0,1,Float.NaN}){
    World a=new World(),b=new World();Entity x=a.add(0),y=b.add(0);x.setUsingPlayer(player(distance,0,z,dead));y.setUsingPlayer(player(distance,0,z,dead));a.update(false);b.update(true);compare(a,b);
   }
 }
 static void callbacks(){
  for(int target:new int[]{0,2}){
   World a=new World(),b=new World();for(int i=0;i<4;i++){a.add(i);b.add(i);}a.bucket.getUsingPlayerIndex();b.bucket.getUsingPlayerIndex();
   Entity ax=a.entities.get(1),bx=b.entities.get(1);ax.setUsingPlayer(player(100,0,0,false));bx.setUsingPlayer(player(100,0,0,false));
   ax.onX=()->a.entities.get(target).setUsingPlayer(player(100,0,0,false));bx.onX=()->b.entities.get(target).setUsingPlayer(player(100,0,0,false));
   a.update(false);b.update(true);compare(a,b);a.update(false);b.update(true);compare(a,b);
  }
  // Live swap-removal must skip a tail entry swapped into the already-visited slot.
  World a=new World(),b=new World();for(int i=0;i<4;i++){a.add(i);b.add(i);a.entities.get(i).setUsingPlayer(player(100,0,0,false));b.entities.get(i).setUsingPlayer(player(100,0,0,false));}
  a.entities.get(0).onX=()->a.remove(a.entities.get(0));b.entities.get(0).onX=()->b.remove(b.entities.get(0));a.update(false);b.update(true);compare(a,b);
 }
 static void lifecycle(){
  World w=new World();Entity e=w.add(0);w.bucket.getUsingPlayerIndex();IsoPlayer first=player(0,0,0,false),second=player(0,0,0,false);
  GameServer.player=first;e.receiveUpdateUsingPlayer(new ByteBufferReader(true),null);check(w.bucket.getUsingPlayerIndex().nextAfter(-1).entity==e,"packet assignment indexed");
  GameServer.player=second;e.receiveUpdateUsingPlayer(new ByteBufferReader(true),null);check(e.getUsingPlayer()==first,"existing user not replaced by packet");
  e.receiveUpdateUsingPlayer(new ByteBufferReader(false),null);check(w.bucket.getUsingPlayerIndex().nextAfter(-1)==null,"packet clear indexed");
  e.setUsingPlayer(first);GameEntityManager.unregister=value->w.remove((Entity)value);e.reset();check(((GameEntity)e).usingPlayerMemberships==null,"reset/unregister releases owner linkage");check(e.getUsingPlayer()==null,"reset clears user");
  e.addedToEngine=true;w.bucket.updateMembership(e);e.setUsingPlayer(second);check(w.bucket.getUsingPlayerIndex().nextAfter(-1).entity==e,"pooled reentry indexed");
  w.remove(e);check(((GameEntity)e).usingPlayerMemberships==null,"removal releases member");e.setUsingPlayer(first);check(w.bucket.getUsingPlayerIndex().nextAfter(-1)==null,"unregistered assignment not scanned");
  World client=new World();Entity ce=client.add(0);ce.setUsingPlayer(player(100,0,0,false));GameClient.client=true;client.update(true);check(ce.getUsingPlayer()!=null,"client remains no-op");GameClient.client=false;
  GameServer.server=false;client.update(true);check(ce.getUsingPlayer()==null,"single player exhaustive cleanup retained");GameServer.server=true;
 }
 static void sharedIndices(){
  World a=new World(),b=new World();for(int i=0;i<12;i++){a.add(i);b.add(i);a.entities.get(i).setUsingPlayer(player(100,0,0,false));b.entities.get(i).setUsingPlayer(player(100,0,0,false));}
  a.bucket.getSimulationEntities();b.bucket.getSimulationEntities();a.bucket.getUsingPlayerIndex();b.bucket.getUsingPlayerIndex();
  a.remove(a.entities.get(3));b.remove(b.entities.get(3));a.remove(a.entities.get(7));b.remove(b.entities.get(7));a.update(false);b.update(true);compare(a,b);
  World w=new World();Entity e=w.add(0);EntityBucket other=new EntityBucket.IsoObjectBucket(1);other.updateMembership(e);w.bucket.getUsingPlayerIndex();other.getUsingPlayerIndex();e.setUsingPlayer(player(0,0,0,false));
  check(w.bucket.getUsingPlayerIndex().nextAfter(-1).entity==e && other.getUsingPlayerIndex().nextAfter(-1).entity==e,"assignment notifies each owning bucket");
  e.removingFromEngine=true;w.bucket.updateMembership(e);e.removingFromEngine=false;e.setUsingPlayer(null);check(other.getUsingPlayerIndex().nextAfter(-1)==null,"unlink retains other owner's notifications");
  e.removingFromEngine=true;other.updateMembership(e);check(((GameEntity)e).usingPlayerMemberships==null,"last owner unlinked");
 }
 static void failures(){
  World a=new World(),b=new World();Entity x=a.add(0),y=b.add(0);x.setUsingPlayer(player(100,0,0,false));y.setUsingPlayer(player(100,0,0,false));
  x.onX=()->{throw new IllegalStateException("fixture");};y.onX=()->{throw new IllegalStateException("fixture");};
  boolean oldThrows=false,newThrows=false;try{a.update(false);}catch(IllegalStateException ex){oldThrows=true;}try{b.update(true);}catch(IllegalStateException ex){newThrows=true;}check(oldThrows && newThrows,"callback exception propagation unchanged");compare(a,b);a.update(false);b.update(true);compare(a,b);
 }
 static void overrides(){
  World w=new World();Custom e=new Custom(0);w.bucket.updateMembership(e);w.bucket.getUsingPlayerIndex();e.external=player(100,0,0,false);w.update(true);check(e.external==null,"override without setter notifications remains checked");
  CustomValidity v=new CustomValidity(1);w.bucket.updateMembership(v);w.update(true);check(v.checks==1,"custom validity check side effects retained for idle entity");
 }
 static void scale(){
  World w=new World();for(int i=0;i<30000;i++)w.add(i);w.bucket.getUsingPlayerIndex();
  for(int i=0;i<35;i++)w.entities.get(i*700).setUsingPlayer(player(0,0,0,false));
  long before=zombie.ApocBRServerTelemetryLite.counts.getOrDefault("entities.usingPlayer.candidatesVisited",0L);
  for(int i=0;i<300;i++)w.update(true);
  check(zombie.ApocBRServerTelemetryLite.counts.get("entities.usingPlayer.candidatesVisited")-before==35L*300,"30k members / 35 active: only 10,500 visits across 300 frames");
  for(Entity e:w.entities)w.remove(e);check(w.bucket.getUsingPlayerIndex().nextAfter(-1)==null,"all active members released");for(Entity e:w.entities)check(((GameEntity)e).usingPlayerMemberships==null,"all owner links released");
 }
 public static void main(String[] args){differential();boundaries();callbacks();lifecycle();sharedIndices();failures();overrides();scale();System.out.println("Passed "+assertions+" using-player index assertions.");}
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-using-player-tests-") as directory:
        work=Path(directory);sources=[]
        for relative,content in fixtures().items():
            path=work/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding="utf-8");sources.append(path)
        src=ROOT/"42.21.0/src/zombie"
        sources.extend(src/name for name in ["entity/EntityBucket.java", "entity/ServerUsingPlayerIndex.java",
            "entity/UsingPlayerUpdateSystem.java", "entity/MetaEntity.java", "entity/EntitySimulation.java",
            "entity/MetaSimulationThrottle.java", "entity/ServerEntitySimulationIndex.java"])
        args=work/"javac.args";args.write_text("\n".join('"'+str(p).replace("\\","/")+'"' for p in sources),encoding="utf-8")
        output=work/"classes"
        subprocess.run([str(ROOT/"jdk/bin/javac.exe"),"--release","25","-d",str(output),"@"+str(args)],check=True)
        subprocess.run([str(ROOT/"jdk/bin/java.exe"),"-cp",str(output),"zombie.entity.UsingPlayerIndexTest"],check=True)

if __name__ == "__main__":
    main()
