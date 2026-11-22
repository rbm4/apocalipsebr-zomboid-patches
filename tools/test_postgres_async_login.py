"""Exercise the production asynchronous login coordinator with controlled JDBC workers."""
from pathlib import Path
import subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
JDK=ROOT/'jdk/bin'
STUBS={
'zombie/core/Core.java': 'package zombie.core; public class Core {public static String gameSaveWorld="world";}',
'zombie/core/raknet/UdpConnection.java': '''package zombie.core.raknet; public class UdpConnection {
 public long guid,steam=1,owner=1;public String user="alice",id="id";public boolean googleAuth,delayed;public int denied;
 public UdpConnection(long guid){this.guid=guid;}public long getConnectedGUID(){return guid;}
 public String getUserName(){return user;}public String getIDStr(){return id;}public long getSteamId(){return steam;}public long getOwnerId(){return owner;}
}''',
'zombie/network/GameServer.java': '''package zombie.network; public class GameServer {
 public static Thread mainThread=Thread.currentThread();public static Engine udpEngine=new Engine();
 public static class Engine {public java.util.Map<Long,zombie.core.raknet.UdpConnection> sessions=new java.util.HashMap<>();
 public zombie.core.raknet.UdpConnection getActiveConnection(long guid){return sessions.get(guid);}}
 public static boolean isDelayedDisconnect(zombie.core.raknet.UdpConnection c){return c.delayed;}
}''',
'zombie/network/ServerWorldDatabase.java': '''package zombie.network; public class ServerWorldDatabase {
 public static class LoginRoles {}public static void useLoginRoles(LoginRoles roles){}
}''',
'zombie/savefile/ApocBRServerDatabase.java':'package zombie.savefile; public class ApocBRServerDatabase {public static boolean enabled(){return true;}}',
'zombie/savefile/ApocBRPlayerDatabase.java':'package zombie.savefile; public class ApocBRPlayerDatabase {public static boolean enabled(){return true;}}',
'zombie/savefile/ApocBRForegroundDatabase.java':'''package zombie.savefile; public class ApocBRForegroundDatabase implements AutoCloseable {
 public static final ThreadLocal<Boolean> scoped=new ThreadLocal<>();public static ApocBRForegroundDatabase beginLogin(){scoped.set(true);return new ApocBRForegroundDatabase();}
 public static void verifySuccess(){} public void close(){scoped.remove();}
}''',
'zombie/savefile/ApocBRLoginGuard.java':'''package zombie.savefile; public class ApocBRLoginGuard {
 public static boolean accepting=true;public static boolean ready(zombie.core.raknet.UdpConnection c){if(!accepting){deny(c);return false;}return true;}
 public static void deny(zombie.core.raknet.UdpConnection c){c.denied++;}
}'''
}
HARNESS=r'''
package zombie.savefile;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import zombie.core.raknet.UdpConnection;import zombie.network.GameServer;
public class AsyncLoginTest {
 static int checks;static long next;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static UdpConnection connection(){var c=new UdpConnection(++next);GameServer.udpEngine.sessions.put(c.guid,c);return c;}
 static void await(CountDownLatch latch)throws Exception{check(latch.await(5,TimeUnit.SECONDS),"worker reached controlled boundary");}
 @SuppressWarnings("unchecked") static Map<UdpConnection,Object> pending()throws Exception{
  var f=ApocBRAsyncLogin.class.getDeclaredField("pending");f.setAccessible(true);return (Map<UdpConnection,Object>)f.get(null);}
 static CompletableFuture<?> result(UdpConnection c)throws Exception{var p=pending().get(c);var f=p.getClass().getDeclaredField("result");f.setAccessible(true);return (CompletableFuture<?>)f.get(p);}
 static void waitResult(UdpConnection c)throws Exception{try{result(c).get(5,TimeUnit.SECONDS);}catch(ExecutionException expected){}}
 static void noCallback(Object value){throw new AssertionError("stale callback executed");}
 public static void main(String[] args)throws Exception {
  ApocBRAsyncLogin.update();check(ApocBRAsyncLogin.onSimulationThread(),"runtime JDBC gate identifies simulation thread");
  var c=connection();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new AtomicInteger();var calls=new AtomicInteger();
  ApocBRAsyncLogin.submit(c,"profile:0",()->{
   check(Thread.currentThread()!=GameServer.mainThread,"JDBC runs away from simulation");
   check(ApocBRAsyncLogin.isWorker()&&Boolean.TRUE.equals(ApocBRForegroundDatabase.scoped.get()),"worker owns deadline scope");
   check(!ApocBRAsyncLogin.onSimulationThread(),"worker allowed JDBC");calls.incrementAndGet();entered.countDown();release.await();return "profile";
  },v->{check(Thread.currentThread()==GameServer.mainThread,"continuation is on simulation");check(v.equals("profile"),"detached result passed");finished.incrementAndGet();});
  await(entered);
  ApocBRAsyncLogin.submit(c,"profile:0",()->{throw new AssertionError("duplicate ran");},AsyncLoginTest::noCallback);
  for(int i=0;i<10;i++)ApocBRAsyncLogin.update();
  check(finished.get()==0&&calls.get()==1,"simulation keeps polling while JDBC is blocked; duplicate coalesced");
  release.countDown();waitResult(c);ApocBRAsyncLogin.update();check(finished.get()==1&&pending().isEmpty(),"exactly one completion");
  // Exception, admission loss, disconnect, same GUID with a new connection, mutable identity, world and timeout.
  var bad=connection();ApocBRAsyncLogin.submit(bad,"auth",()->{throw new Exception("PRIVATE");},AsyncLoginTest::noCallback);
  waitResult(bad);ApocBRAsyncLogin.update();check(bad.denied==1,"failure denies once");
  for(int mode=0;mode<8;mode++) {
   var stale=connection();ApocBRAsyncLogin.submit(stale,"profile",()->"detached",AsyncLoginTest::noCallback);waitResult(stale);
   switch(mode){case 0->GameServer.udpEngine.sessions.remove(stale.guid);case 1->GameServer.udpEngine.sessions.put(stale.guid,new UdpConnection(stale.guid));
    case 2->stale.user="other";case 3->stale.id="other";case 4->stale.delayed=true;case 5->stale.steam++;
    case 6->zombie.core.Core.gameSaveWorld="other";
    case 7->{var p=pending().get(stale);var f=p.getClass().getDeclaredField("expires");f.setAccessible(true);f.setLong(p,0);}}
   ApocBRAsyncLogin.update();check(!pending().containsKey(stale),"stale session removed mode="+mode);
   check(stale.denied==(mode==7?1:0),"only timeout denies active session");zombie.core.Core.gameSaveWorld="world";
  }
  var offline=connection();ApocBRAsyncLogin.submit(offline,"auth",()->true,AsyncLoginTest::noCallback);waitResult(offline);
  ApocBRLoginGuard.accepting=false;ApocBRAsyncLogin.update();check(offline.denied==1,"admission revalidated after I/O");ApocBRLoginGuard.accepting=true;
  var ordered=connection();var hold=new CountDownLatch(1);
  ApocBRAsyncLogin.submit(ordered,"auth",()->{hold.await();return true;},AsyncLoginTest::noCallback);
  ApocBRAsyncLogin.submit(ordered,"profile",()->true,AsyncLoginTest::noCallback);hold.countDown();check(ordered.denied==1&&!pending().containsKey(ordered),"out-of-order phase denied and canceled");
  // A continuation can schedule the next phase without corrupting coordinator iteration.
  var chain=connection();var chained=new AtomicInteger();
  ApocBRAsyncLogin.submit(chain,"read",()->1,v->ApocBRAsyncLogin.submit(chain,"write",()->2,w->chained.set(w)));
  waitResult(chain);ApocBRAsyncLogin.update();waitResult(chain);ApocBRAsyncLogin.update();check(chained.get()==2,"staged continuation completes");
  var two=connection();two.googleAuth=true;check(!ApocBRAsyncLogin.takeSecondFactor(two,"alice"),"unsolicited second factor refused");
  ApocBRAsyncLogin.expectSecondFactor(two,"alice");check(!ApocBRAsyncLogin.takeSecondFactor(two,"other"),"second factor bound to account");
  ApocBRAsyncLogin.expectSecondFactor(two,"alice");check(ApocBRAsyncLogin.takeSecondFactor(two,"alice")&&!ApocBRAsyncLogin.takeSecondFactor(two,"alice"),"proof consumed once");
  // Finished graphs occupy worker capacity until main-thread consumption.
  var reads=new AtomicInteger();var ready=new CountDownLatch(2);var resumed=new AtomicInteger();List<UdpConnection> graphs=new ArrayList<>();
  for(int i=0;i<6;i++){var g=connection();graphs.add(g);ApocBRAsyncLogin.submit(g,"graph",()->{reads.incrementAndGet();ready.countDown();return true;},v->resumed.incrementAndGet());}
  await(ready);check(reads.get()==2,"at most two detached graph results retained before integration");
  ApocBRAsyncLogin.update();check(resumed.get()<=2,"at most two completions per tick");
  for(var g:graphs)GameServer.udpEngine.sessions.remove(g.guid);ApocBRAsyncLogin.update();
  // Bounded retention/queue even if both workers are blocked.
  var block=new CountDownLatch(1);List<UdpConnection> queued=new ArrayList<>();
  for(int i=0;i<32;i++){var q=connection();queued.add(q);ApocBRAsyncLogin.submit(q,"read",()->{block.await();return true;},AsyncLoginTest::noCallback);}
  var overflow=connection();ApocBRAsyncLogin.submit(overflow,"read",()->true,AsyncLoginTest::noCallback);
  check(pending().size()==32&&overflow.denied==1,"hard admission bound");
  for(var q:queued)GameServer.udpEngine.sessions.remove(q.guid);
  ApocBRAsyncLogin.update();block.countDown();check(pending().isEmpty(),"disconnected queued work released");
  ApocBRAsyncLogin.shutdown();var after=connection();ApocBRAsyncLogin.submit(after,"read",()->true,AsyncLoginTest::noCallback);
  check(after.denied==1,"shutdown rejects new work");
  System.out.println("Async login checks="+checks);
 }
}
'''
with tempfile.TemporaryDirectory(prefix='apocbr_async_login_') as folder:
    target=Path(folder);files=[]
    for name,content in STUBS.items():
        path=target/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');files.append(path)
    harness=target/'AsyncLoginTest.java';harness.write_text(HARNESS,encoding='utf-8');files.append(harness)
    files.append(ROOT/'42.21.0/src/zombie/savefile/ApocBRAsyncLogin.java')
    subprocess.run([str(JDK/'javac.exe'),'--release','25','-d',folder,*map(str,files)],check=True)
    subprocess.run([str(JDK/'java.exe'),'-cp',folder,'zombie.savefile.AsyncLoginTest'],check=True,timeout=30)

# Exercise the production ConnectPacket.parse body: follow-up world packets must wait.
connect=(ROOT/'42.21.0/src/zombie/network/packets/connection/ConnectPacket.java').read_text(encoding='utf-8')
start=connect.index('    public void parse(')
method=connect[start:connect.index('    @Override',start)]
followup=r"""
import java.util.*;
public class ConnectContinuationTest {
 static final List<String> events=new ArrayList<>();
 static class ByteBufferReader {}static class IConnection {String getUserName(){return "alice";}}
 static class GameServer {
  static Runnable completion;
  static void receivePlayerConnect(ByteBufferReader b,IConnection c,String user,Runnable done){events.add("load");completion=done;}
  static void sendInitialWorldState(IConnection c){events.add("world");}
 }
 static class PacketTypes {enum PacketType {MetaData}}
 interface INetworkPacket {static void send(IConnection c,PacketTypes.PacketType t,Object...v){events.add("metadata");}}
 METHOD
 public static void main(String[] args) {
  new ConnectContinuationTest().parse(new ByteBufferReader(),new IConnection());
  if(!events.equals(List.of("load")))throw new AssertionError("world packets sent before async load");
  GameServer.completion.run();
  if(!events.equals(List.of("load","world","metadata")))throw new AssertionError("post-connect packet order");
  events.clear();new ConnectContinuationTest().parse(new ByteBufferReader(),new IConnection());GameServer.completion=null;
  if(!events.equals(List.of("load")))throw new AssertionError("failed load sent world packets");
  System.out.println("Deferred connect packet ordering passed");
 }
}
""".replace('METHOD',method)
with tempfile.TemporaryDirectory(prefix='apocbr_connect_continuation_') as folder:
    source=Path(folder)/'ConnectContinuationTest.java';source.write_text(followup,encoding='utf-8')
    subprocess.run([str(JDK/'javac.exe'),'--release','25','-d',folder,str(source)],check=True)
    subprocess.run([str(JDK/'java.exe'),'-cp',folder,'ConnectContinuationTest'],check=True)

src=ROOT/'42.21.0/src/zombie'
login=(src/'network/packets/connection/LoginPacket.java').read_text(encoding='utf-8')
assert 'authSteamClient(steam)' in login and 'authSteamOwner(steam,owner)' in login
assert 'expectSecondFactor(connection,this.username)' in login
profile=(src/'network/packets/connection/LoadPlayerProfilePacket.java').read_text(encoding='utf-8')
assert 'readPostgresProfile(world,identity,slot,coop)' in profile
assert 'serverLoadPostgresClientProfile(' not in profile
server=(src/'network/GameServer.java').read_text(encoding='utf-8')
assert 'copy.put(bb.bb.duplicate())' in server and 'ApocBRAsyncLogin.update();' in server
assert 'finishPostgresPlayer(profile,slot,identity,steamCoop)' in server
connector=(src/'savefile/ApocBRPostgresConnector.java').read_text(encoding='utf-8')
assert 'ApocBRAsyncLogin.onSimulationThread()' in connector
print('Login integration source contracts passed')
