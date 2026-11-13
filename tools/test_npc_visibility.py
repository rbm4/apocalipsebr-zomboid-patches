"""42.22.2 NPC activation flags and zombie eligibility with engine doubles.

Runs extracted production activation statements and victim selection, including
the exact target's native ghost-mode alias. Does not exercise client rendering.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.22.2"


def method(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


actor = (VERSION / "src/zombie/apocalipse/humans/ServerHuman.java").read_text()
victims = (VERSION / "src/zombie/apocalipse/humans/ServerHumanZombieVictims.java").read_text()
native = (VERSION / "decompiled/zombie/characters/IsoPlayer.java").read_text()
activation = actor[actor.index("        this.player.setGodMod("):actor.index("        this.player.setX(")]
selection = method(victims, "private Victim snapshot(ServerHuman human)")
alias = method(native, "public void setGhostMode(boolean aGhostMode, boolean isForced)")
ghost_getter = method(native, "public boolean isGhostMode()")

source = r'''
import zombie.apocalipse.humans.profiles.HumanCheats;
public class VisibilityTest {
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 record Profile(HumanCheats cheats){}
 record Victim(short id,Player body,float x,float y,int z){}
 record Physical(boolean dead){}
 static class State {boolean dead;Physical snapshot(){return new Physical(dead);}}
 static class Player {
  boolean invisible,god,dead;
  void setGodMod(boolean enabled,boolean forced){god=enabled;}
  void setInvisible(boolean enabled,boolean forced){invisible=enabled;}
  boolean isInvisible(){return invisible;}
  boolean isDead(){return dead;}
  short getOnlineID(){return 768;}float getX(){return 1;}float getY(){return 2;}int getZi(){return 0;}
  ALIAS
  GHOST_GETTER
 }
 static class ServerHuman {
  final Player player=new Player();final Profile profile;final State state=new State();
  ServerHuman(HumanCheats flags){profile=new Profile(flags);ACTIVATION}
 }
 SELECTION
 public static void main(String[] args){
  var fixture=new VisibilityTest();
  // All profile combinations: visual invisibility must be independent of ghost immunity.
  for(boolean god:new boolean[]{false,true})
   for(boolean ghost:new boolean[]{false,true})
    for(boolean invisible:new boolean[]{false,true}){
     var human=new ServerHuman(new HumanCheats(god,ghost,invisible));
     check(human.player.god==god);
     check(human.player.isInvisible()==invisible);
     check((fixture.snapshot(human)==null)==(ghost||invisible));
     human.state.dead=true;check(fixture.snapshot(human)==null);
     human.state.dead=false;human.player.dead=true;check(fixture.snapshot(human)==null);
    }
  check(fixture.snapshot(null)==null);
  // Keep the target's surprising alias in the fixture: applying ghost mode last
  // reproduces the original hidden-to-players bug and also clears explicit invisibility.
  var player=new Player();player.setGhostMode(true,true);check(player.isInvisible());
  player.setGhostMode(false,true);check(!player.isInvisible());
  System.out.println("NPC visibility: "+checks+" checks passed (extracted production code, engine doubles)");
 }
}
'''.replace("ALIAS", alias).replace("GHOST_GETTER", ghost_getter) \
    .replace("ACTIVATION", activation).replace("SELECTION", selection)

with tempfile.TemporaryDirectory(prefix="npc_visibility_") as folder:
    work = Path(folder)
    harness = work / "VisibilityTest.java"
    harness.write_text(source, encoding="utf-8")
    cheats = VERSION / "src/zombie/apocalipse/humans/profiles/HumanCheats.java"
    subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-encoding", "UTF-8",
                    "-d", folder, str(harness), str(cheats)], check=True)
    subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", folder, "VisibilityTest"], check=True)
