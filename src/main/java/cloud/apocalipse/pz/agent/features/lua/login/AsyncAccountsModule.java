package cloud.apocalipse.pz.agent.features.lua.login;

import cloud.apocalipse.pz.agent.*;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.*;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import static net.bytebuddy.matcher.ElementMatchers.*;

public final class AsyncAccountsModule implements AgentModule {
    private boolean enabled;
    private boolean initialized;
    private static final String PACKET_TYPE = "zombie.network.PacketTypes$PacketType";
    public static final List<String> PACKETS=List.of("zombie.network.packets.connection.LoginPacket",
        "zombie.network.packets.connection.GoogleAuthPacket","zombie.network.packets.connection.GoogleAuthKeyPacket",
        "zombie.network.packets.service.RequestUserLogPacket");
    public static final List<String> TARGETS;
    static {
        var list=new ArrayList<>(PACKETS);list.addAll(List.of("zombie.network.ServerWorldDatabase",
            "zombie.network.ServerWorldDatabase$LogonResult","zombie.network.GameServer","zombie.network.ServerMap",PACKET_TYPE));TARGETS=List.copyOf(list);
    }
    @Override public String name(){return "async-accounts";}
    @Override public void initialize() {
        enabled=AgentConfiguration.asyncAccountsEnabled();
        if(enabled) cloud.apocalipse.pz.agent.features.lua.HookPreflight.validate(TARGETS,hookChecks(),AsyncAccountsModule::transform);
        AsyncRuntime.initialize(enabled);
        if(enabled) cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime.beforeShutdown(AsyncRuntime::shutdown);
        initialized = true;
    }
    @Override public List<HookCheck> hookChecks(){
        var checks=new ArrayList<HookCheck>();
        List<String> packets = initialized && !enabled ? PACKETS.subList(0, 1) : PACKETS;
        for(String packet:packets)checks.add(new HookCheck(packet.substring(packet.lastIndexOf('.')+1),packet,"processServer","cloud.apocalipse.pz.agent.features.lua.login.PacketRuntime","process",false));
        checks.add(new HookCheck("apbr-login-redaction", PACKET_TYPE, "logPacket",
            PacketRuntime.class.getName(), "omitLoginPacketLog", false));
        if (initialized && !enabled) return List.copyOf(checks);
        checks.add(new HookCheck("admin-response",PACKETS.get(3),"setData","cloud.apocalipse.pz.agent.features.lua.login.PacketRuntime","userlog",false));
        checks.add(new HookCheck("worker-role-view","zombie.network.ServerWorldDatabase","authClient","cloud.apocalipse.pz.agent.features.lua.login.WorkerContext","getRoleById",false));
        checks.add(new HookCheck("worker-result-role","zombie.network.ServerWorldDatabase$LogonResult","<init>","cloud.apocalipse.pz.agent.features.lua.login.WorkerContext","getDefaultForNewUser",true));
        checks.add(new HookCheck("worker-drain","zombie.network.GameServer","main","cloud.apocalipse.pz.agent.features.lua.login.AsyncRuntime","update",false));
        checks.add(new HookCheck("worker-main-exit","zombie.network.GameServer","main","cloud.apocalipse.pz.agent.features.lua.login.AsyncRuntime","shutdown",false));
        checks.add(new HookCheck("worker-quit","zombie.network.ServerMap","QueuedQuit","cloud.apocalipse.pz.agent.features.lua.login.AsyncRuntime","shutdown",false));
        return List.copyOf(checks);
    }
    @Override public AgentBuilder configure(AgentBuilder builder){
        if (!enabled) return builder.type(namedOneOf(PACKETS.getFirst(), PACKET_TYPE))
            .transform((dynamic,type,loader,module,domain) -> transform(type.getName(),dynamic));
        return builder.type(namedOneOf(TARGETS.toArray(String[]::new)))
            .transform((dynamic,type,loader,module,domain)->transform(type.getName(),dynamic));
    }
    public static DynamicType.Builder<?> transform(String type,DynamicType.Builder<?> dynamic){
        if (type.equals(PACKET_TYPE)) return dynamic.visit(Advice.to(AsyncAdvice.PacketLog.class)
            .on(named("logPacket").and(takesArguments(1))));
        if(PACKETS.contains(type)) {
            var result=dynamic.visit(Advice.to(AsyncAdvice.Packet.class).on(named("processServer").and(takesArguments(2))));
            return type.equals(PACKETS.get(3))?result.visit(new WorkerBoundaryVisitor()):result;
        }
        if(type.equals("zombie.network.GameServer"))return dynamic.visit(new AsyncLoopVisitor())
            .visit(Advice.to(AsyncAdvice.MainExit.class).on(named("main").and(takesArguments(String[].class))));
        if(type.equals("zombie.network.ServerMap"))return dynamic.visit(Advice.to(AsyncAdvice.Shutdown.class).on(named("QueuedQuit").and(takesArguments(0))));
        return dynamic.visit(new WorkerBoundaryVisitor());
    }
}
