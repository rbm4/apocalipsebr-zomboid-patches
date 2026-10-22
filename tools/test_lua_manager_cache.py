"""Exercise actual patched Lua-loading methods with a path-resolving filesystem.

The installed 42.21.0 JAR bytecode independently confirms the pre-resolution key.
Lua execution is stubbed; tests do not start a game or change deployed classes.
"""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAR = Path("Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar")


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


HARNESS = r"""
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.*;
public class LuaCacheTest {
    static int checks;
    static final List<String> loaded=new ArrayList<>();
    static final HashSet<String> loading=new HashSet<>();
    static final Map<String,Object> loadedReturn=new HashMap<>();
    static final Map<String,LuaClosure> filenameToClosure=new HashMap<>();
    static final Map<String,Object> luaFunctionMap=new HashMap<>(),luaTableMap=new HashMap<>();
    static final Object env=new Object();
    static final FakeThread thread=new FakeThread();
    static final FakeCaller caller=new FakeCaller();
    static final Map<String,Supplier<LuaReturn>> scripts=new HashMap<>();
    static final Map<String,Integer> executions=new HashMap<>();
    static final String BASE="media/lua/server/WorldGen/WorldGen.lua";
    static final String FEATURES="media/lua/server/WorldGen/Features.lua";
    static final String BIOMES="media/lua/server/WorldGen/Biomes.lua";
    static Map<String,Object> worldgen;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static class ZomboidFileSystem {
        static final ZomboidFileSystem instance=new ZomboidFileSystem();
        String getString(String path){return path.startsWith("Z:/resolved/")?path:"Z:/resolved/"+path;}
    }
    static class IndieFileLoader {
        static InputStreamReader getStreamReader(String path)throws FileNotFoundException {
            String script=path.substring("Z:/resolved/".length());
            if(!scripts.containsKey(script))throw new FileNotFoundException("fixture");
            return new InputStreamReader(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8);
        }
    }
    static class FuncState {static String currentFile,currentfullFile;}
    static class LuaClosure {String script;LuaClosure(String script){this.script=script;}}
    static class LuaCompiler {
        static boolean rewriteEvents;
        static LuaClosure loadis(BufferedReader reader,String filename,Object env)throws IOException {
            return new LuaClosure(reader.readLine());
        }
    }
    static class LuaReturn {
        boolean success;Object value;
        LuaReturn(boolean success,Object value){this.success=success;this.value=value;}
        boolean isSuccess(){return success;}boolean isEmpty(){return value==null;}Object getFirst(){return value;}
        String getErrorString(){return "fixture Lua failure";}RuntimeException getJavaException(){return null;}
        String getLuaStackTrace(){return "fixture stack";}
    }
    static class FakeCaller {
        LuaReturn protectedCall(FakeThread thread,LuaClosure closure){
            executions.merge(closure.script,1,Integer::sum);
            return scripts.get(closure.script).get();
        }
    }
    static class FakeThread {void debugException(Throwable failure){}}
    static class DebugType {static final DebugType Lua=new DebugType();void warn(String format,Object...args){}}
    static class ExceptionLogger {static void logException(Throwable failure){}}
    static class CraftRecipe {static void onLuaFileReloaded(){}}
    static class Dirty {boolean dirty;}
    static class AttachedWeaponDefinitions {static Dirty instance=new Dirty();}
    static class DefaultClothing {static Dirty instance=new Dirty();}
    static class HairOutfitDefinitions {static Dirty instance=new Dirty();}
    static class UnderwearDefinition {static Dirty instance=new Dirty();}
    static class ZombiesZoneDefinition {static boolean dirty;}
    static class IsoWorld {}
    static class LuaManager {}

    /*PRODUCTION_METHODS*/

    public static void main(String[] args){
        Logger.getLogger(IsoWorld.class.getName()).setLevel(Level.OFF);
        scripts.put(BASE,()->{worldgen=new HashMap<>();return new LuaReturn(true,worldgen);});
        scripts.put(FEATURES,()->{
            RunLua(BASE,false);
            worldgen.put("GROUND",Map.of("light_grass","grass"));
            return new LuaReturn(true,null);
        });
        scripts.put(BIOMES,()->{
            RunLua(BASE,false);RunLua(FEATURES,false);
            check(worldgen.get("GROUND") instanceof Map,"require re-executed worldgen and erased feature tables");
            return new LuaReturn(true,"biomes-ready");
        });
        Object first=RunLua(BASE,false);
        check(first==RunLua(BASE.replace('/', '\\'),false),"return cache uses normalized requested key, not resolved IO path");
        check(executions.get(BASE)==1,"same requested file executes once");
        check(loaded.contains(BASE)&&loadedReturn.get(BASE)==first,"loaded and returned values share lookup key");
        check(filenameToClosure.containsKey(("Z:/resolved/"+BASE).replace('/',File.separatorChar)),
                "closure cache still records resolved IO path");
        RunLua(FEATURES,false);
        check(RunLua(BIOMES,false).equals("biomes-ready"),"dependency initialization preserved");
        RunLua(FEATURES,false);RunLua(BIOMES,false);
        check(executions.get(BASE)==1&&executions.get(FEATURES)==1&&executions.get(BIOMES)==1,
                "repeated requires do not reset dependency globals");
        check(!loadedReturn.containsKey(FEATURES),"nil return does not create a fake cached value");
        String recursive="recursive.lua";
        scripts.put(recursive,()->{check(RunLua(recursive,false)==null,"recursive require guard");return new LuaReturn(true,"done");});
        check(RunLua(recursive,false).equals("done")&&loading.isEmpty(),"recursive load cleans loading guard");
        String failed="failed.lua";
        loadedReturn.put(failed,"stale");
        scripts.put(failed,()->new LuaReturn(false,null));
        check(RunLua(failed,false)==null&&RunLua(failed,false)==null,"runtime failure remains nil");
        check(executions.get(failed)==1&&!loadedReturn.containsKey(failed),"vanilla failed-load caching and stale return removal");
        check(RunLua("missing.lua",false)==null&&!loaded.contains("missing.lua")&&loading.isEmpty(),"missing file not cached");
        String throwing="throwing.lua";
        scripts.put(throwing,()->{throw new IllegalStateException("fixture boundary");});
        try{RunLua(throwing,false);throw new AssertionError("expected fixture exception");}
        catch(IllegalStateException expected){check(loading.isEmpty(),"loading guard removed on unexpected exception");}
        check(!LuaCompiler.rewriteEvents,"successful loads restore rewrite flag");
        System.out.println("Passed "+checks+" production Lua loader cache assertions.");
    }
}
"""


def main():
    vanilla = subprocess.run([str(ROOT / "jdk/bin/javap.exe"), "-cp", str(JAR), "-c", "-p",
                              "zombie.Lua.LuaManager"], check=True, capture_output=True, text=True).stdout
    original = vanilla.split("private static java.lang.Object RunLuaInternal(", 1)[1].split(
        "public static java.lang.Object getFunctionObject(", 1)[0]
    assert re.search(r"aload_0\s+\d+: astore_2", original), "JAR must retain pre-resolution filename in slot 2"
    assert re.search(r"Field loaded:.*\n\s*\d+: aload_2\s+\d+: invokeinterface.*List.add", original), "JAR cache key changed; inspect target version"
    assert re.search(r"Field loadedReturn:.*\n\s*\d+: aload_2\s+\d+: aload.*\n\s*\d+: invokevirtual.*HashMap.put", original), "JAR return-cache key changed"
    source = (ROOT / "42.21.0/src/zombie/Lua/LuaManager.java").read_text(encoding="utf-8")
    methods = "\n".join(method(source, signature) for signature in (
        "public static Object RunLua(String filename, boolean bRewriteEvents)",
        "private static Object RunLuaInternal(String filename, boolean bRewriteEvents)"))
    with tempfile.TemporaryDirectory(prefix="apocbr-lua-cache-") as directory:
        work = Path(directory)
        harness = work / "LuaCacheTest.java"
        harness.write_text(HARNESS.replace("/*PRODUCTION_METHODS*/", methods), encoding="utf-8")
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(work), str(harness)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(work), "LuaCacheTest"], check=True)


if __name__ == "__main__":
    main()
