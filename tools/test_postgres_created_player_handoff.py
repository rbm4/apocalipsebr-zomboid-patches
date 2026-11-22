"""Exercise the production new-life handoff state machine without a running server."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / '42.21.0/src/zombie'
FILES = {
    'zombie/core/Core.java': '''package zombie.core; public class Core {public static String gameSaveWorld="test";}''',
    'zombie/core/znet/SteamUtils.java': '''package zombie.core.znet; public class SteamUtils {public static boolean steam; public static boolean isSteamModeEnabled(){return steam;}}''',
    'zombie/core/raknet/UdpConnection.java': '''package zombie.core.raknet; public class UdpConnection {
 public long guid=1; public String user="alice",steam="765";public boolean disconnected; public int denied;
 public long getConnectedGUID(){return guid;}public String getUserName(){return user;}public String getIDStr(){return steam;}
}''',
    'zombie/network/GameServer.java': '''package zombie.network; public class GameServer {
 public static boolean coop;public static final Engine udpEngine=new Engine();
 public static class Engine {public final java.util.List<zombie.core.raknet.UdpConnection> connections=new java.util.ArrayList<>();}
 public static boolean isDelayedDisconnect(zombie.core.raknet.UdpConnection c){return c.disconnected;}
}''',
    'zombie/iso/IsoWorld.java': '''package zombie.iso;public class IsoWorld {public static int getWorldVersion(){return 249;}}''',
    'zombie/characters/IsoPlayer.java': '''package zombie.characters; public class IsoPlayer {
 public int serverPlayerIndex=-1;public boolean remote,removedSquare,attachedSquare;
 public final Cell cell=new Cell();public IsoPlayer(){cell.add.add(this);cell.objects.add(this);}
 public Cell getCell(){return cell;}public void removeFromSquare(){removedSquare=true;}
 public void setCurrentSquareFromPosition(){attachedSquare=true;}
 public static class Cell {public final java.util.List<IsoPlayer> add=new java.util.ArrayList<>(),objects=new java.util.ArrayList<>();
 public java.util.List<IsoPlayer> getAddList(){return add;}public java.util.List<IsoPlayer> getObjectList(){return objects;}
 public void addMovingObject(IsoPlayer p){objects.add(p);}}
}''',
    'zombie/debug/DebugLog.java': '''package zombie.debug; public class DebugLog {
 public static final java.util.List<String> logs=new java.util.ArrayList<>();public static void log(String s){logs.add(s);}}''',
    'zombie/savefile/ApocBRPlayerDatabase.java': '''package zombie.savefile;class ApocBRPlayerDatabase {static boolean enabled=true;static boolean enabled(){return enabled;}}''',
    'zombie/savefile/ApocBRPersistence.java': '''package zombie.savefile;class ApocBRPersistence {static boolean journal=true;static boolean journaling(){return journal;}}''',
    'zombie/savefile/ApocBRLoginGuard.java': '''package zombie.savefile;class ApocBRLoginGuard {
 static void deny(zombie.core.raknet.UdpConnection c){c.denied++;}}''',
    'zombie/savefile/ApocBRPlayerRelationalSync.java': '''package zombie.savefile;class ApocBRPlayerRelationalSync {
 static boolean accept=true,enabled=true,committed;static int offers;
 static boolean creationIsCommitted(java.util.UUID session){return committed;}
 static void forgetCreation(java.util.UUID session){}
 static boolean enabled(){return enabled;}
 static java.util.UUID offerCreatedPlayerForHandoff(zombie.characters.IsoPlayer p,String world,String identity,int slot,int version){
 offers++;if(accept)ApocBRPostgresConnector.pending=true;return accept?java.util.UUID.randomUUID():null;}}''',
    'zombie/savefile/ApocBRPostgresConnector.java': '''package zombie.savefile;class ApocBRPostgresConnector {
 static boolean pending;static String lastIdentity;static int lastSlot;
 static boolean playerReadable(String world,String identity,int slot,boolean coop){lastIdentity=identity;lastSlot=slot;return !pending;}}''',
    'zombie/savefile/HandoffTest.java': r'''package zombie.savefile;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
public class HandoffTest {
 static int checks,created,responses;static IsoPlayer player;static long now;
 static final Thread main=Thread.currentThread();
 static void check(boolean condition,String text){checks++;if(!condition)throw new AssertionError(text);}
 static void reset()throws Exception {
  var map=ApocBRCreatedPlayerHandoff.class.getDeclaredField("waiting");map.setAccessible(true);((java.util.Map<?,?>)map.get(null)).clear();
  for(String name:new String[]{"count","nextPoll"}){var f=ApocBRCreatedPlayerHandoff.class.getDeclaredField(name);f.setAccessible(true);if(name.equals("count"))f.setInt(null,0);else f.setLong(null,0);}
  GameServer.udpEngine.connections.clear();GameServer.coop=false;zombie.core.znet.SteamUtils.steam=false;
  zombie.core.Core.gameSaveWorld="test";ApocBRPostgresConnector.pending=false;ApocBRPlayerRelationalSync.accept=true;
  ApocBRPersistence.journal=true;created=responses=0;player=null;now=System.nanoTime();
  ApocBRPlayerRelationalSync.committed=true;
 }
 static UdpConnection connection(){var c=new UdpConnection();GameServer.udpEngine.connections.add(c);return c;}
 static void tick(){now+=50_000_000L;ApocBRCreatedPlayerHandoff.update(now);}
 static void request(UdpConnection c,int slot){check(ApocBRCreatedPlayerHandoff.defer(c,slot,()->{
  check(Thread.currentThread()==main,"creation stays on simulation thread");created++;player=new IsoPlayer();
  check(ApocBRCreatedPlayerHandoff.created(c,slot,player,()->{check(Thread.currentThread()==main,"network response stays on simulation thread");responses++;}),"creation belongs to reservation");
 }),"request deferred");}
 public static void main(String[] args)throws Exception {
  reset();var c=connection();ApocBRPostgresConnector.pending=true;request(c,0);tick();
  check(created==0&&responses==0,"old death save must clear before creating the next life");
  request(c,0);check(ApocBRCreatedPlayerHandoff.take(c,0)==null,"cannot consume before creation");
  ApocBRPostgresConnector.pending=false;tick();
  check(created==1&&responses==0,"duplicates coalesce and accepted capture waits for commit");
  check(player.remote&&player.removedSquare&&player.cell.add.isEmpty()&&player.cell.objects.isEmpty(),"new player stays outside simulation while persistence is pending");
  tick();check(responses==0,"pending new save must not send response");
  check(ApocBRCreatedPlayerHandoff.take(c,0)==null,"cannot attach before persistence barrier");
  ApocBRPostgresConnector.pending=false;tick();check(responses==1,"exactly one response after commit");
  tick();check(responses==1,"later ticks do not resend");
  var other=connection();check(ApocBRCreatedPlayerHandoff.take(other,0)==null,"another connection cannot take life");
  check(ApocBRCreatedPlayerHandoff.take(c,1)==null,"another slot cannot take life");
  ApocBRPostgresConnector.pending=true;
  check(ApocBRCreatedPlayerHandoff.take(c,0)==player,"handoff uses exact committed instance even if later unrelated writes are queued");
  check(player.serverPlayerIndex==0&&player.attachedSquare&&player.cell.objects.size()==1,"attachment restores simulation once");
  check(ApocBRCreatedPlayerHandoff.take(c,0)==null,"one-shot handoff cannot reuse life");

  reset();c=connection();ApocBRPlayerRelationalSync.committed=false;request(c,0);tick();
  ApocBRPostgresConnector.pending=false;tick();
  check(responses==0,"empty slot queue after discard cannot impersonate a committed creation");
  ApocBRPlayerRelationalSync.committed=true;ApocBRPostgresConnector.pending=true;tick();
  check(responses==0,"creation commit still waits for journal checkpoint/pending writes");
  ApocBRPostgresConnector.pending=false;tick();check(responses==1,"specific creation commit and cleared guard release response");

  reset();c=connection();request(c,0);tick();c.disconnected=true;ApocBRPostgresConnector.pending=false;tick();
  check(responses==0&&ApocBRCreatedPlayerHandoff.take(c,0)==null,"disconnect cancels pending response");
  reset();c=connection();request(c,0);tick();ApocBRPostgresConnector.pending=false;tick();
  c.guid++;check(ApocBRCreatedPlayerHandoff.take(c,0)==null,"reused connection GUID cannot receive stale life");
  reset();c=connection();request(c,0);tick();c.user="bob";ApocBRPostgresConnector.pending=false;tick();
  check(responses==0,"identity change cancels callback");
  reset();c=connection();request(c,0);tick();zombie.core.Core.gameSaveWorld="other";ApocBRPostgresConnector.pending=false;tick();
  check(responses==0,"world change cancels callback");
  reset();c=connection();request(c,0);tick();ApocBRPostgresConnector.pending=false;GameServer.udpEngine.connections.remove(c);tick();
  check(responses==0,"removed connection cancels callback");
  reset();c=connection();ApocBRPostgresConnector.pending=true;request(c,0);now+=31_000_000_000L;tick();
  check(created==0&&responses==0&&c.denied==1,"bounded timeout never hands out stale DB state");
  reset();c=connection();ApocBRPlayerRelationalSync.accept=false;request(c,0);tick();
  check(responses==0&&c.denied==1,"capture rejection fails closed");
  reset();c=connection();request(c,0);tick();now+=31_000_000_000L;tick();
  check(responses==0&&c.denied==1,"failed/quarantined capture cannot satisfy barrier");
  reset();c=connection();request(c,0);tick();ApocBRPostgresConnector.pending=false;tick();now+=31_000_000_000L;tick();
  check(c.denied==1&&ApocBRCreatedPlayerHandoff.take(c,0)==null,"unclaimed response expires");
  reset();c=connection();GameServer.coop=true;zombie.core.znet.SteamUtils.steam=true;request(c,3);tick();
  check(ApocBRPostgresConnector.lastIdentity.equals(c.steam)&&ApocBRPostgresConnector.lastSlot==3,"Steam coop identity and split-screen slot retained");
  ApocBRPostgresConnector.pending=false;tick();check(ApocBRCreatedPlayerHandoff.take(c,3)==player&&player.serverPlayerIndex==3,"secondary slot receives its life");
  reset();c=connection();ApocBRPersistence.journal=false;
  check(!ApocBRCreatedPlayerHandoff.defer(c,0,()->{throw new AssertionError();}),"non-journal vanilla path unchanged");
  reset();c=connection();check(ApocBRCreatedPlayerHandoff.defer(c,4,()->{throw new AssertionError();})&&c.denied==1,"invalid slots rejected");
  reset();for(int i=0;i<128;i++){c=connection();ApocBRCreatedPlayerHandoff.defer(c,0,()->{});}
  c=connection();check(ApocBRCreatedPlayerHandoff.defer(c,0,()->{})&&c.denied==1,"queue capacity bounded");
  System.out.println("Created player handoff: "+checks+" checks passed");
 }
}''',
}

# Keep both actual packet integration points connected to the tested coordinator.
packet = (SRC / 'network/packets/character/CreatePlayerPacket.java').read_text(encoding='utf-8')
start = packet.index('    private CreatePlayerPacket copyForHandoff() {')
end = packet.index('\n    }', start) + len('\n    }')
# Execute the real detach method with field stubs to reproduce packet-handler reuse.
FILES['zombie/savefile/CreatePlayerPacket.java'] = '''package zombie.savefile;
class CreatePlayerPacket {
 String spawnRegionName;Object survivorDescriptor,player;byte playerIndex;
 final java.util.List<String> characterTraits=new java.util.ArrayList<>();
''' + packet[start:end] + '''
 public static void main(String[] args){
  var original=new CreatePlayerPacket();original.spawnRegionName="region";original.survivorDescriptor=new Object();
  original.playerIndex=2;original.characterTraits.add("trait");original.player=new Object();
  var saved=original.copyForHandoff();Object descriptor=original.survivorDescriptor;
  original.spawnRegionName="other";original.survivorDescriptor=new Object();original.playerIndex=3;original.characterTraits.clear();
  if(!saved.spawnRegionName.equals("region")||saved.survivorDescriptor!=descriptor||saved.playerIndex!=2
    ||!saved.characterTraits.equals(java.util.List.of("trait"))||saved.player!=null)throw new AssertionError("retained cached packet state");
  System.out.println("Cached packet detach: request fields survive handler reuse");
 }
}'''
assert packet.index('ApocBRCreatedPlayerHandoff.defer(') < packet.index('ApocBRLoginGuard.playerReady(')
assert packet.index('serverUpdateNetworkCharacter(this.player') < packet.index('ApocBRCreatedPlayerHandoff.created(')
assert packet.index('ApocBRCreatedPlayerHandoff.created(') < packet.index('ApocBRPlayerRelationalSync.offerCreatedPlayer(')
server = (SRC / 'network/GameServer.java').read_text(encoding='utf-8')
assert 'ApocBRCreatedPlayerHandoff.update();' in server
assert server.index('ApocBRCreatedPlayerHandoff.take(') < server.index('serverLoadNetworkCharacter(playerIndex,')

with tempfile.TemporaryDirectory(prefix='apocbr_created_handoff_') as folder:
    target = Path(folder)
    files = []
    for name, body in FILES.items():
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
        files.append(path)
    files.append(SRC / 'savefile/ApocBRCreatedPlayerHandoff.java')
    subprocess.run([str(ROOT / 'jdk/bin/javac.exe'), '--release', '25', '-d', folder, *map(str, files)], check=True)
    subprocess.run([str(ROOT / 'jdk/bin/java.exe'), '-cp', folder, 'zombie.savefile.HandoffTest'], check=True, timeout=15)
    subprocess.run([str(ROOT / 'jdk/bin/java.exe'), '-cp', folder, 'zombie.savefile.CreatePlayerPacket'], check=True, timeout=15)
