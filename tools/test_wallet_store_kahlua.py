"""Execute the real pack facade and wallet coroutine barrier in the game's Kahlua.
The Lua fixture supplies a detached asynchronous entity/database double.
"""
import json
import sys
from pathlib import Path
sys.dont_write_bytecode = True
import test_api_bridge as fixture

# Native StringLib's pool statistics link to the concrete platform field type.
fixture.STUBS['zombie/Lua/LuaManager.java'] = fixture.STUBS['zombie/Lua/LuaManager.java'].replace(
    'final Platform platform', 'final J2SEPlatform platform')
fixture.STUBS['zombie/Lua/LuaManager.java'] = fixture.STUBS['zombie/Lua/LuaManager.java'].replace(
    ' static {', ' public static StackTraceElement[] insertAnyLuaTraceElements(StackTraceElement[] trace){return trace;}\n public static StackTraceElement[] getLuaStackStrace(se.krka.kahlua.vm.Coroutine coroutine){return new StackTraceElement[0];}\n static {')
fixture.STUBS['zombie/debug/DebugLog.java'] = '''package zombie.debug;
public class DebugLog { public static final DebugLog instance=new DebugLog();
 public static DebugLog getInstance(){return instance;} public boolean isLogTraceFileLocationEnabled(){return false;}
 public static boolean isEnabled(DebugType type){return false;}
 public static void log(String value){System.out.println(value);} }
'''
fixture.STUBS['zombie/debug/DebugType.java'] = '''package zombie.debug;
public enum DebugType { Lua, General, Network, Script;
 public DebugLogStream getLogStream(){return new DebugLogStream();}
 public void error(Object value){System.err.println(value);}
}
'''
fixture.STUBS['zombie/debug/DebugLogStream.java'] = '''package zombie.debug;
public class DebugLogStream extends java.io.PrintStream {public DebugLogStream(){super(System.out);}
 public static String generateCallerPrefix(){return "fixture";}
 public void printException(Throwable error,String a,String b,LogSeverity severity){error.printStackTrace(System.out);}}
'''

wallet = Path('Z:/git/Github/ApocalipseBR-ExtractionMapUtils')
lua = wallet / 'ApocalipseBR-Wallet/common/media/lua'
pack = Path('C:/Users/ricar/Zomboid/Workshop/apocbr/Contents/mods/ApocalisebrPack/42.14/media/lua/server')
sources = {
    'ApocWallet/Protocol': lua / 'shared/ApocWallet/Protocol.lua',
    'ApocWallet/EntityStore': lua / 'server/ApocWallet/EntityStore.lua',
    'ApocWallet/CourierService': lua / 'server/ApocWallet/CourierService.lua',
    'ApocWallet/CourierRoute': lua / 'shared/ApocWallet/CourierRoute.lua',
    'ApocBR/Entities': pack / 'ApocBR/Entities.lua',
}
cases = '\n'.join('case ' + json.dumps(name) + ': source=' + json.dumps(path.read_text(encoding='utf-8')) + '; break;'
                  for name, path in sources.items())
test = json.dumps((wallet / 'tests/wallet-entity-store.lua').read_text(encoding='utf-8'))
fixture.HARNESS = r'''
package zombie.apocbr.api;
import zombie.Lua.LuaManager;
import se.krka.kahlua.vm.*;
import se.krka.kahlua.stdlib.*;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
public class ApiBridgeTest {
 static Object load(String source)throws Exception {
  LuaClosure closure=LuaCompiler.loadstring(source,"wallet-entity-fixture",LuaManager.env);
  Object[] result=LuaManager.caller.pcall(LuaManager.thread,closure,new Object[]{});
  if(!Boolean.TRUE.equals(result[0]))throw new AssertionError(java.util.Arrays.toString(result));
  return result.length>1?result[1]:null;
 }
 public static void main(String[] args)throws Exception {
  BaseLib.register(LuaManager.env);StringLib.register(LuaManager.platform,LuaManager.env);
  TableLib.register(LuaManager.platform,LuaManager.env);CoroutineLib.register(LuaManager.platform,LuaManager.env);
  se.krka.kahlua.j2se.MathLib.register(LuaManager.platform,LuaManager.env);
  LuaManager.env.rawset("print",(JavaFunction)(frame,count)->{if(count>0)System.out.println(frame.get(0));return 0;});
  load(java.nio.file.Files.readString(java.nio.file.Path.of("Z:/SteamLibrary/steamapps/common/ProjectZomboid/stdlib.lua")));
  load("package={loaded={},path=''};");
  LuaManager.env.rawset("require",(JavaFunction)(frame,count)->{
   try {
    String name=(String)frame.get(0);
    KahluaTable loaded=(KahluaTable)((KahluaTable)LuaManager.env.rawget("package")).rawget("loaded");
    Object value=loaded.rawget(name);
    if(value==null){String source;switch(name){
''' + cases + r'''
     default: throw new IllegalArgumentException(name);
    } value=load(source);loaded.rawset(name,value);}
    frame.push(value);return 1;
   }catch(Exception e){throw new RuntimeException(e);}
  });
  load(''' + test + r''');
  System.out.println("Passed actual Kahlua wallet entity checkpoint/coroutine fixture.");
 }
}
'''
fixture.main()
