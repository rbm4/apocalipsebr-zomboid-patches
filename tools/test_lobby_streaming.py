"""Compile the production streaming bridge with small server-map doubles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = ROOT / "jdk/bin"
SOURCE = ROOT / "42.22.1/src/zombie/network/ApocBRLobbyStreaming.java"
AREAS = ROOT / "42.22.1/src/zombie/popman/LoadedAreas.java"

FILES = {
    "GameServer.java": """package zombie.network;
public class GameServer {
 public static boolean server;
 public static java.util.ArrayList<zombie.characters.IsoPlayer> Players = new java.util.ArrayList<>();
 public static Engine udpEngine = new Engine();
 public static class Engine { public java.util.ArrayList<zombie.core.raknet.UdpConnection> connections = new java.util.ArrayList<>(); }
}""",
    "ServerMap.java": """package zombie.network;
public class ServerMap {
 public static ServerMap instance = new ServerMap();
 public int requests, x, y;
 public ServerCell cell;
 public java.util.ArrayList<ServerCell> loadedCells=new java.util.ArrayList<>();
 public int getMinX() { return 10; }
 public int getMinY() { return 20; }
 public boolean isInvalidCell(int x, int y) { return x < 0 || y < 0 || x >= 2 || y >= 2; }
 public void loadOrKeepRelevent(int x, int y) { requests++; this.x=x; this.y=y; }
 public ServerCell getCell(int x, int y) { return cell; }
 public static class ServerCell { public boolean isLoaded; public int wx,wy; }
}""",
    "StreamingTest.java": """package zombie.network;
public class StreamingTest {
 static int checks;
 static void check(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
 public static void main(String[] args) throws Exception {
  ServerMap m = ServerMap.instance;
  check(ApocBRLobbyStreaming.keepCellLoaded(640,1280)==-1 && m.requests==0);
  GameServer.server=true;
  for (int[] p : new int[][] {{-1,1280},{640,-1},{1000001,1280},{640,1000001},{0,0},{768,1280}})
   check(ApocBRLobbyStreaming.keepCellLoaded(p[0],p[1])==-1 && m.requests==0);
  check(ApocBRLobbyStreaming.keepCellLoaded(703,1343)==0);
  check(m.x==0 && m.y==0 && m.requests==1);
  m.cell=new ServerMap.ServerCell();
  check(ApocBRLobbyStreaming.keepCellLoaded(704,1344)==0 && m.x==1 && m.y==1);
  m.cell.isLoaded=true;
  check(ApocBRLobbyStreaming.keepCellLoaded(704,1344)==1 && m.requests==3);
  // No timers, workers or retained lease: only explicit calls renew relevance.
  check(m.requests==3);
  zombie.popman.ZombiePopulationManager pop = zombie.popman.ZombiePopulationManager.instance;
  zombie.gameStates.IngameState.instance.paused=true;
  pop.ready=false;
  check(!ApocBRLobbyStreaming.activatePopulation(704,1344));
  check(pop.updates==1 && pop.area.count==1 && pop.area.areas[0]==88 && pop.area.areas[1]==168);
  // Vanilla server-cell areas must not receive the population activation overlay.
  check(pop.serverArea.count==0);
  pop.ready=true;
  check(ApocBRLobbyStreaming.activatePopulation(704,1344));
  zombie.gameStates.IngameState.instance.paused=false;
  int pumped=pop.updates;
  check(ApocBRLobbyStreaming.activatePopulation(704,1344) && pop.updates==pumped);
  zombie.characters.IsoPlayer player=new zombie.characters.IsoPlayer();
  GameServer.Players.add(player);
  pop.updateLoadedAreas();
  check(pop.area.count==2 && pop.area.areas[0]==0 && pop.area.areas[4]==88);
  zombie.popman.LoadedAreas full=new zombie.popman.LoadedAreas(false);
  full.count=64;
  try {ApocBRLobbyStreaming.appendPopulationArea(full);throw new AssertionError();}
  catch(IllegalStateException expected){check(full.count==64);}
  ApocBRLobbyStreaming.releasePopulation();
  check(pop.area.count==1 && pop.area.areas[0]==0);
  int released=pop.areaUpdates;
  ApocBRLobbyStreaming.releasePopulation();
  check(pop.areaUpdates==released);
  ApocBRLobbyStreaming.activatePopulation(704,1344);
  var renewed=ApocBRLobbyStreaming.class.getDeclaredField("populationRenewed");
  renewed.setAccessible(true);renewed.setLong(null,System.nanoTime()-16000000000L);
  pop.updateLoadedAreas();
  check(pop.area.count==1); // expired lease cannot retain the overlay
  ApocBRLobbyStreaming.releasePopulation();
  m.cell.isLoaded=false;
  try {ApocBRLobbyStreaming.activatePopulation(704,1344);throw new AssertionError();}
  catch(IllegalStateException expected){check(pop.area.count==1);}
  GameServer.Players.clear();
  GameServer.server=false;
  zombie.popman.LoadedAreas clientArea=new zombie.popman.LoadedAreas(false);
  clientArea.set();check(clientArea.count==0);
  System.out.println("PASS: production streaming bridge: "+checks+" authority/bounds/readiness/virtual-activation/paused-pump/release checks");
 }
}""",
}

EXTRA = {
 "zombie/gameStates/IngameState.java": "package zombie.gameStates; public class IngameState { public static IngameState instance=new IngameState(); public boolean paused; }",
 "zombie/popman/ZombiePopulationManager.java": """package zombie.popman;
 public class ZombiePopulationManager {
 public static ZombiePopulationManager instance=new ZombiePopulationManager();
 public LoadedAreas area=new LoadedAreas(false),serverArea=new LoadedAreas(true);
 public boolean ready; public int updates,areaUpdates;
 public void updateLoadedAreas(){areaUpdates++;area.set();serverArea.set();}
 public void updateMain(){updates++;updateLoadedAreas();}
 public boolean readyToPause(){return ready;}
 }""",
 "zombie/characters/IsoPlayer.java": """package zombie.characters;
 public class IsoPlayer {public static int numPlayers;public int onlineChunkGridWidth=8;
 public float getX(){return 32;}public float getY(){return 32;}}""",
 "zombie/core/math/PZMath.java": "package zombie.core.math; public class PZMath {public static int fastfloor(float f){return (int)Math.floor(f);}public static int coordmodulo(int x,int d){return Math.floorMod(x,d);}}",
 "zombie/core/raknet/UdpConnection.java": "package zombie.core.raknet; public class UdpConnection {public zombie.iso.Vector3[] connectArea=new zombie.iso.Vector3[4];}",
 "zombie/iso/Vector3.java": "package zombie.iso;public class Vector3 {public float x,y,z;}",
 "zombie/iso/IsoChunkMap.java": "package zombie.iso;public class IsoChunkMap {public static int chunkGridWidth=8;public boolean ignore;public int getWorldXMin(){return 0;}public int getWorldYMin(){return 0;}}",
 "zombie/iso/IsoWorld.java": "package zombie.iso;public class IsoWorld {public static IsoWorld instance=new IsoWorld();public Cell currentCell=new Cell();public static class Cell {public IsoChunkMap[] chunkMap=new IsoChunkMap[4];}}",
}

with tempfile.TemporaryDirectory(prefix="apocbr-lobby-streaming-") as directory:
    work = Path(directory)
    package = work / "zombie/network"
    package.mkdir(parents=True)
    for name, content in FILES.items():
        (package / name).write_text(content, encoding="utf-8")
    for name, content in EXTRA.items():
        path=work/name
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(content,encoding="utf-8")
    subprocess.run([str(JDK / "javac.exe"), "-d", str(work), str(SOURCE), str(AREAS),
                    *map(str, work.rglob("*.java"))], check=True)
    subprocess.run([str(JDK / "java.exe"), "-cp", str(work), "zombie.network.StreamingTest"], check=True)
