"""42.22.1 production name assignment/reservation methods with engine doubles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.1"


def method(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


factory = (V / "src/zombie/apocalipse/humans/ServerHumanFactory.java").read_text()
actor = (V / "src/zombie/apocalipse/humans/ServerHuman.java").read_text()
native = (V / "decompiled/zombie/characters/IsoPlayer.java").read_text()
assignment = "\n".join(line.strip() for line in actor.splitlines()
                       if "this.player.setUsername(" in line or "this.player.setDisplayName(" in line)
methods = "\n".join(method(factory, signature) for signature in (
    "public synchronized void registerDormant(",
    "private void checkOnlineName(", "private static String canonicalName("))
# Startup hydration and retired entries must use the same reservation identity.
assert "canonicalName(state.definition().username())" not in factory
assert factory.count("canonicalName(state.definition().displayName())") == 2
wire = (V / "decompiled/zombie/network/packets/connection/ConnectedPacket.java").read_text()
assert "b.putUTF(this.player.username);" in wire
render = (V / "decompiled/zombie/characters/IsoGameCharacter.java").read_text()
assert "this.isoPlayer.resetDisplayName();\n            String nameStr = this.isoPlayer.getDisplayName();" in render

source = '''
import java.util.*;
import java.text.Normalizer;
public class NamesTest {
 static int checks;
 static void check(boolean ok){if(!ok)throw new AssertionError();checks++;}
 static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException ok){checks++;return;}throw new AssertionError();}
 record ServerHumanDefinition(String key,String username,String displayName){String profileId(){return "idle";}}
 static class HumanLifecycle {HumanLifecycle(ServerHumanDefinition d,Runnable owner){}}
 static class ServerHumanProfiles {static ServerHumanProfiles registry(){return new ServerHumanProfiles();}void require(String id){}}
 static class GameServer {static Map<Integer,Player> IDToPlayerMap=new HashMap<>();}
 static class Player {
  String username,displayName;
  void setUsername(String s){username=s;}void setDisplayName(String s){displayName=s;}
  String getUsername(){return username;}
  String getUsername(Boolean first,Boolean disguise){return username;}
  static class Options {boolean getValue(){return false;}}
  static class GameClient {static boolean client=true;}
  void updateDisguisedState(){}short getOnlineID(){return 768;}
  static class WorldMapRemotePlayer {void setPlayer(Player player){}}
  static class WorldMapRemotePlayers {
   static WorldMapRemotePlayers instance=new WorldMapRemotePlayers();
   WorldMapRemotePlayer getPlayerByID(short id){return null;}
  }
  static class ServerOptions {
   static ServerOptions instance=new ServerOptions();
   Options showFirstAndLastName=new Options(),hideDisguisedUserName=new Options(),usernameDisguises=new Options();
   static ServerOptions getInstance(){return new ServerOptions();}
  }
  GET_DISPLAY
  RESET_DISPLAY
 }
 final Set<String> reservedNames=new HashSet<>(),retiredKeys=new HashSet<>();
 final Map<String,HumanLifecycle> instances=new HashMap<>();
 Player player=new Player();void requireOwner(){}
 void present(ServerHumanDefinition definition){ASSIGNMENT}
 METHODS
 public static void main(String[] args){
  NamesTest f=new NamesTest();
  var v=new ServerHumanDefinition("agente-v","NPC_AgenteV","Agente V");
  f.registerDormant(v);f.present(v);
  check(f.player.getUsername().equals("Agente V"));
  f.player.resetDisplayName();check(f.player.getDisplayName().equals("Agente V"));
  check(v.username().equals("NPC_AgenteV"));
  rejects(()->f.registerDormant(new ServerHumanDefinition("other","NPC_Other"," agente v ")));
  f.instances.clear(); // Reservation survives native/logical removal.
  rejects(()->f.registerDormant(new ServerHumanDefinition("later","NPC_Later","Agente V")));
  Player real=new Player();real.setUsername("AGENTE V");GameServer.IDToPlayerMap.put(1,real);
  rejects(()->new NamesTest().registerDormant(v));GameServer.IDToPlayerMap.clear();
  var unicode=new ServerHumanDefinition("u","NPC_U","Érica Silva");
  f.registerDormant(unicode);f.present(unicode);f.player.resetDisplayName();
  check(f.player.getDisplayName().equals("Érica Silva"));
  rejects(()->f.registerDormant(new ServerHumanDefinition("u2","NPC_U2","E\\u0301rica Silva")));
  // Distinct display names are the actual chat/native identities, even with a legacy alias shared.
  f.registerDormant(new ServerHumanDefinition("distinct","NPC_AgenteV","Agente W"));
  System.out.println("NPC display names: "+checks+" checks passed; engine doubles, extracted production methods");
 }
}
'''.replace("GET_DISPLAY", method(native, "public String getDisplayName()")) \
    .replace("RESET_DISPLAY", method(native, "public void resetDisplayName()")) \
    .replace("ASSIGNMENT", assignment).replace("METHODS", methods) \
    .replace("zombie.characters.IsoPlayer", "Player")

with tempfile.TemporaryDirectory(prefix="npc_names_") as temp:
    path = Path(temp)
    (path / "NamesTest.java").write_text(source, encoding="utf-8")
    subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "-encoding", "UTF-8", str(path / "NamesTest.java")], check=True)
    subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", temp, "NamesTest"], check=True)
