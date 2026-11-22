"""Execute actual vanilla/patched Event.trigger bodies with mutable callback lists and exception stubs."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = ROOT.parent/'jdk/bin'


def trigger(path):
    text = path.read_text()
    start = text.index('public boolean trigger(')
    end = text.index('{', start)+1
    depth = 1
    while depth:
        depth += (text[end]=='{')-(text[end]=='}')
        end += 1
    return text[start:end]


class LuaCallbackProbeTest(unittest.TestCase):
    def test_order_mutation_errors_and_both_debug_modes(self):
        source = r'''
package zombie.Lua;
import java.util.*;
import zombie.ApocBRClientTelemetryLite;
class KahluaTable {}
class Prototype {String file="test.lua",filename="test.lua",name="test";int[] lines={12};}
class LuaClosure {Prototype prototype=new Prototype();Runnable body;LuaClosure(Runnable r){body=r;}}
class LuaCaller {void protectedCallVoid(Object thread,LuaClosure closure,Object[] params){closure.body.run();}}
class LuaManager {static Object thread=new Object();}
class GameProfiler {
 static GameProfiler getInstance(){return new GameProfiler();}
 ProfileArea profile(String name){return new ProfileArea();}
 static class ProfileArea implements AutoCloseable{public void close(){}}
}
class DebugOptions {
 static DebugOptions instance=new DebugOptions();final Checks checks=new Checks();
 static class Checks{Flag slowLuaEvents=new Flag();}static class Flag{boolean v;boolean getValue(){return v;}}
}
class DebugType {static DebugType Lua=new DebugType();void warn(String format,Object... args){}}
class ExceptionLogger {static void logException(Exception error){Base.log.add("error:"+error.getMessage());}}
class Base {
 static ArrayList<String> log;final ArrayList<LuaClosure> callbacks=new ArrayList<>();String name="OnTick";
 boolean trigger(KahluaTable env,LuaCaller caller,Object[] params){return false;}
}
class Vanilla extends Base {VANILLA}
class Patched extends Base {PATCHED}
public class LuaCallbackHarness {
 static String run(Base event,int scenario){
  Base.log=new ArrayList<>();LuaClosure[] first=new LuaClosure[1];
  LuaClosure second=new LuaClosure(()->Base.log.add("second"));
  first[0]=new LuaClosure(()->{
   Base.log.add("first");
   if(scenario==1)event.callbacks.remove(first[0]);
   if(scenario==2)event.callbacks.remove(second);
   if(scenario==3)event.callbacks.add(new LuaClosure(()->Base.log.add("added")));
   if(scenario==4)throw new IllegalStateException("callback");
   if(scenario==6)first[0].prototype=null;
  });
  if(scenario!=5){event.callbacks.add(first[0]);event.callbacks.add(second);}
  if(scenario==7)event.callbacks.set(0,null);
  boolean result=event.trigger(new KahluaTable(),new LuaCaller(),new Object[0]);
  return Base.log+"/"+event.callbacks.size()+"/"+result;
 }
 public static void main(String[] args){
  for(boolean debug:new boolean[]{false,true})for(int scenario=0;scenario<8;scenario++){
   DebugOptions.instance.checks.slowLuaEvents.v=debug;
   String expected=run(new Vanilla(),scenario);
   ApocBRClientTelemetryLite.beginFrame(true);String actual=run(new Patched(),scenario);
   ApocBRClientTelemetryLite.endFrame(0,0);
   if(!expected.equals(actual))throw new AssertionError(expected+" != "+actual);
  }
  ApocBRClientTelemetryLite.shutdown();
 }
}
'''.replace('VANILLA', trigger(ROOT/'decompiled/zombie/Lua/Event.java')).replace(
            'PATCHED', trigger(ROOT/'src/zombie/Lua/Event.java'))
        with tempfile.TemporaryDirectory(prefix='apocbr-lua-probe-') as temp:
            work = pathlib.Path(temp)
            harness = work/'LuaCallbackHarness.java'
            harness.write_text(source)
            subprocess.run([str(JDK/'javac.exe'), '--release', '25', '-d', str(work),
                            str(ROOT/'src/zombie/ApocBRClientTelemetryLite.java'), str(harness)], check=True)
            for enabled in ('false', 'true'):
                subprocess.run([str(JDK/'java.exe'), f'-Dapocbr.clientTelemetry.enabled={enabled}',
                                f'-Dapocbr.clientTelemetry.path={work/"capture.ndjson"}',
                                '-cp', str(work), 'zombie.Lua.LuaCallbackHarness'], check=True)


if __name__ == '__main__':
    unittest.main()
