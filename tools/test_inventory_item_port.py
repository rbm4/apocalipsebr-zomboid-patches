"""42.22.0 extracted native item parser/encoder/fluid predicate.

Real provenance and load-quality helpers; dictionary routing, virtual item
decoding, objects, logging and fluid objects are doubles. No database required.
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
    source = (VERSION / "src/zombie/inventory/InventoryItem.java").read_text(encoding="utf-8")
    signatures = (
        "public static InventoryItem loadItem(ByteBuffer input, int worldVersion, boolean doSaveTypeCheck, InventoryItem i)",
        "public void storeInByteData(IsoObject o)", "public boolean isAvailableFluidContainer(String type)",
    )
    item = """package zombie.inventory;import java.nio.*;import java.io.*;
      import zombie.iso.IsoObject;import zombie.core.Core;import zombie.core.logger.ExceptionLogger;
      import zombie.debug.*;import zombie.world.WorldDictionary;import zombie.entity.components.fluids.FluidContainer;import zombie.util.StringUtils;
      public class InventoryItem {public int loadByteCount;public ByteBuffer byteData;
       private static final ByteBuffer tempBuffer=ByteBuffer.allocate(20000);
       public FluidContainer fluid;public FluidContainer getFluidContainer(){return fluid;}
       public void load(ByteBuffer input,int version)throws IOException{}
    """ + "\n".join(extract_method(source, s) for s in signatures) + "}"
    # Instance load is too engine-dependent for this isolated parser fixture.
    load = extract_method(source, "public void load(ByteBuffer input, int worldVersion)")
    assert load.index("ApocBRWorldDictionaryDecode.enter(input)") < load.index("this.id = input.getInt()")
    assert 'if (size < 0 || size > input.remaining())' in load
    assert load.index('ApocBRWorldDictionaryDecode.inherit(this.byteData,null)') > load.index('this.byteData.flip()')
    return {
        "zombie/inventory/InventoryItem.java": item,
        "zombie/inventory/InventoryItemFactory.java": "package zombie.inventory;public class InventoryItemFactory {public static InventoryItem next;public static InventoryItem CreateItem(short id){return next;}}",
        "zombie/iso/IsoObject.java": """package zombie.iso;public class IsoObject {public Runnable saving=()->{};
          public void save(java.nio.ByteBuffer b,boolean debug)throws java.io.IOException{saving.run();b.put((byte)1).put((byte)2).putInt(77);}}""",
        "zombie/core/Core.java": "package zombie.core;public class Core {public static boolean debug;}",
        "zombie/core/logger/ExceptionLogger.java": "package zombie.core.logger;public class ExceptionLogger {public static void logException(Exception e){}}",
        "zombie/debug/DebugLog.java": "package zombie.debug;public class DebugLog {public static void log(String s){}}",
        "zombie/debug/LogSeverity.java": "package zombie.debug;public enum LogSeverity {Error}",
        "zombie/debug/DebugType.java": "package zombie.debug;public class DebugType {public static final Log General=new Log();public static class Log {public void printException(Exception e,LogSeverity s){}}}",
        "zombie/world/WorldDictionary.java": "package zombie.world;public class WorldDictionary {public static String getItemTypeDebugString(short id){return " + '"fixture"' + ";}}",
        "zombie/util/StringUtils.java": "package zombie.util;public class StringUtils {public static boolean isNullOrEmpty(String s){return s==null||s.isEmpty();}}",
        "zombie/entity/components/fluids/FluidContainer.java": """package zombie.entity.components.fluids;
          public class FluidContainer {public boolean empty;public String type;
           public boolean isEmpty(){return empty;}public Fluid getPrimaryFluid(){return type==null?null:new Fluid(type);}
           public record Fluid(String type){public String getFluidTypeString(){return type;}}}""",
        "zombie/world/ApocBRWorldDictionaryDecode.java": """package zombie.world;
          import java.nio.ByteBuffer;import zombie.savefile.ApocBRPayloadProvenance;
          public class ApocBRWorldDictionaryDecode {
           public static int depth;public static ApocBRPayloadProvenance current;
           public static class Unavailable extends IllegalStateException {public Unavailable(String s){super(s);}}
           public static Scope enter(ByteBuffer b){return new Scope(ApocBRPayloadProvenance.metadata(b));}
           public static class Scope implements AutoCloseable {final ApocBRPayloadProvenance previous=current;
            Scope(ApocBRPayloadProvenance.Metadata m){depth++;if(m!=null)current=m.origin();}
            public void close(){current=previous;depth--;}}
          }""",
        "ItemPortTest.java": HARNESS,
    }


HARNESS = """import java.nio.*;import java.io.*;import java.util.*;
import zombie.inventory.*;import zombie.savefile.*;import zombie.world.ApocBRWorldDictionaryDecode;
public class ItemPortTest {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static ByteBuffer packet(int declared,int payload){ByteBuffer b=ByteBuffer.allocate(7+payload);b.putInt(declared).putShort((short)1).put((byte)0);for(int n=0;n<payload;n++)b.put((byte)n);return b.flip();}
 static class Probe extends InventoryItem {int bytes=4,version;RuntimeException failure;ApocBRPayloadProvenance seen;
  public void load(ByteBuffer b,int v){version=v;seen=ApocBRWorldDictionaryDecode.current;check(ApocBRWorldDictionaryDecode.depth>0,"scope before virtual load");
   if(failure!=null)throw failure;for(int i=0;i<bytes;i++)b.get();}}
 static void parser()throws Exception {
  Probe probe=new Probe();InventoryItemFactory.next=probe;ByteBuffer b=packet(7,4);
  try(var q=ApocBRPlayerLoadQuality.begin()){
   check(InventoryItem.loadItem(b,249,true,null)==probe,"factory item returned");
   check(probe.loadByteCount==7&&probe.version==249&&b.position()==11,"consumed bytes and historical version");check(q.issues().isEmpty(),"valid item has no issues");}
  for(int consumed:new int[]{3,5}){probe=new Probe();probe.bytes=consumed;b=packet(7,5);
   try(var q=ApocBRPlayerLoadQuality.begin()){
    check(InventoryItem.loadItem(b,251,true,probe)==probe,"existing item returned");
    check(probe.loadByteCount==consumed+3&&b.position()==11,"actual count retained before recovery");
    check(q.issues().equals(List.of("ITEM_LENGTH_MISMATCH")),"length issue recorded");}}
  zombie.core.Core.debug=true;probe=new Probe();probe.bytes=3;b=packet(7,4);
  try{InventoryItem.loadItem(b,251,true,probe);throw new AssertionError("debug mismatch accepted");}catch(IOException expected){check(b.position()==11,"debug recovery before exception");}
  zombie.core.Core.debug=false;
  for(int declared:new int[]{0,-1,100}){b=packet(declared,4);
   try{InventoryItem.loadItem(b,251,true,new Probe());throw new AssertionError("bad length accepted");}catch(IOException expected){check(ApocBRWorldDictionaryDecode.depth==0,"invalid length scope closed");}}
  probe=new Probe();probe.failure=new IllegalArgumentException("bad item");b=packet(7,4);
  try(var q=ApocBRPlayerLoadQuality.begin()){
   check(InventoryItem.loadItem(b,251,true,probe)==null,"ordinary failure skipped");
   check(q.issues().equals(List.of("ITEM_LOAD_FAILED","ITEM_SKIPPED"))&&b.position()==11,"failure issues and recovery");}
  InventoryItemFactory.next=null;b=packet(7,4);
  try(var q=ApocBRPlayerLoadQuality.begin()){
   check(InventoryItem.loadItem(b,251,true,null)==null,"missing factory item");check(q.issues().equals(List.of("ITEM_SKIPPED")),"skipped issue");}
  probe=new Probe();probe.failure=new ApocBRWorldDictionaryDecode.Unavailable("not ready");
  try{InventoryItem.loadItem(packet(7,4),251,true,probe);throw new AssertionError("dictionary failure swallowed");}
  catch(ApocBRWorldDictionaryDecode.Unavailable expected){check(expected==probe.failure&&ApocBRWorldDictionaryDecode.depth==0,"dictionary failure propagated and scope restored");}
 }
 static ApocBRPayloadProvenance origin(){return new ApocBRPayloadProvenance(1,UUID.randomUUID(),UUID.randomUUID());}
 static void provenance()throws Exception {
  var parent=origin();var child=origin();ByteBuffer outer=packet(7,4),inner=packet(7,4);
  ApocBRPayloadProvenance.remember(outer,new ApocBRPayloadProvenance.Metadata(parent,249));
  ApocBRPayloadProvenance.remember(inner,new ApocBRPayloadProvenance.Metadata(child,249));
  try(var scope=ApocBRWorldDictionaryDecode.enter(outer)){
   Probe p=new Probe();InventoryItem.loadItem(inner,249,true,p);
   check(p.seen.equals(child)&&ApocBRWorldDictionaryDecode.current.equals(parent),"nested parser scope restores parent");}
  check(ApocBRWorldDictionaryDecode.depth==0&&ApocBRWorldDictionaryDecode.current==null,"outer scope restored");
  System.setProperty("apocbr.postgres.serverId","1");ApocBRPayloadProvenance.published(parent);
  InventoryItem item=new InventoryItem();var object=new zombie.iso.IsoObject();object.saving=()->ApocBRPayloadProvenance.published(child);
  item.storeInByteData(object);ByteBuffer encoded=item.byteData.duplicate();
  check(encoded.getInt()==0x57564552&&encoded.getInt()==251&&encoded.getInt()==77,"new native header/body");
  var metadata=ApocBRPayloadProvenance.metadata(item.byteData);
  check(metadata.worldVersion()==251&&metadata.origin().equals(parent),"251 metadata uses origin captured before save");
  check(ApocBRPayloadProvenance.metadata(inner).worldVersion()==249,"existing payload version not relabeled");
 }
 static void fluid(){InventoryItem i=new InventoryItem();check(!i.isAvailableFluidContainer("Water"),"no component");
  i.fluid=new zombie.entity.components.fluids.FluidContainer();i.fluid.empty=true;check(i.isAvailableFluidContainer(null),"empty container accepted");
  i.fluid.empty=false;i.fluid.type="Water";check(i.isAvailableFluidContainer("water"),"case-insensitive type");
  check(!i.isAvailableFluidContainer(null)&&!i.isAvailableFluidContainer("")&&!i.isAvailableFluidContainer("Gasoline"),"nonmatching/unspecified fluid rejected");
  i.fluid.type=null;check(!i.isAvailableFluidContainer("Water"),"missing primary fluid");}
 public static void main(String[] args)throws Exception{parser();provenance();fluid();System.out.println("PASS: "+checks+" target item parser/provenance/fluid assertions");}
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-item-port-") as directory:
        work = Path(directory)
        sources = []
        for name, content in fixtures().items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources += [VERSION / "src/zombie/savefile" / name for name in
                    ("ApocBRPayloadProvenance.java", "ApocBRPlayerLoadQuality.java")]
        args = work / "javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\", "/")+'"' for p in sources), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work / "classes"), "@"+str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-ea", "-cp", str(work / "classes"), "ItemPortTest"], check=True)


if __name__ == "__main__":
    main()
