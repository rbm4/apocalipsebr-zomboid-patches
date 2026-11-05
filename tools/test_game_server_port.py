"""Review48 candidate login/snapshot orchestration and native-source retention.

No full GameServer/Packer/JAR, live database, native player activation, RakNet or
server startup is run. Runs the target async coordinator fixture in an overlay.
"""
from pathlib import Path
import ast
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_lua_manager_cache import method

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"
DIFF = V / "docs/reports/2026-10-28-merge-48-game-server.diff"


def main():
    with tempfile.TemporaryDirectory(prefix="pz-game-server-port-") as directory:
        w = Path(directory)
        for rel in ["zombie/network/GameServer.java", "zombie/popman/NetworkZombiePacker.java"]:
            p = w / "42.22.0/src" / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            if rel == "zombie/network/GameServer.java":
                preimage = (V / "decompiled" / rel).read_text().replace(
                    "if (SlotToConnection[n] == null) {",
                    "if (SlotToConnection[n] == null\n                && !zombie.apocalipse.humans.ServerHumanPresentationIds.reservesConnectionSlot(n)) {",
                )
            else:
                preimage = (ROOT / "42.21.0/src" / rel).read_text()
            p.write_text(preimage, newline="\n")
        subprocess.run(["git", "apply", str(DIFF)], cwd=w, check=True)
        server = (w / "42.22.0/src/zombie/network/GameServer.java").read_text()
        native = (V / "decompiled/zombie/network/GameServer.java").read_text()
        # Native method-level security/packet cleanup changes must survive intact.
        for signature in ["public static boolean canModifyPlayerStats(", "static void receivePlayerStartPMChat(", "public static void doDelayedDisconnect(String", "public static void sendCharacterDeath("]:
            assert method(server, signature) == method(native, signature), signature
        assert method(server, "private static short getFreeSlot()") == method((V / "src/zombie/network/GameServer.java").read_text(), "private static short getFreeSlot()")
        a = native.index("                    if (sendSilentPlayersLimiter.Check())")
        b = native.index("                    NetworkStatistic.getInstance()", a)
        assert native[a:b] in server
        assert 'TradingRequests.getInstance().remove(player);' in server and 'MedicalRequests.getInstance().remove(player);' in server
        assert 'TransactionManager.cancelAllRelevantToUser(player.getOnlineID());' in server
        assert 'UnitTests.runIfEnabled()' not in server and 'sendHitCharacter(' not in server
        assert 'LocaleManager.initialise();' in server and 'setGameMode(GameMode.MULTIPLAYER)' in server
        assert server.index('ApocBRPostgresConnector.prepareStartup();') < server.index('ServerWorldDatabase.instance.create();') < server.index('Roles.init();') < server.index('ApocBRPostgresConnector.finishStartup();')
        assert server.index('ApocBRModEntities.install();') > server.index('LuaManager.init();')
        assert server.index('ApocBREventControl.start();') < server.index('triggerEvent("OnServerStarted")')
        assert server.index('ApocBRBackendBridgeHooks.initialize();') < server.index('triggerEvent("OnServerStarted")')
        assert 'ApocBRServerTelemetryLite.recordLoop(System.nanoTime() - startServerCycle);' in server
        # Run the previous async-login fixture against exact current target support,
        # plus the proposed GameServer source, without asserting against old sources.
        for rel in ["savefile/ApocBRAsyncLogin.java", "network/packets/connection/ConnectPacket.java", "network/packets/connection/LoginPacket.java", "network/packets/connection/LoadPlayerProfilePacket.java", "savefile/ApocBRPostgresConnector.java"]:
            p = w / "42.22.0/src/zombie" / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes((V / "src/zombie" / rel).read_bytes())
        program = (ROOT / "tools/test_postgres_async_login.py").read_text(encoding="utf-8-sig").replace("42.21.0/src", "42.22.0/src")
        tree = ast.parse(program)
        tree.body = [node for node in tree.body if not (isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id in {"ROOT", "JDK"} for t in node.targets))]
        exec(compile(tree, "target_async_login_fixture", "exec"), {"__file__": str(ROOT / "tools/test_postgres_async_login.py"), "ROOT": w, "JDK": Path(shutil.which("javac")).parent})
        # Isolate actual database-dispatch and guard methods, with a recording
        # receivePlayerConnectReady terminal (real native activation not exercised).
        bodies = "\n".join(method(server, signature) for signature in [
            "public static void receivePlayerConnect(ByteBufferReader bb,IConnection connection,String username,Runnable connected)",
            "private static void receivePlayerConnectDatabase(",
        ])
        bodies = bodies.replace("zombie.savefile.", "").replace("zombie.core.", "")
        login = w / "LoginDispatchTest.java"
        login.write_text(LOGIN.replace("/*METHODS*/", bodies), encoding="utf-8")
        subprocess.run(["javac", "-d", str(w), str(login)], check=True)
        subprocess.run(["java", "-cp", str(w), "LoginDispatchTest"], check=True)
        # Real target writer and exact native ZombiePacket.write body, with field
        # serializers/UTF helper doubled. Verify reusable writer/buffer snapshots.
        packer = (w / "42.22.0/src/zombie/popman/NetworkZombiePacker.java").read_text()
        assert 'private final ByteBufferWriter snapshotWriter = new ByteBufferWriter(this.snapshotBuffer);' in packer
        assert 'zombie.zombiePacket.write(this.snapshotWriter);' in packer
        old_packer = (ROOT / "42.21.0/src/zombie/popman/NetworkZombiePacker.java").read_text()
        assert packer.replace('    private final ByteBufferWriter snapshotWriter = new ByteBufferWriter(this.snapshotBuffer);\n', '').replace('zombie.zombiePacket.write(this.snapshotWriter);', 'zombie.zombiePacket.write(this.snapshotBuffer);') == old_packer
        write = method((V / "decompiled/zombie/network/packets/character/ZombiePacket.java").read_text(), "public void write(ByteBufferWriter bb)")
        writer = w / "zombie/core/network/ByteBufferWriter.java"
        writer.parent.mkdir(parents=True, exist_ok=True)
        writer.write_text((V / "decompiled/zombie/core/network/ByteBufferWriter.java").read_text())
        reader = writer.with_name("ByteBufferReader.java")
        reader.write_text("package zombie.core.network;public class ByteBufferReader {public java.nio.ByteBuffer bb;}")
        game = w / "zombie/GameWindow.java"
        game.write_text('package zombie;public class GameWindow {public static void WriteString(java.nio.ByteBuffer b,String s){byte[]v=s.getBytes(java.nio.charset.StandardCharsets.UTF_8);b.putShort((short)v.length).put(v);}}')
        snapshot = w / "SnapshotTest.java"
        snapshot.write_text(SNAPSHOT.replace("/*WRITE*/", write))
        subprocess.run(["javac", "-d", str(w), str(snapshot), str(writer), str(reader), str(game)], check=True)
        subprocess.run(["java", "-cp", str(w), "SnapshotTest"], check=True)
        print("GameServer source retention/startup/reservation contracts passed; no full-class/runtime claim")


LOGIN = r'''
import java.nio.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;
class LoginDispatchTest {
 static boolean coop;static int checks,ready;static IsoPlayer loaded;static byte[]payload;static int callbacks;
 static void check(boolean b){if(!b)throw new AssertionError("check "+checks);checks++;}
 interface IConnection {IsoPlayer getPlayerAt(int n);String getIDStr();String getUserName();String getUserName(int n);}
 static class UdpConnection implements IConnection {IsoPlayer[]players=new IsoPlayer[4];String user="user",slotUser="alice";public IsoPlayer getPlayerAt(int n){return players[n];}public String getIDStr(){return "steam-coop";}public String getUserName(){return user;}public String getUserName(int n){return slotUser;}}
 static class IsoPlayer {}static class Profile {}
 static class ByteBufferReader{ByteBuffer bb;ByteBufferReader(ByteBuffer b){bb=b;}int remaining(){return bb.remaining();}}
 static class Core{static String gameSaveWorld="world";}static class SteamUtils{static boolean steam;static boolean isSteamModeEnabled(){return steam;}}
 static class ApocBRPlayerDatabase{static boolean pg=true;static boolean enabled(){return pg;}}
 static class ApocBRLoginGuard{static boolean allow=true,enabled=true;static int denied;static boolean ready(IConnection c){return allow;}static boolean enabled(){return enabled;}static void deny(IConnection c){denied++;}}
 static class ApocBRForegroundDatabase implements AutoCloseable{static int begins,closes;static ApocBRForegroundDatabase beginLogin(){begins++;return new ApocBRForegroundDatabase();}public void close(){closes++;}}
 static class ApocBRCreatedPlayerHandoff{static IsoPlayer created;static IsoPlayer take(UdpConnection c,int s){var p=created;created=null;return p;}}
 static class ApocBRAsyncLogin {static Callable<Profile>work;static Consumer<Profile>resume;static String phase;static void submit(UdpConnection c,String p,Callable<Profile>w,Consumer<Profile>r){phase=p;work=w;resume=r;}}
 static class ServerPlayerDB {static String identity,world;static int slot,reads,finishes;static boolean steam,fail;static ServerPlayerDB getInstance(){return new ServerPlayerDB();}Profile readPostgresProfile(String w,String i,int s,boolean c){world=w;identity=i;slot=s;steam=c;reads++;return new Profile();}IsoPlayer finishPostgresPlayer(Profile p,int s,String i,boolean c){finishes++;return new IsoPlayer();}}
 static void receivePlayerConnectReady(ByteBufferReader b,IConnection c,String u,IsoPlayer p,Runnable done){if(ServerPlayerDB.fail)throw new IllegalStateException("fixture");ready++;loaded=p;payload=new byte[b.remaining()];b.bb.duplicate().get(payload);done.run();}
 /*METHODS*/
 static ByteBufferReader input(int slot){return new ByteBufferReader(ByteBuffer.wrap(new byte[]{(byte)slot,14,7}));}
 static void reset(){coop=false;SteamUtils.steam=false;ApocBRPlayerDatabase.pg=true;ApocBRLoginGuard.allow=true;ApocBRLoginGuard.enabled=true;ApocBRLoginGuard.denied=0;ApocBRForegroundDatabase.begins=0;ApocBRForegroundDatabase.closes=0;ApocBRCreatedPlayerHandoff.created=null;ApocBRAsyncLogin.work=null;ApocBRAsyncLogin.resume=null;ServerPlayerDB.reads=0;ServerPlayerDB.finishes=0;ServerPlayerDB.fail=false;callbacks=0;ready=0;loaded=null;payload=null;}
 public static void main(String[]args)throws Exception{
  reset();var c=new UdpConnection();var b=input(1);receivePlayerConnect(b,c,"alice",()->callbacks++);check(ready==0&&callbacks==0&&ServerPlayerDB.reads==0);check(b.bb.position()==0&&ApocBRForegroundDatabase.closes==1);b.bb.put(0,(byte)3);var profile=ApocBRAsyncLogin.work.call();ApocBRAsyncLogin.resume.accept(profile);check(ready==1&&callbacks==1&&payload[0]==1&&loaded!=null);check(ServerPlayerDB.identity.equals("user")&&!ServerPlayerDB.steam&&ServerPlayerDB.slot==1);check(ApocBRAsyncLogin.phase.equals("player-connect:1"));
  reset();c=new UdpConnection();coop=true;SteamUtils.steam=true;receivePlayerConnect(input(0),c,"alice",()->callbacks++);ApocBRAsyncLogin.resume.accept(ApocBRAsyncLogin.work.call());check(ServerPlayerDB.identity.equals("steam-coop")&&ServerPlayerDB.steam&&callbacks==1);
  reset();c=new UdpConnection();var created=new IsoPlayer();ApocBRCreatedPlayerHandoff.created=created;receivePlayerConnect(input(2),c,"alice",()->callbacks++);check(ready==1&&loaded==created&&callbacks==1&&ApocBRAsyncLogin.work==null);
  for(int slot:new int[]{-1,4,127}){reset();receivePlayerConnect(input(slot),new UdpConnection(),"alice",()->callbacks++);check(ApocBRLoginGuard.denied==1&&ready==0&&ApocBRAsyncLogin.work==null);}
  reset();receivePlayerConnect(new ByteBufferReader(ByteBuffer.wrap(new byte[]{0,12})),new UdpConnection(),"alice",()->callbacks++);check(ApocBRLoginGuard.denied==1&&ready==0);
  reset();c=new UdpConnection();c.players[0]=new IsoPlayer();receivePlayerConnect(input(0),c,"alice",()->callbacks++);check(ApocBRLoginGuard.denied==1&&ready==0);
  reset();c=new UdpConnection();receivePlayerConnect(input(0),c,"alice",()->callbacks++);c.slotUser="mallory";ApocBRAsyncLogin.resume.accept(ApocBRAsyncLogin.work.call());check(ApocBRLoginGuard.denied==1&&ready==0&&ServerPlayerDB.finishes==0);
  reset();c=new UdpConnection();receivePlayerConnect(input(0),c,"alice",()->callbacks++);c.players[0]=new IsoPlayer();ApocBRAsyncLogin.resume.accept(ApocBRAsyncLogin.work.call());check(ready==0&&ServerPlayerDB.finishes==0);
  reset();ApocBRLoginGuard.allow=false;receivePlayerConnect(input(0),new UdpConnection(),"alice",()->callbacks++);check(ready==0&&ApocBRForegroundDatabase.begins==0);
  reset();ApocBRPlayerDatabase.pg=false;receivePlayerConnect(input(0),new UdpConnection(),"alice",()->callbacks++);check(ready==1&&loaded==null&&callbacks==1&&ApocBRAsyncLogin.work==null);
  reset();ApocBRPlayerDatabase.pg=false;ServerPlayerDB.fail=true;receivePlayerConnect(input(0),new UdpConnection(),"alice",()->callbacks++);check(ApocBRLoginGuard.denied==1&&ApocBRForegroundDatabase.closes==1);
  reset();ApocBRPlayerDatabase.pg=false;ApocBRLoginGuard.enabled=false;ServerPlayerDB.fail=true;try{receivePlayerConnect(input(0),new UdpConnection(),"alice",()->callbacks++);throw new AssertionError();}catch(IllegalStateException expected){check(ApocBRForegroundDatabase.closes==1&&ApocBRLoginGuard.denied==0);}
  System.out.println("GameServer login dispatch: "+checks+" assertions passed (recording DB/activation ports)");
 }
}
'''

SNAPSHOT = r'''
import java.nio.*;import java.util.*;import zombie.core.network.ByteBufferWriter;
class SnapshotTest {
 static int checks;static void check(boolean b){if(!b)throw new AssertionError("check "+checks);checks++;}
 static class Packet {short id=7,health=100,booleanVariables=9,target=2,timeSinceSeenFlesh=3,smParamTargetAngle=4,speedMod=5;byte update,z=1,predictionType=2,realZ=3;float x=1,y=2,dirAngleRads=3,realX=4,realY=5;int outfitId=99,skinTextureIndex=10;State walkType=State.A,realState=State.B;Field reanimatedBodyId=new Field(),pfb=new Field(),grappledBy=new Field();String sharedGrappleType="grapple";
 /*WRITE*/
 }enum State{A,B}static class Field{void write(ByteBufferWriter w){w.putInt(123);}}
 public static void main(String[]args){var p=new Packet();var buffer=ByteBuffer.allocate(512);var writer=new ByteBufferWriter(buffer);for(int flags=0;flags<256;flags++){p.update=(byte)flags;buffer.clear();p.write(writer);int count=buffer.position();byte[] frozen=Arrays.copyOf(buffer.array(),count);var independent=ByteBuffer.allocate(512);p.write(new ByteBufferWriter(independent));check(independent.position()==count);check(Arrays.equals(frozen,Arrays.copyOf(independent.array(),count)));buffer.put(0,(byte)99);check(frozen[0]!=(byte)99);}System.out.println("Zombie snapshot writer: "+checks+" assertions passed (native writer/body, field/UTF doubles)");}
}
'''


if __name__ == "__main__":
    main()
