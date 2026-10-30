"""42.22.0 extracted ID getter/cache and final virtual-decode wrapper.

Uses the production mutation-tracked list with counting subclasses; square,
registry, native decoder and dictionary routing are explicit doubles.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_player_packet_algorithms import extract_method

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "42.22.0"


def fixtures():
    source = (VERSION / "src/zombie/iso/IsoObject.java").read_text(encoding="utf-8")
    vanilla = (VERSION / "decompiled/zombie/iso/IsoObject.java").read_text(encoding="utf-8")
    helper = extract_method(source, "private long apocbrVanillaEntityNetID()")
    native = extract_method(vanilla, "public long getEntityNetID()")
    assert helper[helper.index("{"):] == native[native.index("{"):]
    fields = source[source.index("    private static final boolean apocbrIdTelemetry"):source.index("    private long isoEntityNetId")]
    cleanup = "\n".join(next(line for line in source.splitlines() if line.strip() == assignment) for assignment in (
        "this.isoEntityNetId = -1L;", "this.apocbrIdSquare = null;", "this.apocbrIdObjects = null;", "this.apocbrCachedEntityID = -1L;"))
    assert not any(api in source for api in ("PZArrayList", "apocbrElementsExposed", "apocbrMutationVersion"))
    obj = """package zombie.iso;import java.util.*;import java.nio.*;import java.io.*;
      import zombie.network.GameServer;import zombie.entity.GameEntityManager;
      public class IsoObject {public IsoGridSquare square;public boolean floor;
       private long isoEntityNetId=-1L;private int lastObjectIndex=-1;
       public boolean isFloor(){return floor;}
       public void load(ByteBuffer input,int version,boolean debug)throws IOException{}
    """ + fields + "\n".join(extract_method(source, s) for s in (
        "public void setSquare(IsoGridSquare square)", "public int getObjectIndex()",
        "public long getEntityNetID()", "private long apocbrVanillaEntityNetID()",
        "public final void load(ByteBuffer input, int worldVersion)")) + "\npublic void resetForFixture(){" + cleanup + "}}"
    return {
        "zombie/iso/IsoObject.java": obj,
        "zombie/iso/IsoGridSquare.java": """package zombie.iso;import java.util.*;
          public class IsoGridSquare {public int x=3,y=4,z=1;public List<IsoObject> objects;
           public IsoGridSquare(List<IsoObject> objects){this.objects=objects;}
           public List<IsoObject> getObjects(){return objects;}public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}}""",
        "zombie/network/GameServer.java": "package zombie.network;public class GameServer {public static boolean server=true;}",
        "zombie/entity/GameEntityManager.java": "package zombie.entity;public class GameEntityManager {public static int repairs;public static void checkEntityIDChange(Object o,long a,long b){repairs++;}}",
        "zombie/ApocBRServerTelemetryLite.java": """package zombie;
          public class ApocBRServerTelemetryLite {public static final java.util.Map<String,Long> counters=new java.util.HashMap<>();
           public static void count(String n,long v){counters.merge(n,v,Long::sum);}
           public static long count(String n){return counters.getOrDefault(n,0L);}}""",
        "zombie/world/ApocBRWorldDictionaryDecode.java": """package zombie.world;
          public class ApocBRWorldDictionaryDecode {public static int depth;public static Scope enter(java.nio.ByteBuffer b){depth++;return new Scope();}
           public static class Scope implements AutoCloseable {public void close(){depth--;}}}""",
        "ObjectPortTest.java": HARNESS,
    }


HARNESS = """import java.util.*;import java.nio.*;import java.io.*;import zombie.iso.*;
import zombie.network.GameServer;import zombie.util.list.MutationTrackedArrayList;import zombie.ApocBRServerTelemetryLite;
public class ObjectPortTest {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static class Plain extends ArrayList<IsoObject> {int reads;public int indexOf(Object o){reads++;return super.indexOf(o);}}
 static class Tracked extends MutationTrackedArrayList<IsoObject> {int reads;Runnable onRead;
  public int indexOf(Object o){reads++;if(onRead!=null){Runnable f=onRead;onRead=null;f.run();}return super.indexOf(o);}}
 static IsoObject add(IsoGridSquare s){IsoObject o=new IsoObject();o.setSquare(s);s.objects.add(o);return o;}
 static long expected(IsoObject o){int n=o.getObjectIndex();return n<0?-1:((long)n<<40)+((long)o.square.z<<32)+((long)o.square.y<<16)+o.square.x;}
 static void cache(){Plain plain=new Plain();IsoGridSquare square=new IsoGridSquare(plain);IsoObject o=add(square);
  long id=o.getEntityNetID();int before=plain.reads;o.getEntityNetID();check(plain.reads>before,"plain list never takes cache fast path");
  plain.add(0,new IsoObject());check(o.getEntityNetID()!=id&&o.getEntityNetID()==expected(o),"plain-list shift uses target native ID");
  Tracked tracked=new Tracked();square=new IsoGridSquare(tracked);add(square);o=add(square);o.floor=true;
  id=o.getEntityNetID();check(id==expected(o),"floor uses actual target index, not forced zero");before=tracked.reads;
  int repairs=zombie.entity.GameEntityManager.repairs;
  for(int i=0;i<10;i++)check(o.getEntityNetID()==id,"stable tracked ID");
  check(tracked.reads==before&&zombie.entity.GameEntityManager.repairs==repairs,"cache hit avoids index and registry callback");
  tracked.add(0,new IsoObject());check(o.getEntityNetID()==expected(o)&&o.getEntityNetID()!=id,"insert invalidates");
  Collections.swap(tracked,0,2);check(o.getEntityNetID()==expected(o),"same-size reorder invalidates");
  int slot=tracked.indexOf(o);tracked.subList(slot,slot+1).set(0,o);before=tracked.reads;o.getEntityNetID();check(tracked.reads>before,"sublist replacement invalidates");
  tracked.remove(o);check(o.getEntityNetID()==-1,"detached membership returns sentinel");tracked.add(o);check(o.getEntityNetID()==expected(o),"same object re-added");
  o.resetForFixture();before=tracked.reads;o.getEntityNetID();check(tracked.reads>before,"reset clears cache references/ID");
  GameServer.server=false;before=tracked.reads;o.getEntityNetID();check(tracked.reads>before,"client uses native fallback");GameServer.server=true;
  before=tracked.reads;o.getEntityNetID();check(tracked.reads>before,"returning to server rebuilds cache");
  o.setSquare(null);check(o.getEntityNetID()==-1,"null square");
  IsoObject custom=new IsoObject(){public int getObjectIndex(){return super.getObjectIndex();}};custom.setSquare(square);tracked.add(custom);
  custom.getEntityNetID();before=tracked.reads;custom.getEntityNetID();check(tracked.reads>before,"override bypasses cache");
  IsoObject equal=new IsoObject(){public boolean equals(Object v){return this==v;}public int hashCode(){return System.identityHashCode(this);}};
  equal.setSquare(square);tracked.add(equal);equal.getEntityNetID();before=tracked.reads;equal.getEntityNetID();check(tracked.reads>before,"custom equality bypasses cache");
  IsoObject callback=add(square);tracked.onRead=()->tracked.add(new IsoObject());callback.getEntityNetID();before=tracked.reads;
  callback.getEntityNetID();check(tracked.reads>before,"mutation during calculation invalidates pre-call snapshot");
  check(ApocBRServerTelemetryLite.count("isoObjects.entityId.cacheHits")>=10,"cache counters");
 }
 static class DecodeProbe extends IsoObject {boolean fail;int version;
  public void load(ByteBuffer input,int version,boolean debug)throws IOException{check(zombie.world.ApocBRWorldDictionaryDecode.depth>0,"scope before virtual decode");this.version=version;if(fail)throw new IOException("fixture");}}
 static void decode()throws Exception{DecodeProbe p=new DecodeProbe();p.load(ByteBuffer.allocate(0),249);
  check(p.version==249&&zombie.world.ApocBRWorldDictionaryDecode.depth==0,"decode version and scope restore");
  p.fail=true;try{p.load(ByteBuffer.allocate(0),251);throw new AssertionError("decode failure swallowed");}
  catch(IOException expected){check(zombie.world.ApocBRWorldDictionaryDecode.depth==0,"failure restores scope");}}
 public static void main(String[] args)throws Exception{cache();decode();System.out.println("PASS: "+checks+" target object cache/decode-boundary assertions");}
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-object-port-") as directory:
        work = Path(directory)
        sources = []
        for name, content in fixtures().items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.append(VERSION / "src/zombie/util/list/MutationTrackedArrayList.java")
        args = work / "javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\", "/")+'"' for p in sources), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@"+str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-ea", "-cp", str(work / "classes"), "ObjectPortTest"], check=True)


if __name__ == "__main__":
    main()
