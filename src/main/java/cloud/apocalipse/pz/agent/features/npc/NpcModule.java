package cloud.apocalipse.pz.agent.features.npc;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.config.AgentConfiguration.NpcSettings;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Original-JAR NPC lifecycle, identity, world, packet, authority and damage hook registrations. */
public final class NpcModule implements AgentModule {
    private NpcSettings settings;
    static final List<String> ACTOR_TARGETS=List.of("zombie.iso.IsoCell","zombie.iso.IsoObject",
        "zombie.pathfind.PMMover","zombie.network.packets.character.PlayerDataRequestPacket",
        "zombie.network.packets.PlayerRequestPacket","zombie.network.packets.RequestTradingPacket",
        "zombie.network.packets.AcceptTradingPacket","zombie.network.packets.RejectTradingPacket",
        "zombie.network.packets.TradingUIAddItemPacket","zombie.network.packets.TradingUIRemoveItemPacket",
        "zombie.network.packets.TradingUIUpdateStatePacket","zombie.popman.NetworkZombieManager",
        "zombie.popman.NetworkZombiePacker","zombie.network.packets.character.ZombiePacket","zombie.characters.IsoGameCharacter",
        "zombie.network.packets.hit.PlayerHitPlayerPacket","zombie.network.packets.hit.PlayerHitPacket",
        "zombie.network.packets.hit.HitCharacter","zombie.network.fields.hit.Hit","zombie.characters.BodyDamage.BodyPart","zombie.iso.IsoWorld","zombie.network.ServerMap$ServerCell");
    @Override public String name() { return "npc-engine"; }
    @Override public String disabledReason() {
        if (settings == null) throw new IllegalStateException("NPC module not initialized");
        return settings.enabled() ? null : "NPC engine disabled with -Dapocbr.npc.enabled=false";
    }
    @Override public List<HookCheck> hookChecks() {
        String runtime = NpcRuntime.class.getName();
        var checks=new java.util.ArrayList<>(List.of(new HookCheck("npc-tick", "zombie.network.GameServer", "main", runtime, "update", false),
            new HookCheck("npc-main-exit", "zombie.network.GameServer", "main", runtime, "shutdown", false),
            new HookCheck("npc-quit", "zombie.network.ServerMap", "QueuedQuit", runtime, "shutdown", false),
            new HookCheck("npc-console-quit", "zombie.network.ServerMap", "preupdate", runtime, "shutdown", false)));
        if(AgentConfiguration.npcActorsEnabled()){
            String hooks=NpcGameplayHooks.class.getName();
            checks.add(new HookCheck("npc-slots","zombie.network.GameServer","getFreeSlot",hooks,"freeSlot",false));
            checks.add(new HookCheck("npc-membership","zombie.iso.IsoCell","<init>",NpcMembershipSet.class.getName(),"wrap",false));
            checks.add(new HookCheck("npc-world-reset","zombie.iso.IsoWorld","KillCell",runtime,"worldReset",false));
            checks.add(new HookCheck("npc-cell-dispose","zombie.iso.IsoCell","Dispose",runtime,"worldReset",false));
            String furniture = NpcFurnitureHooks.class.getName();
            for (String method : List.of("setSquare", "reset", "addToWorld", "removeFromSquare"))
                checks.add(new HookCheck("npc-furniture-" + method, "zombie.iso.IsoObject", method, furniture, "invalidate", false));
            checks.add(new HookCheck("npc-furniture-removeFromWorld", "zombie.iso.IsoObject", "removeFromWorld", furniture, "removedFromWorld", false));
            checks.add(new HookCheck("npc-furniture-cell-unload", "zombie.network.ServerMap$ServerCell", "Unload", furniture, "beginCellUnload", false));
            checks.add(new HookCheck("npc-furniture-cell-unload-exit", "zombie.network.ServerMap$ServerCell", "Unload", furniture, "endCellUnload", false));
            checks.add(new HookCheck("npc-walk-only","zombie.pathfind.PMMover","set",hooks,"walkOnly",false));
            checks.add(new HookCheck("npc-data","zombie.network.packets.character.PlayerDataRequestPacket","processServer",hooks,"dataRequest",false));
            checks.add(new HookCheck("npc-trade-parse","zombie.network.packets.PlayerRequestPacket","parse",hooks,"resolveTrade",false));
            for(String target:ACTOR_TARGETS.subList(5,11))
                checks.add(new HookCheck("npc-trade-"+target.substring(target.lastIndexOf('.')+1),target,"processServer",hooks,"trade",false));
            String zombies=NpcZombieHooks.class.getName(),combat=NpcCombatHooks.class.getName();
            String[][] contracts={
                {"zombie.popman.NetworkZombieManager","updateAuth",zombies,"auth"},
                {"zombie.popman.NetworkZombieManager","moveZombie",zombies,"move"},
                {"zombie.popman.NetworkZombiePacker","updateAuth",zombies,"beginFrame"},
                {"zombie.popman.NetworkZombiePacker","updateAuth",zombies,"endFrame"},
                {"zombie.popman.NetworkZombiePacker","applyZombie",zombies,"received"},
                {"zombie.popman.NetworkZombiePacker","getZombieData",zombies,"append"},
                {"zombie.network.packets.character.ZombiePacket","set",zombies,"prepare"},
                {"zombie.characters.IsoGameCharacter","hitConsequences",zombies,"hit"},
                {"zombie.network.packets.hit.PlayerHitPlayerPacket","parse",combat,"parsing"},
                {"zombie.network.packets.hit.PlayerHitPlayerPacket","parse",combat,"targetStart"},
                {"zombie.network.packets.hit.PlayerHitPlayerPacket","parse",combat,"parsed"},
                {"zombie.network.packets.hit.PlayerHitPlayerPacket","set",combat,"resetPacket"},
                {"zombie.network.packets.hit.PlayerHitPlayerPacket","processTarget",combat,"sanitize"},
                {"zombie.network.packets.hit.PlayerHitPlayerPacket","process",combat,"processFall"},
                {"zombie.network.packets.hit.PlayerHitPacket","processServer",combat,"filter"},
                {"zombie.network.packets.hit.PlayerHitPacket","processServer",combat,"filterRelay"},
                {"zombie.network.packets.hit.HitCharacter","processServer",combat,"beforeProcess"},
                {"zombie.network.packets.hit.HitCharacter","processServer",combat,"afterProcess"},
                {"zombie.network.fields.hit.Hit","roll",combat,"rolled"},
                {"zombie.network.fields.hit.Hit","process",combat,"force"},
                {"zombie.network.fields.hit.Hit","process",combat,"animalOrNpc"},
                {"zombie.characters.BodyDamage.BodyPart","ReduceHealth",combat,"damage"}};
            for(int i=0;i<contracts.length;i++){
                var c=contracts[i];checks.add(new HookCheck("npc-gameplay-"+i,c[0],c[1],c[2],c[3],false));
            }
        }
        return List.copyOf(checks);
    }
    @Override public void initialize() {
        settings = AgentConfiguration.npcSettings();
        if (settings.enabled()) {
            try (var locator = ClassFileLocator.ForClassLoader.ofSystemLoader()) {
                var pool = TypePool.Default.of(locator);
                for (String name : List.of("zombie.network.GameServer", "zombie.network.ServerMap")) {
                    var type = pool.describe(name).resolve();
                    if (name.endsWith("ServerMap") && type.getDeclaredMethods().filter(named("QueuedQuit")
                            .and(takesArguments(0)).and(returns(void.class))).size() != 1)
                        throw new IllegalStateException("Missing NPC native quit boundary");
                    var transformed=transform(name, new ByteBuddy().redefine(type, locator));
                    if(AgentConfiguration.npcActorsEnabled())transformed=actorTransform(name,transformed);
                    transformed.make();
                }
                for (String name : List.of("server", "udpEngine"))
                    if (pool.describe("zombie.network.GameServer").resolve().getDeclaredFields().filter(named(name).and(isPublic())).size() != 1)
                        throw new IllegalStateException("Missing public NPC readiness field: " + name);
                for (String name : List.of("instance", "currentCell"))
                    if (pool.describe("zombie.iso.IsoWorld").resolve().getDeclaredFields().filter(named(name).and(isPublic())).size() != 1)
                        throw new IllegalStateException("Missing public NPC world field: " + name);
                if(AgentConfiguration.npcActorsEnabled())for(String target:ACTOR_TARGETS)
                    actorTransform(target,new ByteBuddy().redefine(pool.describe(target).resolve(),locator)).make();
            } catch (Exception | LinkageError failure) {
                throw new IllegalStateException("NPC original-JAR lifecycle preflight failed", failure);
            }
        }
        NpcRuntime.initialize(settings);
        if(settings.enabled())cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime.beforeShutdown(NpcRuntime::shutdown);
        System.out.println("[ApocBR-Agent][NPC] " + (settings.enabled()
            ? "NPC lifecycle enabled; authority=server; planning=" + (settings.asynchronous() ? "async" : "sync") + "; actors="+AgentConfiguration.npcActorsEnabled()
            : "Disabled by configuration."));
    }
    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        builder=builder.type(namedOneOf("zombie.network.GameServer", "zombie.network.ServerMap"))
            .transform((dynamic, type, loader, module, domain) -> transform(type.getName(), dynamic));
        if(AgentConfiguration.npcActorsEnabled())builder=builder
            .type(namedOneOf(java.util.stream.Stream.concat(java.util.stream.Stream.of("zombie.network.GameServer"),ACTOR_TARGETS.stream()).toArray(String[]::new)))
            .transform((dynamic,type,loader,module,domain)->actorTransform(type.getName(),dynamic));
        return builder;
    }
    static DynamicType.Builder<?> transform(String name, DynamicType.Builder<?> builder) {
        if (name.equals("zombie.network.GameServer")) return builder.visit(new NpcServerLoopVisitor(true))
            .visit(Advice.to(NpcAdvice.MainExit.class).on(named("main").and(isStatic()).and(takesArguments(String[].class)).and(returns(void.class))));
        return builder.visit(new NpcServerLoopVisitor(false))
            .visit(Advice.to(NpcAdvice.Quit.class).on(named("QueuedQuit").and(takesArguments(0)).and(returns(void.class))));
    }

    static DynamicType.Builder<?> actorTransform(String name,DynamicType.Builder<?> builder){
        return switch(name){
            case "zombie.network.GameServer"->builder.visit(Advice.to(NpcGameplayAdvice.Slot.class).on(named("getFreeSlot").and(isStatic()).and(takesArguments(0)).and(returns(short.class))));
            case "zombie.iso.IsoCell"->builder.visit(new NpcCellVisitor())
                .visit(Advice.to(NpcGameplayAdvice.WorldReset.class).on(named("Dispose").and(takesArguments(0))));
            case "zombie.iso.IsoWorld"->builder.visit(Advice.to(NpcGameplayAdvice.WorldReset.class).on(named("KillCell").and(takesArguments(0))));
            case "zombie.iso.IsoObject"->builder
                .visit(Advice.to(NpcGameplayAdvice.SetSquare.class).on(named("setSquare").and(takesArguments(1))))
                .visit(Advice.to(NpcGameplayAdvice.RemoveSquare.class).on(named("removeFromSquare").and(takesArguments(0))))
                .visit(Advice.to(NpcGameplayAdvice.Lifetime.class).on(namedOneOf("reset","addToWorld").and(takesArguments(0))))
                .visit(Advice.to(NpcGameplayAdvice.RemoveFromWorld.class).on(named("removeFromWorld").and(hasDescriptor("()V"))));
            case "zombie.network.ServerMap$ServerCell" -> builder
                .visit(Advice.to(NpcGameplayAdvice.FurnitureCellUnload.class).on(named("Unload").and(hasDescriptor("()V"))));
            case "zombie.pathfind.PMMover"->builder.visit(Advice.to(NpcGameplayAdvice.WalkOnly.class).on(named("set").and(takesArguments(1)).and(takesArgument(0,named("zombie.pathfind.PathFindRequest")))));
            case "zombie.network.packets.character.PlayerDataRequestPacket"->builder.visit(Advice.to(NpcGameplayAdvice.DataRequest.class).on(named("processServer").and(takesArguments(2))));
            case "zombie.network.packets.PlayerRequestPacket"->builder.visit(Advice.to(NpcGameplayAdvice.TradeParse.class).on(named("parse").and(takesArguments(2))));
            case "zombie.popman.NetworkZombieManager"->builder
                .visit(Advice.to(NpcZombieAdvice.Auth.class).on(named("updateAuth").and(takesArguments(1))))
                .visit(Advice.to(NpcZombieAdvice.Move.class).on(named("moveZombie").and(takesArguments(3))));
            case "zombie.popman.NetworkZombiePacker"->builder
                .visit(Advice.to(NpcZombieAdvice.Frame.class).on(named("updateAuth").and(takesArguments(0))))
                .visit(Advice.to(NpcZombieAdvice.Receive.class).on(named("applyZombie").and(takesArguments(1))))
                .visit(Advice.to(NpcZombieAdvice.Send.class).on(named("getZombieData").and(takesArguments(2))));
            case "zombie.network.packets.character.ZombiePacket"->builder.visit(Advice.to(NpcZombieAdvice.Packet.class).on(named("set").and(takesArguments(1))));
            case "zombie.characters.IsoGameCharacter"->builder.visit(Advice.to(NpcZombieAdvice.Hit.class).on(named("hitConsequences").and(takesArguments(5))));
            case "zombie.network.packets.hit.PlayerHitPlayerPacket"->builder.visit(new NpcCombatVisitor())
                .visit(Advice.to(NpcCombatAdvice.Parse.class).on(named("parse").and(takesArguments(2))))
                .visit(Advice.to(NpcCombatAdvice.Reset.class).on(named("set").and(takesArguments(4))))
                .visit(Advice.to(NpcCombatAdvice.Target.class).on(named("processTarget").and(takesArguments(0))));
            case "zombie.network.packets.hit.PlayerHitPacket"->builder.visit(new NpcCombatVisitor())
                .visit(Advice.to(NpcCombatAdvice.Envelope.class).on(named("processServer").and(takesArguments(2))));
            case "zombie.network.packets.hit.HitCharacter"->builder.visit(Advice.to(NpcCombatAdvice.Process.class).on(named("processServer").and(takesArguments(2))));
            case "zombie.network.fields.hit.Hit"->builder.visit(new NpcCombatVisitor())
                .visit(Advice.to(NpcCombatAdvice.Roll.class).on(named("roll").and(takesArguments(3))));
            case "zombie.characters.BodyDamage.BodyPart"->builder.visit(Advice.to(NpcCombatAdvice.Damage.class).on(named("ReduceHealth").and(takesArguments(float.class))));
            case "zombie.network.ServerMap"->builder;
            default->builder.visit(Advice.to(NpcGameplayAdvice.Trade.class).on(named("processServer").and(takesArguments(2))));
        };
    }
}
