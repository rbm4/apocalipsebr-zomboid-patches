package cloud.apocalipse.pz.agent.features.lua;

import cloud.apocalipse.pz.agent.features.lua.api.ApocBrApiBridge;
import cloud.apocalipse.pz.agent.features.lua.power.IsoPowerBank;
import se.krka.kahlua.vm.KahluaTable;

/** Reflection avoids compiling against unrelated account-test doubles. Child classpath must be original-first. */
public final class LuaOriginalSmokeMain {
    private static void runLua(String source)throws Exception {
        Class<?> lua=Class.forName("zombie.Lua.LuaManager");var env=(KahluaTable)lua.getField("env").get(null);
        var closure=se.krka.kahlua.luaj.compiler.LuaCompiler.loadstring(source,"generator-compatibility",env);
        Object caller=lua.getField("caller").get(null);
        caller.getClass().getMethod("protectedCallVoid",se.krka.kahlua.vm.KahluaThread.class,Object.class,Object[].class)
            .invoke(caller,lua.getField("thread").get(null),closure,new Object[0]);
    }
    public static void main(String[] args) throws Exception {
        for(String random:new String[]{"RandStandard","RandLua"}) { Class<?> r=Class.forName("zombie.core.random."+random); r.getMethod("init").invoke(r.getField("INSTANCE").get(null)); }
        Class<?> fs=Class.forName("zombie.ZomboidFileSystem");Object fileSystem=fs.getField("instance").get(null);
        fs.getMethod("setCacheDir",String.class).invoke(fileSystem,java.nio.file.Files.createTempDirectory("apocbr-lua-smoke-").toString());
        fs.getMethod("init").invoke(fileSystem);
        Class<?> server=Class.forName("zombie.network.GameServer"),lua=Class.forName("zombie.Lua.LuaManager");
        if(!server.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("projectzomboid.jar"))
            throw new AssertionError("Wrong game origin");
        server.getField("mainThread").set(null,Thread.currentThread());
        server.getField("server").setBoolean(null,true);
        Object platform=lua.getField("platform").get(null);
        KahluaTable env=(KahluaTable)platform.getClass().getMethod("newEnvironment").invoke(platform);
        lua.getField("env").set(null,env);
        Class<?> type=Class.forName("zombie.Lua.LuaManager$Exposer");
        Object exposer=type.getConstructors()[0].newInstance(lua.getField("converterManager").get(null),platform,env);
        lua.getField("exposer").set(null,exposer);
        Class<?> thread=Class.forName("se.krka.kahlua.vm.KahluaThread");
        lua.getField("thread").set(null,thread.getConstructor(Class.forName("se.krka.kahlua.vm.Platform"),KahluaTable.class).newInstance(platform,env));
        thread.getField("debugOwnerThread").set(lua.getField("thread").get(null),Thread.currentThread());
        type.getMethod("exposeAll").invoke(exposer);
        for(String name:new String[]{"ApocBRLobbyStreaming","ApocBRManagedGenerators","ApocBREventControl","ApocBRRadioBridge",
                "ApocBrApiBridge","ApocBRPlayerLogin","IsoPowerBank","PowerBankBackup"}) {
            if(env.rawget(name)==null)throw new AssertionError("Missing Lua global: "+name);
        }
        if(!(boolean)type.getMethod("shouldExpose",Class.class).invoke(exposer,ApocBrApiBridge.Endpoint.class))throw new AssertionError("Endpoint not exposed");
        if(!(boolean)type.getMethod("shouldExpose",Class.class).invoke(exposer,IsoPowerBank.class))throw new AssertionError("Power bank not exposed");
        server.getField("server").setBoolean(null,false);
        Object client=type.getConstructors()[0].newInstance(lua.getField("converterManager").get(null),platform,platform.getClass().getMethod("newTable").invoke(platform));
        type.getMethod("exposeAll").invoke(client);
        if((boolean)type.getMethod("shouldExpose",Class.class).invoke(client,ApocBrApiBridge.class))throw new AssertionError("Server API leaked to client");
        Class<?> capability=Class.forName("zombie.characters.Capability");
        Object serverEnumId=type.getMethod("getExposedEnumClassId",Class.class).invoke(exposer,capability);
        Object clientEnumId=type.getMethod("getExposedEnumClassId",Class.class).invoke(client,capability);
        if(!serverEnumId.equals(clientEnumId))throw new AssertionError("Custom endpoint registration shifted vanilla network enum IDs");
        server.getField("server").setBoolean(null,true);
        lua.getMethod("init").invoke(null);
        Class<?> cell=Class.forName("zombie.iso.IsoCell"),object=Class.forName("zombie.iso.IsoObject");
        Class<?> sprites=Class.forName("zombie.iso.sprite.IsoSpriteManager");
        Object manager=sprites.getField("instance").get(null);
        Object sprite=sprites.getMethod("getSprite",String.class).invoke(manager,"solarmod_tileset_01_0");
        Class<?> spriteClass=sprite.getClass();spriteClass.getField("name").set(sprite,"solarmod_tileset_01_0");spriteClass.getField("id").setInt(sprite,900001);
        Object intMap=sprites.getField("intMap").get(manager);
        intMap.getClass().getMethod("put",int.class,Object.class).invoke(intMap,900001,sprite);
        var input=java.nio.ByteBuffer.allocate(6).put((byte)1).put((byte)22).putInt(900001).flip();
        Object bank=object.getMethod("factoryFromFileInput",cell,java.nio.ByteBuffer.class).invoke(null,null,input);
        if(!(bank instanceof IsoPowerBank)||input.position()!=2)throw new AssertionError("Power-bank factory result="+bank.getClass().getName()+" position="+input.position()+" sprite="+spriteClass.getMethod("getName").invoke(sprite)+" server="+server.getField("server").getBoolean(null));
        IsoPowerBank solar=(IsoPowerBank)bank;
        solar.applyPowerState(false,0.4);
        if(solar.getFuel()!=4F || solar.isActivated())throw new AssertionError("Invalid native power projection");
        var forged=java.nio.ByteBuffer.allocate(10).putFloat(10).putInt(100).put((byte)1).put((byte)1).flip();
        solar.syncIsoObjectReceive(new zombie.core.network.ByteBufferReader(forged),null);
        if(forged.position()!=10 || solar.getFuel()!=4F || solar.isActivated())throw new AssertionError("Client overwrote bank energy");
        try{solar.completeUpdate(0,1);throw new AssertionError("Acknowledgement outside simulation accepted");}
        catch(IllegalStateException expected){}
        var ordinary=java.nio.ByteBuffer.allocate(6).put((byte)1).put((byte)22).putInt(-1).flip();
        Object generator=object.getMethod("factoryFromFileInput",cell,java.nio.ByteBuffer.class).invoke(null,null,ordinary);
        if(generator instanceof IsoPowerBank)throw new AssertionError("Ordinary generator replaced");
        if(!generator.getClass().getName().equals("zombie.iso.objects.IsoGenerator"))throw new AssertionError("Native generator factory lost");
        generator.getClass().getMethod("update").invoke(generator);
        var data=(KahluaTable)generator.getClass().getMethod("getModData").invoke(generator);
        data.rawset("ApocBRManagedGeneratorId","fixture");
        generator.getClass().getField("activated").setBoolean(generator,true);
        generator.getClass().getField("fuel").setFloat(generator,7F);
        generator.getClass().getMethod("update").invoke(generator);
        if(generator.getClass().getField("fuel").getFloat(generator)!=7F)throw new AssertionError("Suspended tagged generator advanced vanilla depletion");
        data.rawset("ApocBRManagedGeneratorId",null);
        try{generator.getClass().getMethod("update").invoke(generator);throw new AssertionError("Ordinary generator vanilla body skipped");}
        catch(java.lang.reflect.InvocationTargetException expected){if(!(expected.getCause() instanceof NullPointerException))throw expected;}
        var backup=(zombie.iso.objects.IsoGenerator)object.getMethod("factoryFromFileInput",cell,byte.class).invoke(null,null,(byte)22);
        var coordinates=(KahluaTable)platform.getClass().getMethod("newTable").invoke(platform);
        coordinates.rawset("x",1D);coordinates.rawset("y",2D);coordinates.rawset("z",0D);
        cloud.apocalipse.pz.agent.features.lua.power.PowerBankBackup.enable();
        if(!cloud.apocalipse.pz.agent.features.lua.power.PowerBankBackup.claim(backup,coordinates))throw new AssertionError("Backup ownership rejected");
        var currentEnv=(KahluaTable)lua.getField("env").get(null);currentEnv.rawset("backupFixture",backup);
        runLua("backupFixture:applyISABackupState(5, 80, false); backupFixture:resetISABackupClock(); local owner = backupFixture:getISABackupOwner(); assert(owner.x == 1 and not backupFixture:isISABackupActivationAllowed(true)); backupLuaFixturePassed = true");
        if(!Boolean.TRUE.equals(currentEnv.rawget("backupLuaFixturePassed")))throw new AssertionError("Legacy generator colon-call compatibility failed");
        backup.setActivated(true);
        if(backup.isActivated())throw new AssertionError("Uncontrolled backup activation accepted");
        forged.clear();forged.putFloat(10).putInt(100).put((byte)1).put((byte)1).flip();
        backup.syncIsoObjectReceive(new zombie.core.network.ByteBufferReader(forged),null);
        if(forged.position()!=10 || backup.getFuel()!=5 || backup.getCondition()!=80 || backup.isActivated())throw new AssertionError("Client overwrote backup state");
        backup.getModData().rawset(cloud.apocalipse.pz.agent.features.lua.power.PowerBankBackup.OWNER,null);
        cloud.apocalipse.pz.agent.features.lua.power.PowerBankBackup.saving(backup);
        if(backup.getModData().rawget(cloud.apocalipse.pz.agent.features.lua.power.PowerBankBackup.OWNER)==null)throw new AssertionError("Incoming mod data erased authoritative ownership");
        backup.setConnected(false);
        if(cloud.apocalipse.pz.agent.features.lua.power.PowerBankBackup.isControlled(backup))throw new AssertionError("Disconnect did not release backup ownership");
        runLua("backupFixture:setISABackupOwner({x=1,y=2,z=0}); assert(backupFixture:getISABackupOwner() ~= nil); backupFixture:setISABackupOwner(nil); assert(backupFixture:getISABackupOwner() == nil); backupOwnerFixturePassed = true");
        if(!Boolean.TRUE.equals(currentEnv.rawget("backupOwnerFixturePassed")))throw new AssertionError("Legacy owner setter compatibility failed");
        var bytes=LuaModule.transform("zombie.network.GameServer",new net.bytebuddy.ByteBuddy().redefine(LuaLifecycleFixture.class)).make().getBytes();
        var loader=new net.bytebuddy.dynamic.loading.ByteArrayClassLoader.ChildFirst(LuaLifecycleFixture.class.getClassLoader(),
            java.util.Map.of(LuaLifecycleFixture.class.getName(),bytes));
        Class<?> fixture=loader.loadClass(LuaLifecycleFixture.class.getName());
        fixture.getMethod("startServer").invoke(null);
        if(fixture.getField("starts").getInt(null)!=1)throw new AssertionError("Startup skipped native body");
        Runnable pump=()->{try{fixture.getMethod("main",String[].class).invoke(null,(Object)new String[0]);}catch(Exception failure){throw new RuntimeException(failure);}};
        Class.forName("cloud.apocalipse.pz.agent.features.lua.events.EventOriginalAssertions").getMethod("prepare").invoke(null);
        Class.forName("cloud.apocalipse.pz.agent.features.lua.api.ApiOriginalAssertions").getMethod("run",Runnable.class).invoke(null,pump);
        Class.forName("cloud.apocalipse.pz.agent.features.lua.events.EventOriginalAssertions").getMethod("verify").invoke(null);
        if(fixture.getField("ticks").getInt(null)!=1)throw new AssertionError("Loop skipped native body");
        System.out.println("ORIGINAL_LUA_SMOKE_PASS");
    }
}
