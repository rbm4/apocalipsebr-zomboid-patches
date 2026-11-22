package cloud.apocalipse.pz.agent.features.lua;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.*;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Server Lua APIs and their original-game integration contracts. */
public final class LuaModule implements AgentModule {
    private boolean enabled;
    public static final List<String> TARGETS=List.of("zombie.Lua.LuaManager$Exposer","zombie.Lua.LuaManager",
        "zombie.network.GameServer","zombie.network.ServerMap","zombie.popman.LoadedAreas",
        "zombie.iso.objects.IsoGenerator","zombie.iso.IsoObject");
    @Override public String name() { return "server-lua"; }
    @Override public void initialize() {
        enabled=AgentConfiguration.luaEnabled();
        if(enabled) HookPreflight.validate(TARGETS,hookChecks(),LuaModule::transform);
    }
    @Override public String disabledReason() { return enabled?null:"Disabled by apocbr.lua.enabled"; }
    @Override public List<HookCheck> hookChecks() {
        var checks=new ArrayList<HookCheck>();
        String h="cloud.apocalipse.pz.agent.features.lua.LuaRuntime";
        String[][] hooks={
            {TARGETS.get(0),"exposeAll","expose"},{TARGETS.get(1),"init","luaInitialized"},
            {TARGETS.get(2),"startServer","start"},{TARGETS.get(2),"main","update"},
            {TARGETS.get(3),"QueuedQuit","shutdown"},
            {TARGETS.get(3),"QueuedQuit","cleanup"},{TARGETS.get(4),"set","populationArea"},{TARGETS.get(5),"update","beforeGeneratorUpdate"},
            {TARGETS.get(5),"update","skipGeneratorDepletion"},{TARGETS.get(5),"addToWorld","generatorAdded"},
            {TARGETS.get(5),"removeFromWorld","generatorRemoved"},{TARGETS.get(5),"load","generatorLoaded"},
            {TARGETS.get(5),"save","generatorSaving"},{TARGETS.get(5),"remove","denyRemoval"},
            {TARGETS.get(5),"setFuel","denyChange"},{TARGETS.get(5),"setCondition","denyChange"},
            {TARGETS.get(5),"setActivated","denyChange"},{TARGETS.get(5),"setConnected","denyChange"},
            {TARGETS.get(5),"syncIsoObjectReceive","denyRemote"},{TARGETS.get(6),"factoryFromFileInput","powerBank"},
            {TARGETS.get(2),"RemoveItemFromMap","denyRemoval"},{TARGETS.get(2),"receivePlayerConnect","playerAccepted"},
            {TARGETS.get(0),"exposeAll","exposed"}};
        for(int i=0;i<hooks.length;i++) checks.add(new HookCheck("lua-"+i,hooks[i][0],hooks[i][1],h,hooks[i][2],false));
        checks.add(new HookCheck("lua-generator-prewarm-relevance",TARGETS.get(2),"main",
            "cloud.apocalipse.pz.agent.features.lua.LuaRuntime","renewGeneratorPrewarms",false));
        checks.add(new HookCheck("lua-mod-entities",TARGETS.get(1),"init",
            "cloud.apocalipse.pz.agent.features.lua.entities.ApocBRModEntities","install",false));
        return List.copyOf(checks);
    }
    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (!enabled) return builder;
        return builder.type(namedOneOf(TARGETS.toArray(String[]::new)))
            .transform((dynamic,type,loader,module,domain)->transform(type.getName(),dynamic));
    }
    public static DynamicType.Builder<?> transform(String type,DynamicType.Builder<?> dynamic) {
        return switch(type) {
            case "zombie.Lua.LuaManager$Exposer" -> dynamic.visit(new LuaExposureVisitor())
                .visit(Advice.to(LuaAdvice.Expose.class).on(named("exposeAll").and(takesArguments(0))));
            case "zombie.Lua.LuaManager" -> dynamic.visit(Advice.to(LuaAdvice.Init.class).on(named("init").and(takesArguments(0))));
            case "zombie.network.GameServer" -> dynamic.visit(new LuaBoundaryVisitor(type))
                .visit(Advice.to(LuaAdvice.Startup.class).on(named("startServer").and(takesArguments(0))))
                .visit(Advice.to(LuaAdvice.PlayerAccepted.class).on(named("receivePlayerConnect").and(takesArguments(3))))
                .visit(Advice.to(LuaAdvice.RemoveFromMap.class).on(named("RemoveItemFromMap").and(takesArguments(1))));
            case "zombie.network.ServerMap" -> dynamic.visit(Advice.to(LuaAdvice.Shutdown.class).on(named("QueuedQuit").and(takesArguments(0))));
            case "zombie.popman.LoadedAreas" -> dynamic.visit(new LuaBoundaryVisitor(type));
            case "zombie.iso.IsoObject" -> dynamic.visit(Advice.to(LuaAdvice.Factory.class).on(named("factoryFromFileInput")
                .and(takesArguments(2)).and(takesArgument(1,java.nio.ByteBuffer.class))));
            case "zombie.iso.objects.IsoGenerator" -> dynamic.visit(new LuaBoundaryVisitor(type))
                .visit(Advice.to(LuaAdvice.Update.class).on(named("update").and(takesArguments(0))))
                .visit(Advice.to(LuaAdvice.Added.class).on(named("addToWorld").and(takesArguments(0))))
                .visit(Advice.to(LuaAdvice.Removed.class).on(named("removeFromWorld").and(takesArguments(0))))
                .visit(Advice.to(LuaAdvice.Loaded.class).on(named("load").and(takesArguments(java.nio.ByteBuffer.class,int.class,boolean.class))))
                .visit(Advice.to(LuaAdvice.Saving.class).on(named("save").and(takesArguments(java.nio.ByteBuffer.class,boolean.class))))
                .visit(Advice.to(LuaAdvice.Change.class).on(named("setFuel").and(takesArguments(float.class))
                    .or(named("setCondition").and(takesArguments(int.class)))
                    .or(namedOneOf("setConnected","setActivated").and(takesArguments(boolean.class)))))
                .visit(Advice.to(LuaAdvice.Remove.class).on(named("remove").and(takesArguments(0))))
                .visit(Advice.to(LuaAdvice.Remote.class).on(named("syncIsoObjectReceive").and(takesArguments(2))));
            default -> throw new IllegalArgumentException(type);
        };
    }
}
