"""Compile the production streaming bridge with small server-map doubles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = ROOT / "jdk/bin"
SOURCE = ROOT / "42.22.1/src/zombie/network/ApocBRLobbyStreaming.java"

FILES = {
    "GameServer.java": "package zombie.network; public class GameServer { public static boolean server; }",
    "ServerMap.java": """package zombie.network;
public class ServerMap {
 public static ServerMap instance = new ServerMap();
 public int requests, x, y;
 public ServerCell cell;
 public int getMinX() { return 10; }
 public int getMinY() { return 20; }
 public boolean isInvalidCell(int x, int y) { return x < 0 || y < 0 || x >= 2 || y >= 2; }
 public void loadOrKeepRelevent(int x, int y) { requests++; this.x=x; this.y=y; }
 public ServerCell getCell(int x, int y) { return cell; }
 public static class ServerCell { public boolean isLoaded; }
}""",
    "StreamingTest.java": """package zombie.network;
public class StreamingTest {
 static int checks;
 static void check(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
 public static void main(String[] args) {
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
  System.out.println("PASS: production streaming bridge: "+checks+" authority/bounds/origin/readiness/request checks");
 }
}""",
}

with tempfile.TemporaryDirectory(prefix="apocbr-lobby-streaming-") as directory:
    work = Path(directory)
    package = work / "zombie/network"
    package.mkdir(parents=True)
    for name, content in FILES.items():
        (package / name).write_text(content, encoding="utf-8")
    subprocess.run([str(JDK / "javac.exe"), "-d", str(work), str(SOURCE),
                    *map(str, package.glob("*.java"))], check=True)
    subprocess.run([str(JDK / "java.exe"), "-cp", str(work), "zombie.network.StreamingTest"], check=True)
