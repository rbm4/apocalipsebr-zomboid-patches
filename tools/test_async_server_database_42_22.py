"""42.22 administrative read worker: production coordinator/packet, controlled engine/JDBC doubles."""
import ast
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = ROOT / 'jdk/bin'
# Reuse declarations only; importing the login test would execute its 42.21 suite.
tree = ast.parse((ROOT / 'tools/test_postgres_async_login.py').read_text(encoding='utf-8-sig'))
STUBS = ast.literal_eval(next(n.value for n in tree.body if isinstance(n, ast.Assign)
                            and any(isinstance(t, ast.Name) and t.id == 'STUBS' for t in n.targets)))
STUBS.pop('zombie/savefile/ApocBRLoginGuard.java')
STUBS['zombie/savefile/ApocBRServerDatabase.java'] = '''package zombie.savefile; public class ApocBRServerDatabase {
 public static boolean active=true;public static boolean enabled(){return active;}}'''
STUBS['zombie/savefile/ApocBRForegroundDatabase.java'] = '''package zombie.savefile;
public class ApocBRForegroundDatabase implements AutoCloseable {
 public static final ThreadLocal<Boolean> failed=new ThreadLocal<>();
 public static ApocBRForegroundDatabase begin(){return new ApocBRForegroundDatabase();}
 public static void verifySuccess()throws Exception{if(Boolean.TRUE.equals(failed.get()))throw new Exception("private");}
 public void close(){failed.remove();}
}'''
STUBS.update({
'zombie/debug/DebugLog.java': 'package zombie.debug; public class DebugLog {public static void log(String s){if(s.contains("private"))throw new AssertionError(s);}}',
'zombie/characters/Capability.java': 'package zombie.characters; public enum Capability {ReadUserLog}',
'zombie/characters/IsoPlayer.java': 'package zombie.characters; public class IsoPlayer {}',
'zombie/network/Userlog.java': '''package zombie.network; public class Userlog {
 public Userlog(){}public Userlog(zombie.core.network.ByteBufferReader b){}
 public void write(zombie.core.network.ByteBufferWriter b){}
}''',
'zombie/network/ServerWorldDatabase.java': '''package zombie.network; public class ServerWorldDatabase {
 public static final ServerWorldDatabase instance=new ServerWorldDatabase();public static int reads;
 public java.util.ArrayList<Userlog> getUserlog(String user){
 if(zombie.savefile.ApocBRServerDatabase.enabled()&&Thread.currentThread()==GameServer.mainThread)throw new AssertionError("simulation SQL");
 if(!user.equals("original"))throw new AssertionError("pooled packet reused");
 reads++;return new java.util.ArrayList<>(java.util.List.of(new Userlog()));}
}''',
'zombie/network/anticheats/AntiCheat.java': 'package zombie.network.anticheats; public enum AntiCheat {Test}',
'zombie/network/anticheats/PacketValidator.java': '''package zombie.network.anticheats; public class PacketValidator {
 public java.util.EnumMap<AntiCheat,Integer> getCounters(){return new java.util.EnumMap<>(AntiCheat.class);}}''',
'zombie/network/IConnection.java': 'package zombie.network; public interface IConnection {}',
'zombie/network/JSONField.java': 'package zombie.network; public @interface JSONField {}',
'zombie/network/PacketSetting.java': '''package zombie.network; public @interface PacketSetting {
 int ordering();int priority();int reliability();zombie.characters.Capability requiredCapability();int handlingType();}''',
'zombie/network/PacketTypes.java': 'package zombie.network; public class PacketTypes {public enum PacketType {RequestUserLog}}',
'zombie/core/network/ByteBufferReader.java': 'package zombie.core.network; public class ByteBufferReader {public String getUTF(){return "";}public int getInt(){return 0;}}',
'zombie/core/network/ByteBufferWriter.java': 'package zombie.core.network; public class ByteBufferWriter {public void putUTF(String s){}public void putInt(int i){}}',
'se/krka/kahlua/vm/KahluaTable.java': 'package se.krka.kahlua.vm; public class KahluaTable {public void rawset(Object k,Object v){}}',
'zombie/Lua/LuaEventManager.java': 'package zombie.Lua; public class LuaEventManager {public static void triggerEvent(Object...args){}}',
'zombie/Lua/LuaManager.java': '''package zombie.Lua; public class LuaManager {
 public static LuaManager platform=new LuaManager();public se.krka.kahlua.vm.KahluaTable newTable(){return new se.krka.kahlua.vm.KahluaTable();}}''',
'zombie/network/packets/INetworkPacket.java': '''package zombie.network.packets;
public interface INetworkPacket {
 void setData(Object...v);void write(zombie.core.network.ByteBufferWriter b);
 void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c);
 void parseClient(zombie.core.network.ByteBufferReader b,zombie.core.raknet.UdpConnection c);
 void processClient(zombie.core.raknet.UdpConnection c);
 void processServer(zombie.network.PacketTypes.PacketType p,zombie.core.raknet.UdpConnection c);
 static void send(zombie.core.raknet.UdpConnection c,zombie.network.PacketTypes.PacketType t,Object...v){
  if(Thread.currentThread()!=zombie.network.GameServer.mainThread)throw new AssertionError("worker send");
  var p=new zombie.network.packets.service.RequestUserLogPacket();p.setData(v);
  try{var f=p.getClass().getDeclaredField("userLog");f.setAccessible(true);
   if(((java.util.List<?>)f.get(p)).size()!=1)throw new AssertionError("rows missing");
  }catch(ReflectiveOperationException e){throw new AssertionError(e);}c.sent++;
 }
}''',
})
STUBS['zombie/core/raknet/UdpConnection.java'] = STUBS['zombie/core/raknet/UdpConnection.java'].replace(
 'public long guid', '''public boolean allowed=true;public int sent;
 public class Role {public boolean hasCapability(zombie.characters.Capability cap){return allowed;}}
 public Role getRole(){return new Role();}public zombie.network.anticheats.PacketValidator getValidator(){return null;}
 public long guid''')
STUBS['zombie/core/raknet/UdpConnection.java'] = STUBS['zombie/core/raknet/UdpConnection.java'].replace(
    'public class UdpConnection {', 'public class UdpConnection implements zombie.network.IConnection {')
STUBS['zombie/network/GameServer.java'] = STUBS['zombie/network/GameServer.java'].replace(
 'public static Thread mainThread', '''public static boolean server=true;
 public static zombie.characters.IsoPlayer getPlayerByUserName(String u){return null;}
 public static zombie.core.raknet.UdpConnection getConnectionFromPlayer(zombie.characters.IsoPlayer p){return null;}
 public static Thread mainThread''')

HARNESS = r'''
package zombie.savefile;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import zombie.core.raknet.UdpConnection;import zombie.network.GameServer;
public class AsyncServerTest {
 static int checks;static long next;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static UdpConnection connection(){var c=new UdpConnection(++next);GameServer.udpEngine.sessions.put(c.guid,c);return c;}
 @SuppressWarnings("unchecked") static Map<UdpConnection,Object> pending()throws Exception {
  var f=ApocBRAsyncServerDatabase.class.getDeclaredField("pending");f.setAccessible(true);return (Map<UdpConnection,Object>)f.get(null);}
 static void done(UdpConnection c)throws Exception {
  var e=pending().get(c);var f=e.getClass().getDeclaredField("result");f.setAccessible(true);
  try{((CompletableFuture<?>)f.get(e)).get(5,TimeUnit.SECONDS);}catch(ExecutionException expected){}
 }
 static void noCallback(Object value){throw new AssertionError("invalid callback");}
 static void packet(UdpConnection c)throws Exception {
  var p=new zombie.network.packets.service.RequestUserLogPacket();
  var f=p.getClass().getDeclaredField("username");f.setAccessible(true);f.set(p,"original");
  p.processServer(zombie.network.PacketTypes.PacketType.RequestUserLog,c);f.set(p,"reused");
 }
 public static void main(String[] args)throws Exception {
  var c=connection();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var count=new AtomicInteger();
  ApocBRAsyncServerDatabase.submit(c,()->{check(Thread.currentThread()!=GameServer.mainThread,"SQL off simulation");entered.countDown();release.await();return 7;},
   value->{check(Thread.currentThread()==GameServer.mainThread&&value==7,"server completion receives detached result");count.incrementAndGet();});
  check(entered.await(5,TimeUnit.SECONDS),"worker started");
  ApocBRAsyncServerDatabase.submit(c,()->{throw new AssertionError("duplicate SQL");},AsyncServerTest::noCallback);
  for(int i=0;i<10;i++)ApocBRAsyncServerDatabase.update();check(count.get()==0,"tick does not wait");
  release.countDown();done(c);ApocBRAsyncServerDatabase.update();check(count.get()==1,"one completion");
  for(int mode=0;mode<8;mode++) {
   var stale=connection();ApocBRAsyncServerDatabase.submit(stale,()->1,AsyncServerTest::noCallback);done(stale);
   switch(mode){case 0->GameServer.udpEngine.sessions.remove(stale.guid);case 1->GameServer.udpEngine.sessions.put(stale.guid,new UdpConnection(stale.guid));
    case 2->stale.user="changed";case 3->stale.id="changed";case 4->stale.delayed=true;case 5->stale.steam++;
    case 6->zombie.core.Core.gameSaveWorld="changed";case 7->stale.owner++;}
   ApocBRAsyncServerDatabase.update();check(!pending().containsKey(stale),"stale identity discarded "+mode);zombie.core.Core.gameSaveWorld="world";
  }
  var expired=connection();ApocBRAsyncServerDatabase.submit(expired,()->1,AsyncServerTest::noCallback);done(expired);
  var e=pending().get(expired);var expiry=e.getClass().getDeclaredField("expires");expiry.setAccessible(true);expiry.setLong(e,0);
  ApocBRAsyncServerDatabase.update();check(!pending().containsKey(expired),"expired result discarded");
  var bad=connection();ApocBRAsyncServerDatabase.submit(bad,()->{throw new Exception("private");},AsyncServerTest::noCallback);done(bad);ApocBRAsyncServerDatabase.update();
  check(bad.denied==0,"administrative failure does not disconnect");
  var swallowed=connection();ApocBRAsyncServerDatabase.submit(swallowed,()->{ApocBRForegroundDatabase.failed.set(true);return List.of();},AsyncServerTest::noCallback);
  done(swallowed);ApocBRAsyncServerDatabase.update();check(!pending().containsKey(swallowed),"swallowed JDBC failure withholds false empty response");
  var admin=connection();packet(admin);done(admin);ApocBRAsyncServerDatabase.update();check(admin.sent==1&&zombie.network.ServerWorldDatabase.reads==1,"packet response uses worker rows without requery");
  var revoked=connection();packet(revoked);done(revoked);revoked.allowed=false;ApocBRAsyncServerDatabase.update();check(revoked.sent==0,"permission rechecked before send");
  var forbidden=connection();forbidden.allowed=false;packet(forbidden);check(!pending().containsKey(forbidden),"unauthorized read never scheduled");
  ApocBRServerDatabase.active=false;var sqlite=connection();packet(sqlite);
  check(sqlite.sent==1&&!pending().containsKey(sqlite),"SQLite keeps native synchronous reply");ApocBRServerDatabase.active=true;
  var completions=new AtomicInteger();List<UdpConnection> completed=new ArrayList<>();
  for(int i=0;i<4;i++){var q=connection();completed.add(q);ApocBRAsyncServerDatabase.submit(q,()->1,v->completions.incrementAndGet());}
  for(var q:completed)done(q);ApocBRAsyncServerDatabase.update();check(completions.get()<=2,"at most two continuations per tick");
  for(int i=0;i<4;i++)ApocBRAsyncServerDatabase.update();check(completions.get()==4,"remaining continuations drain");
  var block=new CountDownLatch(1);List<UdpConnection> queued=new ArrayList<>();
  for(int i=0;i<32;i++){var q=connection();queued.add(q);ApocBRAsyncServerDatabase.submit(q,()->{block.await();return 1;},AsyncServerTest::noCallback);}
  var overflow=connection();ApocBRAsyncServerDatabase.submit(overflow,()->1,AsyncServerTest::noCallback);
  check(pending().size()==32&&!pending().containsKey(overflow)&&overflow.denied==0,"bounded overload without disconnect");
  for(var q:queued)GameServer.udpEngine.sessions.remove(q.guid);ApocBRAsyncServerDatabase.update();block.countDown();check(pending().isEmpty(),"canceled work released");
  ApocBRAsyncServerDatabase.shutdown();ApocBRAsyncServerDatabase.submit(connection(),()->1,AsyncServerTest::noCallback);
  check(pending().isEmpty(),"shutdown rejects new requests");
  System.out.println("42.22 async server database checks="+checks);
 }
}
'''

with tempfile.TemporaryDirectory(prefix='apocbr_admin_jdbc_') as folder:
    target = Path(folder)
    files = []
    for name, content in {**STUBS, 'zombie/savefile/AsyncServerTest.java': HARNESS}.items():
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        files.append(path)
    src = ROOT / '42.22.0/src/zombie'
    files += [src / 'savefile/ApocBRAsyncServerDatabase.java', src / 'network/packets/service/RequestUserLogPacket.java']
    subprocess.run([str(JDK / 'javac.exe'), '--release', '25', '-d', folder, *map(str, files)], check=True)
    subprocess.run([str(JDK / 'java.exe'), '-cp', folder, 'zombie.savefile.AsyncServerTest'], check=True, timeout=30)

login = (src / 'savefile/ApocBRAsyncLogin.java').read_text()
assert 'ApocBRAsyncServerDatabase.update();' in login
assert 'ApocBRAsyncServerDatabase.shutdown();' in login
connector = (src / 'savefile/ApocBRPostgresConnector.java').read_text()
assert 'if(ApocBRAsyncLogin.onSimulationThread())throw new SQLException' in connector
patched = (src / 'network/packets/service/RequestUserLogPacket.java').read_text()
vanilla = (ROOT / '42.22.0/decompiled/zombie/network/packets/service/RequestUserLogPacket.java').read_text()
for method in ('write', 'parse', 'parseClient', 'processClient'):
    start = '    public void ' + method + '('
    def body(text):
        tail = text[text.index(start):]
        return tail[:tail.index('    @Override')].strip()
    assert body(patched) == body(vanilla), method + ' wire/client behavior changed'
print('Server tick/shutdown hooks and simulation SQL guard preserved')
