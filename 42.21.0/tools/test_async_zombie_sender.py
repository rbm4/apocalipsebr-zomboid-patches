"""Production async sender and extracted main-thread capture, with deterministic transport barriers."""
from pathlib import Path
import re
import subprocess
import tempfile

VERSION = Path(__file__).resolve().parents[1]
ROOT = VERSION.parent
SRC = VERSION / "src"


def extract(text, signature):
    start = text.index(signature)
    end = text.index("{", start) + 1
    depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


STUBS = {
    "zombie/ApocBRServerTelemetryLite.java": """package zombie;
public class ApocBRServerTelemetryLite { public static class Scope implements AutoCloseable {public void close(){}}
 public static Scope phase(String n){return new Scope();} public static void count(String n,long v){} public static void recordPhase(String n,long v){} }""",
    "zombie/network/IConnection.java": "package zombie.network; public interface IConnection {}",
    "zombie/core/utils/UpdateLimit.java": """package zombie.core.utils;
public class UpdateLimit {public boolean due=true; public boolean Check(){return due;} public boolean check(){return due;}
 public void Reset(){due=false;} public void reset(long n){due=false;} }""",
    "zombie/core/raknet/RakNetPeerInterface.java": """package zombie.core.raknet;
public class RakNetPeerInterface {public zombie.popman.ServerZombieAsyncSender.Transport transport;
 public int SendRaw(java.nio.ByteBuffer b,int p,int r,byte o,long guid,boolean broad){return transport.send(b,p,r,o);} }""",
    "zombie/core/raknet/UdpConnection.java": """package zombie.core.raknet;
public class UdpConnection implements zombie.network.IConnection {
 public boolean connected=true,delayed; public int hash; public short[] auth=new short[0];
 public final RakNetPeerInterface peer=new RakNetPeerInterface();
 public final zombie.core.utils.UpdateLimit zombieListRefresh=new zombie.core.utils.UpdateLimit(),timerSendZombie=new zombie.core.utils.UpdateLimit();
 public boolean isFullyConnected(){return connected;} public RakNetPeerInterface getPeer(){return peer;} public long getConnectedGUID(){return 123;}
 public boolean isRelevantTo(float x,float y){return x>=0;} public boolean RelevantTo(float x,float y,int r){return x>=0;}
 public int getRelevantRange(){return 10;} public boolean isNeighborPlayer(){return true;}
 public int getZombieListHash(){return hash;} public void setZombieListHash(int h){hash=h;}
 public Object getPacket(zombie.network.PacketTypes.PacketType t){return new zombie.network.packets.character.ZombieListPacket();}
} """,
    "zombie/network/GameServer.java": """package zombie.network;
public class GameServer {public static class Engine {public java.util.List<zombie.core.raknet.UdpConnection> connections=new java.util.ArrayList<>();}
 public static Engine udpEngine=new Engine(); public static boolean isDelayedDisconnect(zombie.core.raknet.UdpConnection c){return c.delayed;} }""",
    "zombie/network/ServerMap.java": """package zombie.network; public class ServerMap {
 public static final ServerMap instance=new ServerMap(); public java.util.Map<Short,zombie.characters.IsoZombie> zombieMap=new java.util.HashMap<>();} """,
    "zombie/network/PacketTypes.java": """package zombie.network; public class PacketTypes {public enum PacketType {
 ZombieDeleteOnClient,ZombieList,ZombieSynchronizationReliable,ZombieSynchronizationUnreliable;
 public int packetPriority=1,packetReliability=2; public byte orderingChannel=0; public short getId(){return (short)ordinal();}}} """,
    "zombie/network/statistics/data/NetworkStatistic.java": """package zombie.network.statistics.data;
public class NetworkStatistic {public static final NetworkStatistic i=new NetworkStatistic(); public static NetworkStatistic getInstance(){return i;}
 public void addOutcomePacket(short id,int size,zombie.core.raknet.UdpConnection c){} }""",
    "zombie/network/packets/character/ZombieListPacket.java": "package zombie.network.packets.character; public class ZombieListPacket {public java.util.ArrayList<Short> zombiesAuth=new java.util.ArrayList<>();}",
    "zombie/popman/NetworkZombieManager.java": """package zombie.popman; public class NetworkZombieManager {
 public static final NetworkZombieManager i=new NetworkZombieManager(); public static NetworkZombieManager getInstance(){return i;}
 public int getZombieAuth(zombie.core.raknet.UdpConnection c,zombie.network.packets.character.ZombieListPacket p){int h=1; for(short id:c.auth){p.zombiesAuth.add(id);h=h*31+id;} return h;} }""",
    "zombie/characters/IsoZombie.java": """package zombie.characters; public class IsoZombie {
 public short onlineId; public float x,y; public Object owner; public final Wire zombiePacket=new Wire();
 public IsoZombie(short id){onlineId=id;} public float getX(){return x;} public float getY(){return y;} public Object getOwner(){return owner;}
 public static class Wire {public byte[] data=new byte[]{7,8,9}; public void write(java.nio.ByteBuffer b){b.put(data);}} }""",
    "zombie/popman/ServerZombiePacketPreparation.java": "package zombie.popman; public class ServerZombiePacketPreparation {public void prepare(zombie.characters.IsoZombie z){}}",
    "zombie/debug/LogSeverity.java": "package zombie.debug; public enum LogSeverity {Error}",
    "zombie/debug/DebugType.java": "package zombie.debug; public class DebugType {public static final DebugType General=new DebugType(); public void printException(Exception e,LogSeverity l){throw new AssertionError(e);}}",
}

HARNESS = r"""
package zombie.popman;
import java.nio.*; import java.util.*; import java.util.concurrent.*; import java.util.concurrent.atomic.*;
import zombie.core.raknet.*; import zombie.characters.IsoZombie; import zombie.network.*;
public class AsyncZombieSenderTest {
 static int checks; static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void await(CountDownLatch l)throws Exception{check(l.await(5,TimeUnit.SECONDS),"barrier timeout");}
 static ServerZombieAsyncSender.Completion completion(ServerZombieAsyncSender s)throws Exception {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); ServerZombieAsyncSender.Completion c;
  while((c=s.poll())==null){if(System.nanoTime()>deadline)throw new AssertionError("completion timeout");Thread.sleep(1);} return c;
 }
 static byte[] bytes(ByteBuffer b){byte[] a=new byte[b.remaining()];b.get(a);return a;}
 static void drain(NetworkZombiePacker p,UdpConnection c)throws Exception {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(!p.connectionStates.get(c).inFlightRequests.isEmpty()||!p.connectionStates.get(c).inFlightDeletes.isEmpty()) {
   p.awaitWorkers();if(System.nanoTime()>deadline)throw new AssertionError("capture completion timeout");Thread.sleep(1);
  }
 }
 static void helperTests()throws Exception {
  ServerZombieAsyncSender s=new ServerZombieAsyncSender(2,2);
  CountDownLatch entered=new CountDownLatch(1), unblock=new CountDownLatch(1);
  List<byte[]> wire=new CopyOnWriteArrayList<>(); AtomicReference<Thread> thread=new AtomicReference<>();
  ServerZombieAsyncSender.Lane a=new ServerZombieAsyncSender.Lane((b,p,r,o)->{thread.set(Thread.currentThread());entered.countDown();try{unblock.await();}catch(Exception e){throw new RuntimeException(e);}wire.add(bytes(b));return 1;});
  byte[] source={1,2,3}; byte[][] parts={source};
  var message=new ServerZombieAsyncSender.Message((short)12,1,2,(byte)3,parts);
  check(s.reserve(a),"reserve first batch"); s.submit(a,List.of(message)); await(entered);
  source[0]=99; parts[0]=new byte[]{88};
  check(!s.reserve(a),"same connection cannot overlap");
  CountDownLatch independent=new CountDownLatch(1);
  var other=new ServerZombieAsyncSender.Lane((b,p,r,o)->{check(b.isDirect(),"native direct buffer");independent.countDown();return 1;});
  check(s.reserve(other),"other connection admitted"); s.submit(other,List.of(message)); await(independent);
  check(!s.reserve(new ServerZombieAsyncSender.Lane((b,p,r,o)->1)),"global capacity includes undrained completions");
  var c=completion(s); check(c.lane==other&&c.success,"other connection independent");s.release(c.lane);
  unblock.countDown(); c=completion(s);check(c.success,"first success");s.release(c.lane);
  check(thread.get()!=Thread.currentThread(),"transport outside main thread");
  check(Arrays.equals(wire.getFirst(),new byte[]{(byte)134,0,12,1,2,3}),"frozen wire/header");
  check(s.reserve(a),"next batch after completion");s.submit(a,List.of(message));c=completion(s);s.release(c.lane);
  check(wire.size()==2,"ordered second batch");
  var fail=new ServerZombieAsyncSender.Lane((b,p,r,o)->0);check(s.reserve(fail),"failure reservation");s.submit(fail,List.of(message));c=completion(s);
  check(!c.success&&c.attempted==1,"native failure reported");s.release(c.lane);
  var thrown=new ServerZombieAsyncSender.Lane((b,p,r,o)->{throw new RuntimeException();});check(s.reserve(thrown),"exception reservation");s.submit(thrown,List.of(message));c=completion(s);
  check(!c.success,"exception completion");s.release(c.lane);
  var closed=new ServerZombieAsyncSender.Lane((b,p,r,o)->{throw new AssertionError("closed transport invoked");});check(s.reserve(closed),"close reservation");closed.close();s.submit(closed,List.of(message));c=completion(s);
  check(!c.success&&c.attempted==0,"retired lane canceled");s.release(c.lane);check(!s.reserve(closed),"retired lane rejects");
  boolean overflow=false;try{new ServerZombieAsyncSender.Message((short)1,1,1,(byte)0,new byte[1000000]);}catch(IllegalArgumentException e){overflow=true;}
  check(overflow,"bounded packet size");s.shutdown();check(!s.reserve(a),"shutdown rejects");
 }
 static void captureTests()throws Exception {
  NetworkZombiePacker packer=new NetworkZombiePacker(); UdpConnection conn=new UdpConnection();
  GameServer.udpEngine.connections.clear();GameServer.udpEngine.connections.add(conn);
  CountDownLatch entered=new CountDownLatch(1),unblock=new CountDownLatch(1),finished=new CountDownLatch(1);
  List<byte[]> wire=new CopyOnWriteArrayList<>(); AtomicBoolean failNext=new AtomicBoolean();
  conn.peer.transport=(b,p,r,o)->{entered.countDown();try{unblock.await();}catch(Exception e){throw new RuntimeException(e);}wire.add(bytes(b));finished.countDown();return failNext.getAndSet(false)?0:1;};
  IsoZombie z=new IsoZombie((short)10);z.owner=conn;ServerMap.instance.zombieMap.put(z.onlineId,z);conn.auth=new short[]{10};
  var requests=packer.zombiesRequest.getNetworkZombie(conn);requests.zombies.add(z);
  packer.send(conn);await(entered);z.zombiePacket.data[0]=99;
  packer.zombiesDeletedForSending.add(new NetworkZombiePacker.DeletedZombie((short)11,1,1));
  requests.zombies.add(z);packer.send(conn);
  check(requests.zombies.size()==1,"backpressure leaves request queue untouched");
  check(packer.connectionStates.get(conn).pendingDeletes.contains((short)11),"backpressure retains deletion");
  unblock.countDown();await(finished);packer.asyncSender.shutdown();packer.awaitWorkers();
  check(wire.size()==2,"auth then sync");
  check(Arrays.equals(wire.get(0),new byte[]{(byte)134,0,1,0,1,0,10}),"vanilla auth wire");
  check(Arrays.equals(wire.get(1),new byte[]{(byte)134,0,2,1,0,1,7,8,9}),"sync frozen before mutation");

  // Failure must restore control data and requests, and force an authorization refresh.
  packer=new NetworkZombiePacker(); wire.clear(); failNext.set(true);
  packer.zombiesDeletedForSending.add(new NetworkZombiePacker.DeletedZombie((short)12,1,1));
  packer.zombiesRequest.getNetworkZombie(conn).zombies.add(z);packer.send(conn);
  drain(packer,conn);var state=packer.connectionStates.get(conn);
  check(state.pendingDeletes.contains((short)12),"failed deletion retained");
  check(state.forceAuth&&state.forceExtra,"failure forces refresh");
  check(packer.zombiesRequest.getNetworkZombie(conn).zombies.contains(z),"failed sync request restored");
  wire.clear();packer.zombiesDeletedForSending.clear();packer.send(conn);drain(packer,conn);
  check(wire.size()==3,"failed batch retried as delete/auth/sync");
  check(!state.forceAuth&&!state.forceExtra&&state.pendingDeletes.isEmpty(),"successful retry commits control state");
  conn.connected=false;packer.postupdate();check(packer.connectionStates.isEmpty(),"disconnect retires state");packer.shutdown();

  packer=new NetworkZombiePacker(); conn.connected=true;conn.auth=new short[0];conn.hash=1;conn.timerSendZombie.due=false;wire.clear();
  for(int i=0;i<601;i++)packer.zombiesDeletedForSending.add(new NetworkZombiePacker.DeletedZombie((short)i,1,1));
  packer.send(conn);packer.asyncSender.shutdown();packer.awaitWorkers();
  check(wire.size()==3,"large delete backlog split");
  check(ByteBuffer.wrap(wire.get(0)).getShort(3)==300&&ByteBuffer.wrap(wire.get(2)).getShort(3)==1,"signed-short delete counts");
  check(packer.connectionStates.get(conn).pendingDeletes.isEmpty(),"submitted deletion backlog consumed");packer.shutdown();

  packer=new NetworkZombiePacker();wire.clear();conn.timerSendZombie.due=false;
  packer.send(conn);check(packer.batchesSubmitted==0,"idle capture releases reservation without a batch");
  conn.timerSendZombie.due=true;packer.send(conn);packer.asyncSender.shutdown();packer.awaitWorkers();
  check(wire.size()==1&&wire.getFirst().length==6,"empty keepalive admitted after idle capture");packer.shutdown();

  // Native completion from an old lifetime must not requeue the reused object under a new ID.
  packer=new NetworkZombiePacker();CountDownLatch reuseEntered=new CountDownLatch(1),reuseRelease=new CountDownLatch(1);
  conn.peer.transport=(b,p,r,o)->{reuseEntered.countDown();try{reuseRelease.await();}catch(Exception e){throw new RuntimeException(e);}return 0;};
  packer.zombiesRequest.getNetworkZombie(conn).zombies.add(z);packer.send(conn);await(reuseEntered);
  ServerMap.instance.zombieMap.remove(z.onlineId);z.onlineId=20;ServerMap.instance.zombieMap.put(z.onlineId,z);
  reuseRelease.countDown();packer.asyncSender.shutdown();packer.awaitWorkers();
  check(packer.zombiesRequest.getNetworkZombie(conn).zombies.isEmpty(),"failed old lifetime not restored after ID reuse");packer.shutdown();

  // A full global budget preserves the next admission position until capacity becomes free.
  packer=new NetworkZombiePacker();GameServer.udpEngine.connections.clear();
  CountDownLatch fairRelease=new CountDownLatch(1),fairEntered=new CountDownLatch(1);
  for(int i=0;i<8;i++){UdpConnection c=new UdpConnection();c.peer.transport=(b,p,r,o)->{fairEntered.countDown();try{fairRelease.await();}catch(Exception e){throw new RuntimeException(e);}return 1;};GameServer.udpEngine.connections.add(c);}
  packer.postupdate();await(fairEntered);check(packer.nextConnectionStart==4,"admission rotates past admitted group");
  packer.postupdate();check(packer.nextConnectionStart==4&&packer.batchesSubmitted==4,"blocked frame preserves fair cursor");
  fairRelease.countDown();packer.asyncSender.shutdown();packer.awaitWorkers();packer.shutdown();
 }
 public static void main(String[] args)throws Exception{helperTests();captureTests();System.out.println("Async zombie sender: "+checks+" checks passed");}
}
"""


def main():
    packer = (SRC / "zombie/popman/NetworkZombiePacker.java").read_text()
    # Keep the actual production static declaration order: constructing the singleton
    # before a property-derived capacity initializes used to pass zero to its sender.
    declarations = re.findall(r"^    private static final (?:int|NetworkZombiePacker) [^;]+;", packer, re.M)
    sender_field = re.search(r"^    private final ServerZombieAsyncSender asyncSender = [^;]+;", packer, re.M).group()
    initialization_fixture = "package zombie.popman; class InitializationFixture {\n" + "\n".join(
        declarations + [sender_field]
    ).replace("private ", "").replace("NetworkZombiePacker", "InitializationFixture") + "\n}"
    initialization_test = """package zombie.popman; public class InitializationOrderTest {
 public static void main(String[] args) {
  InitializationFixture singleton=InitializationFixture.instance;
  if(singleton==null || InitializationFixture.ASYNC_CAPACITY!=Integer.parseInt(args[0])
     || InitializationFixture.ZOMBIE_PACKET_WORKERS!=Integer.parseInt(args[1])) throw new AssertionError("startup configuration");
  singleton.asyncSender.shutdown();
  System.out.println("Singleton startup passed: capacity="+InitializationFixture.ASYNC_CAPACITY+", workers="+InitializationFixture.ZOMBIE_PACKET_WORKERS);
 }
} """
    signatures = ["public void awaitWorkers()", "public void shutdown()", "public void postupdate()",
                  "private ConnectionState stateFor(", "public void send(UdpConnection connection)",
                  "private boolean isRelayRelevant(", "private ServerZombieAsyncSender.Message freeze(",
                  "private byte[] freezeIds(", "private void restoreRequests(", "private void captureAndSubmit(",
                  "private static final class ConnectionState", "private static final class RequestedZombie"]
    methods = "\n".join(extract(packer, signature).replace("private ", "") for signature in signatures)
    fixture = """package zombie.popman;
import java.util.*;import java.nio.*;import java.util.concurrent.atomic.*;
import zombie.ApocBRServerTelemetryLite;import zombie.characters.IsoZombie;import zombie.core.raknet.*;
import zombie.core.utils.UpdateLimit;import zombie.network.*;import zombie.network.statistics.data.NetworkStatistic;
import zombie.network.packets.character.*;import zombie.debug.*;
public class NetworkZombiePacker {
 final static int MAX_ASYNC_CONNECTIONS=256,ASYNC_CAPACITY=4; int nextConnectionStart; long batchesSubmitted;
 final ServerZombieAsyncSender asyncSender=new ServerZombieAsyncSender(2,4);
 final Map<UdpConnection,ConnectionState> connectionStates=new IdentityHashMap<>();
 final Map<ServerZombieAsyncSender.Lane,ConnectionState> statesByLane=new IdentityHashMap<>();
 final NetworkZombieList zombiesRequest=new NetworkZombieList();
 final Map<IConnection,List<Short>> zombiesToSend=new HashMap<>();
 final Set<IConnection> extraUpdate=new HashSet<>();final AtomicBoolean extraUpdateAll=new AtomicBoolean();
 final ByteBuffer snapshotBuffer=ByteBuffer.allocate(1000000);final ServerZombiePacketPreparation packetPreparation=new ServerZombiePacketPreparation();
 final UpdateLimit zombieSynchronizationReliableLimit=new UpdateLimit();
 final List<DeletedZombie> zombiesDeleted=new ArrayList<>(),zombiesDeletedForSending=new ArrayList<>();
 final Set<IsoZombie> zombiesReceived=new HashSet<>();final List<IsoZombie> zombiesProcessing=new ArrayList<>();
 void updateAuth(){} void rebuildZombiesProcessingGrid(){} void getRelayCandidates(UdpConnection c,HashSet<IsoZombie> z){z.addAll(zombiesProcessing);}
 static class DeletedZombie {short onlineId;float x,y;DeletedZombie(short id,float x,float y){onlineId=id;this.x=x;this.y=y;}}
""" + methods + "\n}"
    with tempfile.TemporaryDirectory(prefix="apocbr-async-zombies-") as directory:
        work = Path(directory)
        sources = []
        for name, text in STUBS.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            sources.append(path)
        for name, text in [("zombie/popman/NetworkZombiePacker.java", fixture),
                           ("zombie/popman/AsyncZombieSenderTest.java", HARNESS),
                           ("zombie/popman/InitializationFixture.java", initialization_fixture),
                           ("zombie/popman/InitializationOrderTest.java", initialization_test)]:
            path = work / name
            path.write_text(text, encoding="utf-8")
            sources.append(path)
        sources += [SRC / "zombie/popman/ServerZombieAsyncSender.java", SRC / "zombie/popman/NetworkZombieList.java"]
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources))
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(work / "classes"), "zombie.popman.AsyncZombieSenderTest"], check=True, timeout=30)
        for properties, capacity, workers in [([], 64, 6),
                (["-Dapocbr.zombieSendCapacity=0", "-Dapocbr.zombiePacketWorkers=0"], 1, 1),
                (["-Dapocbr.zombieSendCapacity=-20", "-Dapocbr.zombiePacketWorkers=-3"], 1, 1),
                (["-Dapocbr.zombieSendCapacity=999", "-Dapocbr.zombiePacketWorkers=999"], 256, 32),
                (["-Dapocbr.zombieSendCapacity=bad", "-Dapocbr.zombiePacketWorkers=bad"], 64, 6)]:
            subprocess.run([str(ROOT / "jdk/bin/java.exe"), *properties, "-cp", str(work / "classes"),
                            "zombie.popman.InitializationOrderTest", str(capacity), str(workers)], check=True, timeout=30)


if __name__ == "__main__":
    main()
