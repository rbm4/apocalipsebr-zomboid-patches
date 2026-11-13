"""Exercise production diagnostic cadence, identity isolation, capacity and scopes."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "zombie/network/IConnection.java": """package zombie.network;
public interface IConnection { long getConnectedGUID(); String getIDStr(); }""",
    "zombie/network/PacketTypes.java": """package zombie.network;
public class PacketTypes { public enum PacketType { Equip, Other } }""",
    "zombie/debug/DebugType.java": """package zombie.debug;
import java.util.*;
public class DebugType {
 public static final DebugType Multiplayer=new DebugType();
 public static final List<String> lines=new ArrayList<>();
 public void error(String f,Object... a){lines.add("ERROR "+String.format(f,a));}
 public void warn(String f,Object... a){lines.add("WARN "+String.format(f,a));}
}""",
    "zombie/network/DiagnosticsTest.java": r"""package zombie.network;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import zombie.debug.DebugType;
public class DiagnosticsTest {
 static int checks;
 record Connection(long getConnectedGUID) implements IConnection {
  public String getIDStr(){return "fixture-"+getConnectedGUID;}
 }
 static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static String last(){var l=DebugType.lines;return l.get(l.size()-1);}
 static void report(IConnection c,PacketTypes.PacketType p,String event,long t){
  ApocBRNetworkDiagnostics.reportAt(c,p,event,true,()->"bad-slot",t);
 }
 public static void main(String[] args)throws Exception {
  var equip=PacketTypes.PacketType.Equip;var other=PacketTypes.PacketType.Other;
  var c=new Connection(1);long interval=ApocBRNetworkDiagnostics.INTERVAL_NANOS;
  report(c,equip,"slot",0);
  check(last().contains("total=1 sinceLast=1 suppressed=0"),"first emitted");
  AtomicInteger evaluated=new AtomicInteger();
  for(int i=0;i<100;i++)ApocBRNetworkDiagnostics.reportAt(c,equip,"slot",true,()->{evaluated.incrementAndGet();return "expensive";},interval-1);
  check(DebugType.lines.size()==1&&evaluated.get()==0,"repeats lazy and silent");
  report(c,equip,"slot",interval);
  check(last().contains("total=102 sinceLast=101 suppressed=100"),"boundary count summary");
  report(c,equip,"slot",interval*2);
  check(last().contains("total=103 sinceLast=1 suppressed=0"),"window reset");
  int n=DebugType.lines.size();
  report(c,other,"slot",0);report(new Connection(2),equip,"slot",0);report(c,equip,"consistency",0);
  check(DebugType.lines.size()==n+3,"connection packet event isolated");
  ApocBRNetworkDiagnostics.reportAt(c,equip,"slot",false,()->"warn",0);
  check(last().startsWith("WARN"),"severity isolated");
  report(null,equip,"slot",0);report(new Connection(0),equip,"slot",0);
  check(last().contains("fixture-0"),"null distinct from GUID zero");
  try(var outer=ApocBRNetworkDiagnostics.receiving(equip)){
   try(var inner=ApocBRNetworkDiagnostics.receiving(other)){
    ApocBRNetworkDiagnostics.error(c,"nested",()->"test");
    check(last().contains("packet=Other"),"inner scope");
   }
   ApocBRNetworkDiagnostics.error(c,"outer",()->"test");
   check(last().contains("packet=Equip"),"outer restored");
   Thread t=new Thread(()->ApocBRNetworkDiagnostics.error(c,"thread",()->"test"));t.start();t.join();
   check(last().contains("packet=unknown"),"scope thread isolated");
  }
  try(var scope=ApocBRNetworkDiagnostics.receiving(equip)){throw new RuntimeException("fixture");}
  catch(RuntimeException expected){}
  ApocBRNetworkDiagnostics.error(c,"after",()->"test");
  check(last().contains("packet=unknown"),"scope removed on exception");
  Field field=ApocBRNetworkDiagnostics.class.getDeclaredField("COUNTS");field.setAccessible(true);
  Map<?,?> buckets=(Map<?,?>)field.get(null);
  for(int i=0;i<ApocBRNetworkDiagnostics.MAX_BUCKETS+10;i++)report(new Connection(100+i),equip,"slot",0);
  check(buckets.size()==ApocBRNetworkDiagnostics.MAX_BUCKETS,"hard capacity bound");
  report(c,equip,"slot",0);
  check(last().contains("total=1 "),"evicted bucket restarts");
  var parallel=new Connection(-10);
  ExecutorService pool=Executors.newFixedThreadPool(4);
  List<Future<?>> jobs=new ArrayList<>();
  for(int i=0;i<4;i++)jobs.add(pool.submit(()->{for(int j=0;j<1000;j++)report(parallel,equip,"parallel",0);}));
  for(var job:jobs)job.get();pool.shutdown();
  report(parallel,equip,"parallel",interval);
  check(last().contains("total=4001 sinceLast=4000 suppressed=3999"),"concurrent counts exact");
  System.out.println("Passed "+checks+" network diagnostic assertions.");
 }
}""",
}


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-diagnostics-") as directory:
        work = Path(directory)
        sources = []
        for name, content in SOURCES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(path)
        sources.append(ROOT / "42.22.1/src/zombie/network/ApocBRNetworkDiagnostics.java")
        jdk = ROOT / "jdk/bin"
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources))
        output = work / "classes"
        subprocess.run([str(jdk / "javac.exe"), "--release", "25", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(jdk / "java.exe"), "-cp", str(output), "zombie.network.DiagnosticsTest"], check=True)


if __name__ == "__main__":
    main()
