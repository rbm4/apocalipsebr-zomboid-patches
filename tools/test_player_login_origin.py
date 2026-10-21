"""Production login-origin bridge with isolated player/packet fixtures; no live database."""
from pathlib import Path
import subprocess, tempfile
ROOT = Path(__file__).resolve().parents[1]
STUBS = {
 'zombie/savefile/ApocBRPayloadProvenance.java': '''package zombie.savefile; public class ApocBRPayloadProvenance {
 public static int id=3; public static int configuredServerId(){return id;} }''',
 'zombie/characters/IsoPlayer.java': '''package zombie.characters; public class IsoPlayer {
 public boolean dead,connected=true; public Object ai=new Object(); private final Data data=new Data();
 public boolean isDead(){return dead;} public Object getNetworkCharacterAI(){return ai;}
 public Data getModData(){return data;} public static class Data {
 private final java.util.Map<Object,Object> values=new java.util.HashMap<>();
 public Object rawget(Object key){return values.get(key);} public void rawset(Object key,Object value){values.put(key,value);}}
 }''',
 'zombie/network/GameServer.java': '''package zombie.network; public class GameServer {
 public static boolean server=true; public static Thread mainThread=Thread.currentThread();
 public static int packets; public static float x,y,z;
 public static Object getConnectionFromPlayer(zombie.characters.IsoPlayer p){return p.connected?new Object():null;}
 public static void sendTeleport(zombie.characters.IsoPlayer p,float a,float b,float c){packets++;x=a;y=b;z=c;}
 }''',
}
HARNESS = '''package zombie.savefile;
import zombie.characters.IsoPlayer;import zombie.network.GameServer;
public class LoginTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static final String KEY="ApocBR.LastConnectedServerId.v1";
 public static void main(String[] args)throws Exception {
  IsoPlayer foreign=new IsoPlayer();foreign.getModData().rawset(KEY,2.0);
  ApocBRPlayerLogin.getPreviousServerId(foreign);
  check(ApocBRPlayerLogin.hasChangedServer(foreign),"foreign login recognized");
  check(ApocBRPlayerLogin.getPreviousServerId(foreign)==2,"origin preserved before stamping");
  check(foreign.getModData().rawget(KEY).equals(3.0),"next persisted tag is local server");
  foreign.getModData().rawset(KEY,3.0);ApocBRPlayerLogin.getPreviousServerId(foreign);
  check(ApocBRPlayerLogin.hasChangedServer(foreign),"repeat capture/live tag mutation cannot erase origin");
  IsoPlayer reconnect=new IsoPlayer();reconnect.getModData().rawset(KEY,foreign.getModData().rawget(KEY));
  ApocBRPlayerLogin.getPreviousServerId(reconnect);check(!ApocBRPlayerLogin.hasChangedServer(reconnect),"same-server reconnect after save");
  ApocBRPayloadProvenance.id=4;
  IsoPlayer next=new IsoPlayer();next.getModData().rawset(KEY,reconnect.getModData().rawget(KEY));
  ApocBRPlayerLogin.getPreviousServerId(next);check(ApocBRPlayerLogin.getPreviousServerId(next)==3&&ApocBRPlayerLogin.hasChangedServer(next),"next server sees updated tag");
  for(Object bad:new Object[]{"3",Double.NaN,Double.POSITIVE_INFINITY,0.0,-1.0,2.5,2147483648.0}){
   IsoPlayer p=new IsoPlayer();p.getModData().rawset(KEY,bad);ApocBRPlayerLogin.getPreviousServerId(p);
   check(ApocBRPlayerLogin.getPreviousServerId(p)==0&&!ApocBRPlayerLogin.hasChangedServer(p),"malformed tags stay unknown");
  }
  check(!ApocBRPlayerLogin.hasChangedServer(new IsoPlayer()),"untagged/new characters use fallback");
  check(!ApocBRPlayerLogin.teleportToLobby(foreign,Double.NaN,2,0),"finite coordinates required");
  foreign.connected=false;check(!ApocBRPlayerLogin.teleportToLobby(foreign,100.5,200.5,0),"connection required");
  foreign.connected=true;foreign.dead=true;check(!ApocBRPlayerLogin.teleportToLobby(foreign,100.5,200.5,0),"dead player rejected");
  foreign.dead=false;
  check(ApocBRPlayerLogin.teleportToLobby(foreign,100.5,200.5,0),"native server teleport accepted");
  check(ApocBRPlayerLogin.teleportToLobby(foreign,100.5,200.5,0)&&GameServer.packets==1,"repeated requests send only once");
  check(GameServer.x==100.5f&&GameServer.y==200.5f,"destination tile center retained");
  Thread other=new Thread(()->{check(!ApocBRPlayerLogin.hasChangedServer(foreign),"worker cannot read game state");
   check(!ApocBRPlayerLogin.teleportToLobby(next,1,2,0),"worker cannot move player");});other.start();other.join();
  GameServer.server=false;check(!ApocBRPlayerLogin.teleportToLobby(next,1,2,0),"client cannot invoke bridge");
  System.out.println("PASS: immutable login origin, server transitions/reconnects, tag validation and one-shot native server teleport");
 }
}'''
with tempfile.TemporaryDirectory(prefix='apocbr_login_') as folder:
 target=Path(folder);sources=[]
 for name,body in STUBS.items():
  p=target/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');sources.append(p)
 p=target/'LoginTest.java';p.write_text(HARNESS,encoding='utf-8');sources.append(p)
 sources.append(ROOT/'42.21.0/src/zombie/savefile/ApocBRPlayerLogin.java')
 subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',folder,*map(str,sources)],check=True)
 subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',folder,'zombie.savefile.LoginTest'],check=True)
# Login is initiated by the plugin's Lua command, never a reconstruction callback.
s=(ROOT/'42.21.0/src/zombie/savefile/ServerPlayerDB.java').read_text(encoding='utf-8')
assert 'ApocBRPlayerLogin.' not in s
