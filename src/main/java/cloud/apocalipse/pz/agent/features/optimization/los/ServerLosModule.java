package cloud.apocalipse.pz.agent.features.optimization.los;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.network.GameServer;
import zombie.network.ServerLOS;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Dedicated-server LOS scheduling, result publication and map lifecycle. */
public final class ServerLosModule implements AgentModule {
    private static final String LOS = "zombie.network.ServerLOS";
    private static final String MAP = "zombie.network.ServerMap";
    private static final String PLAYER = "zombie.characters.IsoPlayer";
    private static final String CELL = "zombie.iso.IsoCell";
    private static final String MOVING = "zombie.iso.IsoMovingObject";
    private static volatile ServerLOS owner;
    private static volatile ServerLosRuntime runtime;

    @Override
    public String name() {
        return "server-los-workers";
    }

    @Override
    public String disabledReason() {
        return AgentConfiguration.optimizationEnabled(AgentConfiguration.SERVER_LOS_OPTIMIZATION)
            ? null : "Disabled by -D" + AgentConfiguration.SERVER_LOS_OPTIMIZATION;
    }

    @Override
    public List<HookCheck> hookChecks() {
        String helper = getClass().getName();
        return List.of(
            new HookCheck("start", LOS, "start", helper, "start", false),
            new HookCheck("add-player", LOS, "addPlayer", helper, "add", false),
            new HookCheck("remove-player", LOS, "removePlayer", helper, "remove", false),
            new HookCheck("schedule", LOS, "doServerZombieLOS", helper, "schedule", false),
            new HookCheck("apply", LOS, "updateLOS", helper, "apply", false),
            new HookCheck("visibility", LOS, "isCouldSee", helper, "visibility", false),
            new HookCheck("map-barrier", LOS, "suspend", helper, "suspend", false),
            new HookCheck("resume", LOS, "resume", helper, "resume", false),
            new HookCheck("zombie-perception", PLAYER, "updateLOS", ServerZombiePerception.class.getName(), "complete", false),
            new HookCheck("tracked-zombie-list", CELL, "<init>", ServerZombiePerception.class.getName(), "track", false),
            new HookCheck("zombie-movement-x", MOVING, "setX", ServerZombiePerception.class.getName(), "moved", false),
            new HookCheck("zombie-movement-y", MOVING, "setY", ServerZombiePerception.class.getName(), "moved", false),
            new HookCheck("shutdown", MAP, "QueuedQuit", helper, "shutdown", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        String player = "(Lzombie/characters/IsoPlayer;)V";
        return builder.type(named(LOS)).transform((dynamic, type, loader, module, domain) -> dynamic
            .visit(advice(Start.class, "start", "()V"))
            .visit(advice(Add.class, "addPlayer", player))
            .visit(advice(Remove.class, "removePlayer", player))
            .visit(advice(Schedule.class, "doServerZombieLOS", player))
            .visit(advice(Apply.class, "updateLOS", player))
            .visit(advice(Visibility.class, "isCouldSee", "(Lzombie/characters/IsoPlayer;Lzombie/iso/IsoGridSquare;)Z"))
            .visit(advice(Suspend.class, "suspend", "()V"))
            .visit(advice(Resume.class, "resume", "()V")))
            .type(named(MAP)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(advice(Shutdown.class, "QueuedQuit", "()V")))
            .type(named(PLAYER)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(advice(Perception.class, "updateLOS", "()V")))
            .type(named(CELL)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(TrackZombieList.class).on(isConstructor().and(hasDescriptor("(II)V")))))
            .type(named(MOVING)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(advice(MoveX.class, "setX", "(F)F"))
                .visit(advice(MoveY.class, "setY", "(F)F")));
    }

    private static net.bytebuddy.asm.AsmVisitorWrapper advice(Class<?> advice, String method, String descriptor) {
        return Advice.to(advice).on(named(method).and(hasDescriptor(descriptor)));
    }

    public static synchronized boolean start(Object instance) {
        if (!GameServer.server) {
            return false;
        }
        if (runtime == null || owner != instance) {
            if (runtime != null) {
                runtime.close();
            }
            runtime = new ServerLosRuntime();
            owner = (ServerLOS) instance;
        }
        return true;
    }

    private static ServerLosRuntime active(Object instance) {
        return instance == owner ? runtime : null;
    }

    static boolean usesAgentLos() {
        return GameServer.server && ServerLOS.instance != null && active(ServerLOS.instance) != null;
    }

    public static boolean add(Object instance, IsoPlayer player) {
        var current = active(instance);
        if (current == null) return false;
        current.add(player);
        return true;
    }

    public static boolean remove(Object instance, IsoPlayer player) {
        var current = active(instance);
        if (current == null) return false;
        current.remove(player);
        return true;
    }

    public static boolean schedule(Object instance, IsoPlayer player) {
        var current = active(instance);
        if (current == null) return false;
        current.schedule(player);
        return true;
    }

    public static boolean apply(Object instance, IsoPlayer player) {
        var current = active(instance);
        if (current == null) return false;
        current.apply(player);
        return true;
    }

    public static Boolean visibility(Object instance, IsoPlayer player, IsoGridSquare square) {
        var current = active(instance);
        return current == null ? null : current.isVisible(player, square);
    }

    public static boolean suspend(Object instance) {
        var current = active(instance);
        if (current == null) return false;
        current.suspend();
        ServerZombiePerception.invalidate();
        return true;
    }

    public static boolean resume(Object instance) {
        var current = active(instance);
        if (current == null) return false;
        current.resume();
        return true;
    }

    public static synchronized void shutdown() {
        if (runtime != null) {
            // Keep routing the closed owner through the discarded state until JVM exit.
            runtime.close();
            ServerZombiePerception.invalidate();
        }
    }

    public static class Start {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object self) { return start(self); }
    }

    public static class Add {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object self, @Advice.Argument(0) IsoPlayer player) { return add(self, player); }
    }

    public static class Remove {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object self, @Advice.Argument(0) IsoPlayer player) { return remove(self, player); }
    }

    public static class Schedule {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object self, @Advice.Argument(0) IsoPlayer player) { return schedule(self, player); }
    }

    public static class Apply {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object self, @Advice.Argument(0) IsoPlayer player) { return apply(self, player); }
    }

    public static class Visibility {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Boolean enter(@Advice.This Object self, @Advice.Argument(0) IsoPlayer player,
                @Advice.Argument(1) IsoGridSquare square) { return visibility(self, player, square); }

        @Advice.OnMethodExit
        public static void exit(@Advice.Enter Boolean visible, @Advice.Return(readOnly = false) boolean result) {
            if (visible != null) result = visible;
        }
    }

    public static class Suspend {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object self) { return suspend(self); }
    }

    public static class Resume {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object self) { return resume(self); }
    }

    public static class Shutdown {
        @Advice.OnMethodEnter
        public static void enter() { shutdown(); }
    }

    public static class Perception {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object player, @Advice.FieldValue("spottedList") Stack<?> spotted,
                @Advice.FieldValue("lastSpotted") Stack<?> lastSpotted) {
            return ServerZombiePerception.complete((IsoPlayer) player, spotted, lastSpotted);
        }
    }

    public static class TrackZombieList {
        @Advice.OnMethodExit
        public static void exit(@Advice.FieldValue(value = "zombieList", readOnly = false) ArrayList<IsoZombie> zombies) {
            if (GameServer.server) {
                zombies = ServerZombiePerception.track(zombies);
            }
        }
    }

    public static class MoveX {
        @Advice.OnMethodEnter
        public static float enter(@Advice.FieldValue("x") float x) { return x; }

        @Advice.OnMethodExit
        public static void exit(@Advice.This Object object, @Advice.Enter float before, @Advice.FieldValue("x") float after) {
            if (before != after) ServerZombiePerception.moved(object, before, after);
        }
    }

    public static class MoveY {
        @Advice.OnMethodEnter
        public static float enter(@Advice.FieldValue("y") float y) { return y; }

        @Advice.OnMethodExit
        public static void exit(@Advice.This Object object, @Advice.Enter float before, @Advice.FieldValue("y") float after) {
            if (before != after) ServerZombiePerception.moved(object, before, after);
        }
    }
}
