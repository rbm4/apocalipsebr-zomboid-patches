"""Run production ownership/cadence/coverage code against isolated engine fixtures."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "zombie/network/IConnection.java": "package zombie.network; public interface IConnection {}",
    "zombie/ApocBRServerTelemetryLite.java": "package zombie; public class ApocBRServerTelemetryLite { public static final java.util.Map<String,Long> counts=new java.util.HashMap<>(); public static void count(String n,long v){counts.merge(n,v,Long::sum);} public static void recordPhase(String n,long v){} }",
    "zombie/ai/State.java": "package zombie.ai; public class State {}",
    "zombie/core/Core.java": "package zombie.core; public class Core { public static boolean debug; }",
    "zombie/core/math/PZMath.java": "package zombie.core.math; public class PZMath { public static int fastfloor(float f){return (int)Math.floor(f);} }",
    "zombie/debug/DebugLog.java": "package zombie.debug; public class DebugLog { public static void log(String s){} public static void log(Object o,String s){} }",
    "zombie/debug/DebugType.java": "package zombie.debug; public class DebugType { public static final DebugType General=new DebugType(), Multiplayer=new DebugType(); public void error(String s){} }",
    "zombie/iso/IsoChunkMap.java": "package zombie.iso; public class IsoChunkMap { public static int chunkGridWidth=8; }",
    "zombie/iso/IsoUtils.java": "package zombie.iso; public class IsoUtils { public static float DistanceToSquared(float x,float y,float a,float b){return (x-a)*(x-a)+(y-b)*(y-b);} }",
    "zombie/iso/IsoCell.java": "package zombie.iso; public class IsoCell { public final java.util.ArrayList<zombie.characters.IsoZombie> zombies=new java.util.ArrayList<>(); public final java.util.Set<zombie.characters.IsoZombie> objects=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); public java.util.ArrayList<zombie.characters.IsoZombie> getZombieList(){return zombies;} public java.util.Set<zombie.characters.IsoZombie> getObjectList(){return objects;} }",
    "zombie/iso/IsoWorld.java": "package zombie.iso; public class IsoWorld { public static final IsoWorld instance=new IsoWorld(); public IsoCell currentCell=new IsoCell(); }",
    "zombie/network/GameServer.java": """
package zombie.network;
import zombie.characters.*; import zombie.core.raknet.UdpConnection;
public class GameServer {
    public static boolean server=true;
    public static class Engine { public final java.util.ArrayList<UdpConnection> connections=new java.util.ArrayList<>(); }
    public static final Engine udpEngine=new Engine();
    public static UdpConnection getConnectionFromPlayer(IsoPlayer p){for(var c:udpEngine.connections)for(var q:c.players)if(q==p)return c;return null;}
    public static boolean isDelayedDisconnect(UdpConnection c){return c.delayed;}
    public static int getPlayerCount(){return udpEngine.connections.size();}
}
""",
    "zombie/network/ServerOptions.java": "package zombie.network; public class ServerOptions { public static final ServerOptions instance=new ServerOptions(); public static ServerOptions getInstance(){return instance;} public static class Option { public boolean value; public boolean getValue(){return value;} } public final Option switchZombiesOwnershipEachUpdate=new Option(); }",
    "zombie/network/NetworkVariables.java": "package zombie.network; public class NetworkVariables { public enum ZombieState { OnGround, Idle } }",
    "zombie/network/packets/character/ZombieListPacket.java": "package zombie.network.packets.character; public class ZombieListPacket { public final java.util.ArrayList<Short> zombiesAuth=new java.util.ArrayList<>(); }",
    "zombie/util/hash/PZHash.java": "package zombie.util.hash; public class PZHash { public static int fnv_32_init(){return 17;} public static int fnv_32_hash(int h,short v){return h*31+v;} }",
    "zombie/popman/NetworkZombiePacker.java": "package zombie.popman; public class NetworkZombiePacker { private static final NetworkZombiePacker i=new NetworkZombiePacker(); public static NetworkZombiePacker getInstance(){return i;} public void setExtraUpdate(){} public void deleteZombie(zombie.characters.IsoZombie z){} }",
    "zombie/vehicles/BaseVehicle.java": "package zombie.vehicles; public class BaseVehicle { public float getSpeed2D(){return 0;} public Object getDriver(){return null;} }",
    "zombie/characters/IsoPlayer.java": """
package zombie.characters;
public class IsoPlayer {
    public float x,y; public boolean alive=true; public IsoZombie reanimatedCorpse;
    public float getX(){return x;} public float getY(){return y;} public boolean isAlive(){return alive;} public boolean isDead(){return !alive;}
    public short getOnlineID(){return 1;} public zombie.vehicles.BaseVehicle getVehicle(){return null;}
    public float getRelevantAndDistance(float x,float y,float range){return Math.abs(this.x-x)<=range*8 && Math.abs(this.y-y)<=range*8 ? (float)Math.hypot(this.x-x,this.y-y) : Float.POSITIVE_INFINITY;}
}
""",
    "zombie/characters/IsoZombie.java": """
package zombie.characters;
import zombie.core.raknet.UdpConnection;
public class IsoZombie {
    public float x,y; public int id, dies; public short onlineId=1; public long lastChangeOwner;
    public IsoPlayer target,ownerPlayer; public UdpConnection owner; public boolean dead,reanimated;
    public zombie.network.NetworkVariables.ZombieState realState=zombie.network.NetworkVariables.ZombieState.Idle;
    public static int equalsCalls;
    public static class Grapple { public Object other; public Object getGrappledBy(){return other;} }
    public final Grapple grapple=new Grapple(); public Grapple getWrappedGrappleable(){return grapple;}
    public int getID(){return id;} public float getX(){return x;} public float getY(){return y;} public float getZ(){return 0;}
    public short getOnlineID(){return onlineId;} public UdpConnection getOwner(){return owner;} public void setOwner(UdpConnection c){owner=c;}
    public IsoPlayer getOwnerPlayer(){return ownerPlayer;} public void setOwnerPlayer(IsoPlayer p){ownerPlayer=p;}
    public void setTarget(IsoPlayer p){target=p;} public boolean isRemoteZombie(){return false;} public boolean isDead(){return dead;}
    public zombie.ai.State getCurrentState(){return null;} public boolean isReanimatedPlayer(){return reanimated;}
    public static class AI { public void resetSpeedLimiter(){} } public final AI ai=new AI(); public AI getNetworkCharacterAI(){return ai;}
    public void die(){dies++;} public void removeFromWorld(){} public void removeFromSquare(){}
    @Override public boolean equals(Object o){equalsCalls++;return this==o;} @Override public int hashCode(){return System.identityHashCode(this);}
}
""",
    "zombie/core/raknet/UdpConnection.java": """
package zombie.core.raknet;
import zombie.characters.IsoPlayer;
public class UdpConnection implements zombie.network.IConnection {
    public boolean connected=true,delayed; public int range=10;
    public final IsoPlayer[] players=new IsoPlayer[4];
    public static class Timer { public void reset(long t){} } public final Timer timerSendZombie=new Timer();
    public boolean isFullyConnected(){return connected;} public int getRelevantRange(){return range;}
    public float getRelevantAndDistance(float x,float y,float z){for(var p:players)if(p!=null){float d=p.getRelevantAndDistance(x,y,range);if(!Float.isInfinite(d))return d;}return Float.POSITIVE_INFINITY;}
    public boolean RelevantTo(float x,float y,float radius){for(var p:players)if(p!=null&&Math.abs(p.x-x)<=radius&&Math.abs(p.y-y)<=radius)return true;return false;}
}
""",
}
for state in ["GenericDefaultState", "ZombieEatBodyState", "ZombieIdleState", "ZombieSittingState", "ZombieTurnAlerted"]:
    STUBS[f"zombie/ai/states/{state}.java"] = f"package zombie.ai.states; public class {state} extends zombie.ai.State {{ private static final {state} i=new {state}(); public static {state} instance(){{return i;}} }}"

HARNESS = r"""
package zombie.popman;
import java.util.*;
import zombie.ApocBRServerTelemetryLite;
import zombie.characters.*;
import zombie.core.raknet.UdpConnection;
import zombie.network.*;
import zombie.network.packets.character.ZombieListPacket;
import zombie.iso.*;
public class ZombieAuthAlgorithmsTest {
    static int assertions;
    static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    static long count(String key){return ApocBRServerTelemetryLite.counts.getOrDefault(key,0L);}
    static UdpConnection connection(float x,float y){var c=new UdpConnection();var p=new IsoPlayer();p.x=x;p.y=y;c.players[0]=p;GameServer.udpEngine.connections.add(c);return c;}
    static IsoZombie zombie(int id){var z=new IsoZombie();z.id=id;z.onlineId=(short)id;IsoWorld.instance.currentCell.objects.add(z);IsoWorld.instance.currentCell.zombies.add(z);return z;}
    static void reset(){GameServer.udpEngine.connections.clear();IsoWorld.instance.currentCell=new IsoCell();ServerOptions.instance.switchZombiesOwnershipEachUpdate.value=false;}
    static void ownership(){
        var set=new ZombieOwnershipIndex.OrderedIdentitySet();var objects=new ArrayList<IsoZombie>();
        for(int i=0;i<30000;i++){var z=new IsoZombie();objects.add(z);set.add(z);}
        IsoZombie.equalsCalls=0;
        for(int i=0;i<1000;i++)check(set.remove(objects.get(i*29)),"indexed removal succeeds");
        check(IsoZombie.equalsCalls==0,"30k ownership group removals perform zero equality searches");
        check(set.size()==29000,"exact survivors");
        var expected=new ArrayList<IsoZombie>();for(int i=0;i<objects.size();i++)if(i%29!=0||i/29>=1000)expected.add(objects.get(i));
        check(new ArrayList<>(set).equals(expected),"ownership iteration retains insertion order");
        var first=set.iterator().next();check(!set.add(first),"duplicate ownership is rejected");
        set.removeIf(z->z==first);check(!set.contains(first),"predicate removal updates identity index");
        var it=set.iterator();it.next();it.remove();check(set.size()==28998,"iterator removal updates index");
        set.clear();check(set.isEmpty(),"clear releases nodes");
    }
    static void coverage(){
        reset();var a=connection(-128,-128);var b=connection(-128,-128);var grid=new ZombieAuthCoverage();grid.refresh();
        var first=grid.candidates(-128,-128);var candidate=first.get(0);long changes=count("zombies.auth.coverageChanged");
        for(int i=0;i<20;i++)grid.refresh();
        check(grid.candidates(-128,-128)==first&&first.get(0)==candidate,"stable grid reuses bucket and candidates");
        check(count("zombies.auth.coverageChanged")==changes,"stable coverage causes no recomputation");
        a.players[0].x+=1;grid.refresh();check(first.get(0)==candidate,"in-cell movement retains candidate");
        a.players[0].x=500;grid.refresh();check(grid.candidates(-128,-128).size()==1,"old coverage unlinked");
        check(grid.candidates(500,-128).get(0).connection==a,"new coverage registered");
        a.range=3;grid.refresh();check(grid.candidates(435,-128).isEmpty(),"relevance range shrink removes coverage");
        a.players[0].alive=false;grid.refresh();check(grid.candidates(500,-128).isEmpty(),"dead player removed");
        a.players[0].alive=true;grid.refresh();check(grid.candidates(500,-128).size()==1,"revived player returns");
        a.connected=false;grid.refresh();check(!grid.eligible(a)&&grid.candidates(500,-128).isEmpty(),"connection eligibility removed");
        a.connected=true;a.delayed=true;grid.refresh();check(!grid.eligible(a),"delayed disconnect excluded");
        a.delayed=false;a.range=10;a.players[0].x=-128;grid.refresh();
        Collections.swap(GameServer.udpEngine.connections,0,1);grid.refresh();
        check(grid.candidates(-128,-128).get(0).connection==b,"connection order changes retain vanilla selection priority");
        GameServer.udpEngine.connections.remove(b);grid.refresh();check(grid.candidates(-128,-128).size()==1&&!grid.eligible(b),"disconnect removes persistent coverage");
        var random=new Random(791);
        for(int frame=0;frame<100;frame++){
            a.players[0].x=random.nextFloat()*1000-500;a.players[0].y=random.nextFloat()*1000-500;a.range=3+random.nextInt(20);grid.refresh();
            for(int q=0;q<20;q++){
                float x=random.nextFloat()*1000-500,y=random.nextFloat()*1000-500;
                boolean relevant=!Float.isInfinite(a.players[0].getRelevantAndDistance(x,y,a.range-2));
                boolean present=grid.candidates(x,y).stream().anyMatch(c->c.connection==a);
                check(!relevant||present,"coverage never excludes an exact relevant player");
            }
        }
    }
    static void cadence(){
        reset();var a=connection(0,0);var b=connection(10,0);var manager=new NetworkZombieManager();manager.beginAuthUpdate();
        var z=zombie(0);z.x=1;manager.moveZombie(z,a,a.players[0]);z.lastChangeOwner=0;
        long t=System.currentTimeMillis()/500*500+100000;long checks=count("zombies.auth.reassessed");
        manager.updateAuth(z,t);for(int i=1;i<5;i++)manager.updateAuth(z,t+i*100);
        check(count("zombies.auth.reassessed")==checks+1,"stable ownership reassesses once across five ticks");
        manager.updateAuth(z,t+500);check(count("zombies.auth.reassessed")==checks+2,"500 ms boundary reassesses");
        z.target=b.players[0];manager.updateAuth(z,t+550);check(z.owner==b,"target change bypasses stable cadence");
        z.target=null;z.lastChangeOwner=0;manager.updateAuth(z,t+600);
        z.grapple.other=a.players[0];manager.updateAuth(z,t+650);check(z.owner==a,"grapple change bypasses cadence");
        z.grapple.other=null;z.lastChangeOwner=0;manager.updateAuth(z,t+700);
        a.delayed=true;manager.beginAuthUpdate();z.lastChangeOwner=System.currentTimeMillis();manager.updateAuth(z,t+701);
        check(z.owner==b,"invalid owner bypasses reassessment and transfer cooldown");
        z.owner=null;manager.updateAuth(z,t+702);check(z.owner==b,"ownerless zombie is assigned immediately");
        var packet=new ZombieListPacket();manager.getZombieAuth(a,packet);check(packet.zombiesAuth.isEmpty(),"transfer removes old owner authorization");
        manager.getZombieAuth(b,packet);check(packet.zombiesAuth.equals(List.of(z.onlineId)),"new owner list contains zombie once");
        z.dead=true;z.realState=NetworkVariables.ZombieState.OnGround;manager.updateAuth(z,t+703);
        manager.getZombieAuth(b,packet);check(z.owner==null&&packet.zombiesAuth.isEmpty(),"ground death removes indexed ownership immediately");
        reset();a=connection(0,0);manager=new NetworkZombieManager();manager.beginAuthUpdate();z=zombie(0);manager.moveZombie(z,a,a.players[0]);
        long now=System.currentTimeMillis();z.lastChangeOwner=now;checks=count("zombies.auth.reassessed");manager.updateAuth(z,now+100);
        check(count("zombies.auth.reassessed")==checks,"vanilla two-second transfer cooldown retained");
        manager.updateAuth(z,now+2000);check(count("zombies.auth.reassessed")==checks+1,"transfer cooldown expires normally");
        checks=count("zombies.auth.reassessed");z.onlineId++;manager.updateAuth(z,now+2001);
        check(count("zombies.auth.reassessed")==checks+1,"pooled online ID change invalidates cadence");
        checks=count("zombies.auth.reassessed");manager.updateAuth(z,now-10000);
        check(count("zombies.auth.transferCooldownSkipped")>0,"clock rollback retains transfer cooldown");
        z.lastChangeOwner=0;manager.updateAuth(z,now-10000);
        check(count("zombies.auth.reassessed")==checks+1,"clock rollback cannot trap cadence in the future");
        reset();a=connection(0,0);b=connection(0,0);manager=new NetworkZombieManager();manager.beginAuthUpdate();z=zombie(0);
        manager.moveZombie(z,a,a.players[0]);z.lastChangeOwner=0;ServerOptions.instance.switchZombiesOwnershipEachUpdate.value=true;
        manager.updateAuth(z,t);check(z.owner==b,"debug ownership rotation remains enabled");
        z.lastChangeOwner=0;manager.updateAuth(z,t+1);check(z.owner==a,"debug rotation bypasses stable cadence");
        reset();a=connection(0,0);b=connection(5,0);manager=NetworkZombieManager.getInstance();manager.beginAuthUpdate();z=zombie(77);
        manager.moveZombie(z,a,a.players[0]);z.target=a.players[0];a.delayed=true;
        manager.clearTargetAuth(a,a.players[0]);
        manager.getZombieAuth(a,packet);check(packet.zombiesAuth.isEmpty(),"disconnect cleanup removes old indexed authorization");
        manager.getZombieAuth(b,packet);check(z.owner==b&&z.target==null&&packet.zombiesAuth.equals(List.of(z.onlineId)),"disconnect reassignment retains new ownership membership");
    }
    public static void main(String[] args){ownership();coverage();cadence();System.out.println("Passed "+assertions+" zombie authorization assertions.");}
}
"""

def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-auth-tests-") as directory:
        work = Path(directory)
        sources = []
        for relative, content in STUBS.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        harness = work / "zombie/popman/ZombieAuthAlgorithmsTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources.append(harness)
        sources.extend(ROOT / "42.21.0/src/zombie/popman" / name for name in [
            "NetworkZombieManager.java", "ZombieAuthCoverage.java", "ZombieOwnershipIndex.java",
        ])
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.popman.ZombieAuthAlgorithmsTest"], check=True)

if __name__ == "__main__":
    main()
