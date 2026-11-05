"""Review46 target-source Lua cache/order/exposure fixture; no installed JAR used.

Real extracted Java methods run with filesystem/Lua/checksum/exposer doubles.
The older fixture's 42.21 bytecode check is deliberately not executed here.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_lua_manager_cache import HARNESS, method

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"
REL = "zombie/Lua/LuaManager.java"
DIFF = V / "docs/reports/2026-10-28-merge-46-lua-manager.diff"


def main():
    with tempfile.TemporaryDirectory(prefix="pz-lua-manager-port-") as directory:
        w = Path(directory)
        p = w / "42.22.0/src" / REL
        p.parent.mkdir(parents=True)
        p.write_text((V / "decompiled" / REL).read_text(), encoding="utf-8", newline="\n")
        subprocess.run(["git", "apply", str(DIFF)], cwd=w, check=True)
        source = p.read_text()
        methods = "\n".join(method(source, signature) for signature in [
            "public static Object RunLua(String filename, boolean bRewriteEvents)",
            "private static Object RunLuaInternal(String filename, boolean bRewriteEvents)",
        ])
        harness = HARNESS.replace("/*PRODUCTION_METHODS*/", methods)
        marker = '        System.out.println("Passed "+checks+" production Lua loader cache assertions.");'
        extra = r'''
        loaded.remove(BASE);Object reloaded=RunLua(BASE,true);
        check(reloaded!=first&&executions.get(BASE)==2,"reload invalidates requested key and replaces return");
        check(loadedReturn.get(BASE)==reloaded&&!LuaCompiler.rewriteEvents,"reload return and rewrite reset");
        Object absolute=RunLua("Z:/resolved/"+BASE,false);
        check(absolute!=reloaded&&executions.get(BASE)==3,"different requested aliases are not canonicalized together");
        check(RunLua("Z:/resolved/"+BASE,false)==absolute&&executions.get(BASE)==3,"absolute requested key also caches");
        loaded.clear();loadedReturn.clear();
        check(RunLua(BASE,false)!=reloaded&&executions.get(BASE)==4,"reset enables reinitialization");
        check(filenameToClosure.containsKey(("Z:/resolved/"+BASE).replace('/',File.separatorChar)),"physical closure index retained");
        System.out.println("Target Lua cache: "+checks+" assertions passed");
'''
        assert marker in harness
        harness = harness.replace(marker, extra)
        cache = w / "LuaCacheTest.java"
        cache.write_text(harness, encoding="utf-8")
        start = source.index("            if (GameServer.server) {", source.index("this.setExposed(TradingState.class);"))
        exposure = source[start:source.index("\n\n            for (Class<?> clazz : this.exposed)", start)]
        order = w / "LuaOrderTest.java"
        order.write_text(ORDER.replace("/*LOAD*/", method(source, "public static void LoadDirBase(String sub, boolean onlyChecksum)")).replace("/*EXPOSURE*/", exposure), encoding="utf-8")
        markers = []
        for relative in ["zombie/apocalipse/events/ApocBREventControl", "zombie/network/ApocBRRadioBridge", "zombie/savefile/ApocBRPlayerLogin"]:
            assert (V / "src" / (relative + ".java")).exists()
            package, name = relative.rsplit("/", 1)
            marker_file = w / (relative + ".java")
            marker_file.parent.mkdir(parents=True, exist_ok=True)
            marker_file.write_text("package " + package.replace("/", ".") + ";public final class " + name + " {}")
            markers.append(str(marker_file))
        subprocess.run(["javac", "-d", str(w), str(cache), str(order), *markers], check=True)
        for cls in ["LuaCacheTest", "LuaOrderTest"]:
            subprocess.run(["java", "-cp", str(w), cls], check=True)


ORDER = r'''
import java.io.*;import java.net.*;import java.util.*;
public class LuaOrderTest {
 static List<String>paths=new ArrayList<>(),loadList=new ArrayList<>(),runs=new ArrayList<>();static boolean checksumDone;static int checks;
 static void check(boolean b){if(!b)throw new AssertionError("check "+checks);checks++;}
 static void RunLua(String path){runs.add(path);}
 static void searchFolders(URI uri,File dir)throws IOException {String p=dir.getPath().replace('\\','/').toLowerCase(Locale.ROOT);if(p.contains("base/"))loadList.addAll(List.of("zBase.lua","aBase.lua","SandboxVars.lua"));else if(p.contains("a/common"))loadList.addAll(List.of("shared.lua","aCommon.lua","z.lua"));else if(p.contains("a/version"))loadList.addAll(List.of("aVersion.lua","shared.lua"));else if(p.contains("b/version"))loadList.addAll(List.of("b.lua","shared.lua"));}
 static class ZomboidFileSystem {static ZomboidFileSystem instance=new ZomboidFileSystem();Base base=new Base();static class Base{URI lowercaseUri=new File("base").toURI();}File getMediaFile(String s){return new File("base/"+s);}List<String>getModIDs(){return List.of("A","missing","B");}static File getCanonicalFile(File parent,String name){return new File(parent,name);}String getAbsolutePath(String p){return "resolved:"+p;}}
 static class ChooseGameInfo {static Mod getAvailableModDetails(String id){return id.equals("missing")?null:new Mod(id);}record Mod(String id){String getCommonDir(){return id.equals("A")?"a/common":null;}String getVersionDir(){return id.toLowerCase(Locale.ROOT)+"/version";}}}
 static class PZArrayUtil {static <T>void addAll(List<T>a,List<T>b){a.addAll(b);}}
 static class ExceptionLogger {static void logException(Exception e){throw new AssertionError(e);}}
 static class GameServer {static boolean server=true;}static class GameClient{static boolean client;}
 static class NetChecksum {static Check checksummer=new Check();static class Check{List<String>files=new ArrayList<>();void addFile(String p,String abs){files.add(p);}}}
 static class CoopMaster {static CoopMaster instance=new CoopMaster();int updates;void update(){updates++;}}
 static class Exposure {Set<Class<?>>classes=new HashSet<>();void setExposed(Class<?>c){classes.add(c);}void register(){/*EXPOSURE*/}}
 /*LOAD*/
 public static void main(String[]args)throws Exception {
  var expected=List.of("resolved:aBase.lua","resolved:SandboxVars.lua","resolved:zBase.lua","resolved:aCommon.lua","resolved:aVersion.lua","resolved:shared.lua","resolved:z.lua","resolved:b.lua");
  LoadDirBase("server",false);check(runs.equals(expected));check(loadList.isEmpty());check(paths.equals(List.of("media/lua/server/")));check(NetChecksum.checksummer.files.size()==7&&!NetChecksum.checksummer.files.contains("SandboxVars.lua"));check(CoopMaster.instance.updates==8);
  runs.clear();NetChecksum.checksummer.files.clear();CoopMaster.instance.updates=0;LoadDirBase("server",true);check(runs.isEmpty());check(NetChecksum.checksummer.files.size()==7&&CoopMaster.instance.updates==8);check(paths.size()==1&&loadList.isEmpty());
  checksumDone=true;NetChecksum.checksummer.files.clear();LoadDirBase("server",false);check(runs.equals(expected)&&NetChecksum.checksummer.files.isEmpty());
  var e=new Exposure();GameServer.server=false;e.register();check(e.classes.isEmpty());GameServer.server=true;e.register();check(e.classes.equals(Set.of(zombie.apocalipse.events.ApocBREventControl.class,zombie.network.ApocBRRadioBridge.class,zombie.savefile.ApocBRPlayerLogin.class)));e.register();check(e.classes.size()==3);
  System.out.println("Target Lua load order/exposure: "+checks+" assertions passed");
 }
}
'''


if __name__ == "__main__":
    main()
