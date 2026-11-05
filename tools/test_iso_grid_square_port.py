"""42.22.0 extracted room visibility, lighting initialization and scratch fields.

World/player/lighting/vector collaborators are doubles. Full CalcVisibility,
native LOS and concurrent world access are not executed by this fixture.
"""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_player_packet_algorithms import extract_method

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.22.0"


def fixtures():
    source = (VERSION / "src/zombie/iso/IsoGridSquare.java").read_text(encoding="utf-8")
    vanilla = (VERSION / "decompiled/zombie/iso/IsoGridSquare.java").read_text(encoding="utf-8")
    fields = "\n".join(next(line for line in source.splitlines() if line.strip().startswith(prefix)) for prefix in (
        "public final IsoGridSquare.ILighting[] lighting =", "private static final ThreadLocal<Vector2> tempoTL =",
        "private static final ThreadLocal<Vector2> tempo2TL ="))
    loop = extract_method(source, "for (int i = 0; i < this.lighting.length; i++)")
    visibility = extract_method(source, "public void CalcVisibility(int playerIndex, IsoGameCharacter isoGameCharacter, VisibilityData visibilityData)")
    assert "Vector2 tempo = tempoTL.get();" in visibility and "Vector2 tempo2 = tempo2TL.get();" in visibility
    assert "if (!GameServer.server && this.room != null && this.room.def != null)" in visibility
    assert "protected final List<IsoObject> objects = new ArrayList<>(2);" in source
    assert "public List<IsoObject> getObjects()" in source
    assert "apocbrReadOnlyElements" not in source and ".getElements()" not in source
    prefix = """package zombie.iso;import zombie.characters.IsoPlayer;import zombie.network.*;import zombie.core.math.PZMath;
      public class IsoGridSquare {public int x,y,z;public Room room;public Room getRoom(){return room;}
       public interface ILighting {}public static class Lighting implements ILighting {}
       public static class Room {public Def def=new Def();public Object building=new Object();public int seen=7,calls;public void onSee(){calls++;}}
       public static class Def {public boolean explored;}
    """
    target = prefix + fields + "\npublic void initLighting(){" + loop + "}\n"
    target += "\n".join(extract_method(source, signature) for signature in (
        "public void checkRoomSeen(int playerIndex)", "public void checkRoomSeen(IsoPlayer player)"))
    target += "\npublic static Object first(){return tempoTL.get();}public static Object second(){return tempo2TL.get();}}"
    original = """package zombie.iso;import zombie.characters.IsoPlayer;import zombie.core.math.PZMath;
      public class VanillaSquare {public int x,y,z;public IsoGridSquare.Room room;public IsoGridSquare.Room getRoom(){return room;}
    """ + extract_method(vanilla, "public void checkRoomSeen(int playerIndex)").replace("IsoRoom room", "IsoGridSquare.Room room") + "}"
    target = target.replace("IsoRoom room", "Room room")
    los = (VERSION / "src/zombie/iso/LosUtil.java").read_text(encoding="utf-8")
    slots = re.search(r"public static final int SLOT_COUNT = \d+;", los).group()
    return {
        "zombie/iso/IsoGridSquare.java": target,
        "zombie/iso/VanillaSquare.java": original,
        "zombie/iso/LosUtil.java": "package zombie.iso;public class LosUtil {"+slots+"}",
        "zombie/iso/Vector2.java": "package zombie.iso;public class Vector2 {public float x,y;}",
        "zombie/iso/IsoUtils.java": "package zombie.iso;public class IsoUtils {public static float DistanceToSquared(float ax,float ay,float bx,float by){float x=ax-bx,y=ay-by;return x*x+y*y;}}",
        "zombie/iso/LightingJNI.java": "package zombie.iso;public class LightingJNI {public static boolean init;public static class JNILighting implements IsoGridSquare.ILighting {public final int index;public JNILighting(int i,IsoGridSquare s){index=i;}}}",
        "zombie/network/GameServer.java": "package zombie.network;public class GameServer {public static boolean server;}",
        "zombie/network/ServerLOS.java": "package zombie.network;public class ServerLOS {public static class ServerLighting implements zombie.iso.IsoGridSquare.ILighting {}}",
        "zombie/core/math/PZMath.java": "package zombie.core.math;public class PZMath {public static int fastfloor(float f){return (int)Math.floor(f);}}",
        "zombie/characters/IsoPlayer.java": """package zombie.characters;public class IsoPlayer {
          public static IsoPlayer[] players=new IsoPlayer[4];public float x,y,z;public Object building;public boolean local;public int music;
          public float getX(){return x;}public float getY(){return y;}public float getZ(){return z;}public Object getBuilding(){return building;}
          public boolean isLocalPlayer(){return local;}public void triggerMusicIntensityEvent(String e){music++;}}""",
        "GridPortTest.java": HARNESS,
    }


HARNESS = """import zombie.iso.*;import zombie.characters.IsoPlayer;import zombie.network.*;
import java.util.*;import java.util.concurrent.*;
public class GridPortTest {
 static int checks;static synchronized void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void rooms(){
  for(float distance:new float[]{0,9.999f,10,10.001f,49.999f,50,50.001f})
   for(float z:new float[]{0,.99f,1})for(boolean sameBuilding:new boolean[]{false,true})for(boolean local:new boolean[]{false,true}){
    IsoGridSquare a=new IsoGridSquare();VanillaSquare b=new VanillaSquare();a.room=new IsoGridSquare.Room();b.room=new IsoGridSquare.Room();
    IsoPlayer p=new IsoPlayer(),q=new IsoPlayer();p.x=q.x=.5f+distance;p.y=q.y=.5f;p.z=q.z=z;p.local=q.local=local;
    if(sameBuilding){p.building=a.room.building;q.building=b.room.building;}
    IsoPlayer.players[0]=q;b.checkRoomSeen(0);a.checkRoomSeen(p);
    check(a.room.def.explored==b.room.def.explored,"exploration matches vanilla");
    check(a.room.calls==b.room.calls&&a.room.seen==b.room.seen&&p.music==q.music,"room/music side effects match");
    int before=a.room.calls;a.checkRoomSeen(p);check(a.room.calls==before,"exploration not repeated");
   }
  IsoGridSquare square=new IsoGridSquare();square.room=new IsoGridSquare.Room();square.checkRoomSeen((IsoPlayer)null);
  check(!square.room.def.explored,"null player");square.room=null;square.checkRoomSeen(new IsoPlayer());
  square.room=new IsoGridSquare.Room();square.room.def=null;square.checkRoomSeen(new IsoPlayer());check(square.room.calls==0,"null room definition");
  square.room=new IsoGridSquare.Room();IsoPlayer player=new IsoPlayer();player.x=player.y=.5f;IsoPlayer.players[2]=player;square.checkRoomSeen(2);
  check(square.room.def.explored&&square.room.calls==1,"integer overload delegates");
 }
 static void lighting(){IsoGridSquare square=new IsoGridSquare();GameServer.server=true;square.initLighting();
  check(square.lighting.length==LosUtil.SLOT_COUNT,"slot count");var previous=square.lighting.clone();
  for(var light:square.lighting)check(light instanceof ServerLOS.ServerLighting,"every server slot initialized");
  square.initLighting();for(int i=0;i<square.lighting.length;i++)check(square.lighting[i]!=previous[i],"reuse refreshes slots");
  GameServer.server=false;LightingJNI.init=true;square.initLighting();
  for(int i=0;i<square.lighting.length;i++)check(((LightingJNI.JNILighting)square.lighting[i]).index==i,"JNI index retained");
  LightingJNI.init=false;square.initLighting();for(var light:square.lighting)check(light instanceof IsoGridSquare.Lighting,"fallback client lighting");
 }
 static void threads()throws Exception{var identities=Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>()));
  var barrier=new CyclicBarrier(4);var pool=Executors.newFixedThreadPool(4);var results=new ArrayList<Future<?>>();
  try{for(int i=0;i<4;i++)results.add(pool.submit(()->{try{barrier.await();Object first=IsoGridSquare.first(),second=IsoGridSquare.second();
   check(first!=second&&identities.add(first)&&identities.add(second),"distinct scratch per thread/lane");
   for(int n=0;n<20;n++)check(first==IsoGridSquare.first()&&second==IsoGridSquare.second(),"scratch reused within owner thread");
  }catch(Exception failure){throw new RuntimeException(failure);}}));for(var result:results)result.get(10,TimeUnit.SECONDS);
  }finally{pool.shutdownNow();}check(identities.size()==8,"eight independent scratch objects");}
 public static void main(String[] args)throws Exception{rooms();lighting();threads();System.out.println("PASS: "+checks+" target grid visibility/slot/scratch assertions");}
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-grid-port-") as directory:
        work = Path(directory)
        sources = []
        for name, content in fixtures().items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        args = work / "javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\", "/")+'"' for p in sources), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@"+str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-ea", "-cp", str(work / "classes"), "GridPortTest"], check=True)


if __name__ == "__main__":
    main()
