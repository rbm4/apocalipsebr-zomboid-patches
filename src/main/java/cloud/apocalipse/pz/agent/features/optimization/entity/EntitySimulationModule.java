package cloud.apocalipse.pz.agent.features.optimization.entity;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.entity.EngineSystem;
import zombie.entity.components.crafting.MashingLogicSystem;
import zombie.network.GameServer;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class EntitySimulationModule implements AgentModule {
    private static final String MANAGER = "zombie.entity.GameEntityManager";
    private static final String CLOCK = "zombie.entity.EntitySimulation";
    private static final String CRAFT = "zombie.entity.components.crafting.";
    private static final String RESOURCE = "zombie.entity.components.resources.";
    private static final String FLUID = "zombie.entity.components.fluids.FluidContainerUpdateSystem";
    private static final String CRAFT_LOGIC = CRAFT + "CraftLogicSystem";
    private static final String DRYING = CRAFT + "DryingCraftLogic";
    private static final String FURNACE = CRAFT + "FurnaceLogicSystem";
    private static final String MASH = CRAFT + "MashingLogicSystem";
    private static final String RESOURCE_UPDATE = RESOURCE + "ResourceUpdateSystem";
    private static final String LOGISTICS = RESOURCE + "LogisticsSystem";
    private static final String SYSTEM = "zombie.entity.EngineSystem";
    private static final String BUCKET = "zombie.entity.EntityBucket";
    private static final String META = "zombie.entity.MetaEntity";
    private static final String ENTITY = "zombie.entity.GameEntity";
    private static final String USING_PLAYER = "zombie.entity.UsingPlayerUpdateSystem";
    private static final String RECIPE_DATA = "Lzombie/entity/components/crafting/recipe/CraftRecipeData;";
    private static final String RESOURCE_GROUP = "Lzombie/entity/components/resources/ResourceGroup;";
    private static final String ENTITY_TYPE = "Lzombie/entity/GameEntity;";

    @Override
    public String name() {
        return "entity-simulation-catchup";
    }

    @Override
    public String disabledReason() {
        return AgentConfiguration.optimizationEnabled(AgentConfiguration.ENTITY_SIMULATION_OPTIMIZATION)
            ? null : "Disabled by -D" + AgentConfiguration.ENTITY_SIMULATION_OPTIMIZATION;
    }

    @Override
    public List<HookCheck> hookChecks() {
        String clock = EntitySimulationClock.class.getName();
        String index = EntityDueIndex.class.getName();
        return List.of(
            new HookCheck("batch", MANAGER, "Update", clock, "captureTicks", false),
            new HookCheck("reset", CLOCK, "reset", clock, "reset", false),
            new HookCheck("craft-candidates", CRAFT_LOGIC, "updateSimulation", index, "select", false),
            new HookCheck("craft-ticks", CRAFT_LOGIC, "updateCraftLogic", clock, "gameSeconds", false),
            new HookCheck("drying-ticks", DRYING, "onUpdate", clock, "gameSeconds", false),
            new HookCheck("furnace-ticks", FURNACE, "updateFurnaceLogic", clock, "effectiveTicks", false),
            new HookCheck("furnace-slot-ticks", FURNACE, "updateFurnaceSlots", clock, "effectiveTicks", false),
            new HookCheck("resource-ticks", RESOURCE_UPDATE, "updateSimulation", clock, "scale", false),
            new HookCheck("fluid-ticks", FLUID, "updateEntity", clock, "scale", false),
            new HookCheck("mashing-cadence", SYSTEM, "updateSimulation", getClass().getName(), "dispatchMashing", false),
            new HookCheck("mashing-membership", MASH, "<init>", getClass().getName(), "configureMashing", false),
            new HookCheck("mashing-candidates", MASH, "update", index, "select", false),
            new HookCheck("logistics", LOGISTICS, "updateSimulation", clock, "active", false),
            new HookCheck("bucket-append", BUCKET, "updateMembership", index, "append", false),
            new HookCheck("bucket-remove", BUCKET, "updateMembership", index, "remove", false),
            new HookCheck("using-player-update", USING_PLAYER, "update", UsingPlayerIndex.class.getName(), "update", false),
            new HookCheck("using-player-write", ENTITY, "setUsingPlayer", UsingPlayerIndex.class.getName(), "changed", false),
            new HookCheck("using-player-receive", ENTITY, "receiveUpdateUsingPlayer", UsingPlayerIndex.class.getName(), "changed", false),
            new HookCheck("using-player-sync", ENTITY, "receiveSyncEntity", UsingPlayerIndex.class.getName(), "changed", false),
            new HookCheck("using-player-reset", ENTITY, "reset", UsingPlayerIndex.class.getName(), "changed", false),
            new HookCheck("meta-identity", META, "loadMetaEntity", index, "refresh", false),
            new HookCheck("meta-reset", META, "reset", index, "refresh", false));
    }

    @Override
    public void initialize() {
        if (disabledReason() == null) {
            EntitySimulationClock.configure();
        }
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        return builder.type(named(MANAGER)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(site("Update", "()V", Kind.FRAME, 1))
                .visit(Advice.to(FrameScope.class).on(named("Update").and(takesArguments(0)))))
            .type(named(CLOCK)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(Reset.class).on(named("reset").and(takesArguments(0)))))
            .type(named(CRAFT_LOGIC)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(site("updateSimulation", "()V", Kind.SELECTION, 2))
                .visit(site("updateCraftLogic", "(Lzombie/entity/components/crafting/CraftLogic;"
                    + RESOURCE_GROUP + RESOURCE_GROUP + ")V", Kind.SECONDS, 1)))
            .type(named(DRYING)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(site("onUpdate", "(" + RECIPE_DATA + ")V", Kind.SECONDS, 3)))
            .type(named(FURNACE)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(site("updateSimulation", "()V", Kind.SELECTION, 2))
                .visit(site("updateFurnaceLogic", "(Lzombie/entity/components/crafting/FurnaceLogic;"
                    + RESOURCE_GROUP + RESOURCE_GROUP + ")V", Kind.FURNACE_TICKS, 1))
                .visit(site("updateFurnaceSlots", "(Lzombie/entity/components/crafting/FurnaceLogic;Z)V",
                    Kind.FURNACE_TICKS, 2)))
            .type(named(RESOURCE_UPDATE)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(site("updateSimulation", "()V", Kind.RESOURCE, 3)))
            .type(named(FLUID)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(site("updateSimulation", "()V", Kind.SELECTION, 2))
                .visit(site("updateEntity", "(" + ENTITY_TYPE + "Lzombie/entity/components/fluids/FluidContainer;Z)V",
                    Kind.FLUID, 2)))
            .type(named(MASH)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(site("update", "()V", Kind.SELECTION, 2))
                .visit(Advice.to(MashingMembership.class).on(isConstructor().and(takesArguments(int.class)))))
            .type(named(SYSTEM)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(MashingDispatch.class).on(named("updateSimulation").and(takesArguments(0)))))
            .type(named(LOGISTICS)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(Logistics.class).on(named("updateSimulation").and(takesArguments(0)))))
            .type(named(BUCKET)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(site("updateMembership", "(" + ENTITY_TYPE + ")V", Kind.BUCKET, 2))
                .visit(Advice.to(RefreshMember.class).on(named("updateMembership").and(takesArguments(1)))))
            .type(named(META)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(RefreshMeta.class).on(named("loadMetaEntity").or(named("reset")))))
            .type(named(USING_PLAYER)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(UpdateUsingPlayer.class).on(named("update").and(takesArguments(0)))))
            .type(named(ENTITY)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(site("reset", "()V", Kind.USING_PLAYER_WRITE, 1))
                .visit(site("setUsingPlayer", "(Lzombie/characters/IsoPlayer;)V", Kind.USING_PLAYER_WRITE, 1))
                .visit(site("receiveUpdateUsingPlayer", "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V",
                    Kind.USING_PLAYER_WRITE, 3))
                .visit(site("receiveSyncEntity", "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V",
                    Kind.USING_PLAYER_WRITE, 2)));
    }

    private static AsmVisitorWrapper site(String name, String descriptor, Kind kind, int expected) {
        return OptimizationVisitor.method(name, descriptor, downstream -> new SiteVisitor(downstream, kind, expected))
            .writerFlags(ClassWriter.COMPUTE_MAXS);
    }

    public static void configureMashing(EngineSystem system) {
        if (GameServer.server) {
            system.setUpdater(false);
            system.setSimulationUpdater(true);
        }
    }

    public static void dispatchMashing(EngineSystem system) {
        if (EntitySimulationClock.active() && system.getClass() == MashingLogicSystem.class) {
            system.update();
        }
    }

    public static class FrameScope {
        @Advice.OnMethodEnter
        public static EntitySimulationClock.Frame enter() { return EntitySimulationClock.enter(); }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter EntitySimulationClock.Frame previous) { EntitySimulationClock.leave(previous); }
    }

    public static class Reset {
        @Advice.OnMethodExit
        public static void exit() { EntitySimulationClock.reset(); }
    }

    public static class MashingMembership {
        @Advice.OnMethodExit
        public static void exit(@Advice.This EngineSystem system) { configureMashing(system); }
    }

    public static class MashingDispatch {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object system) { dispatchMashing((EngineSystem) system); }
    }

    public static class Logistics {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter() { return EntitySimulationClock.active(); }
    }

    public static class RefreshMember {
        @Advice.OnMethodExit
        public static void exit(@Advice.This Object bucket, @Advice.Argument(0) zombie.entity.GameEntity entity) {
            EntityDueIndex.refresh((zombie.entity.EntityBucket) bucket, entity);
        }
    }

    public static class RefreshMeta {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This zombie.entity.GameEntity entity) { EntityDueIndex.refresh(entity); }
    }

    public static class UpdateUsingPlayer {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.FieldValue("isoEntities") Object bucket) {
            return UsingPlayerIndex.update((zombie.entity.EntityBucket) bucket);
        }
    }

    private enum Kind { FRAME, SELECTION, SECONDS, FURNACE_TICKS, RESOURCE, FLUID, BUCKET, USING_PLAYER_WRITE }

    private static final class SiteVisitor extends MethodVisitor {
        private final Kind kind;
        private final int expected;
        private int anchors;
        private boolean afterElapsed;
        private boolean scaleProduct;

        SiteVisitor(MethodVisitor downstream, Kind kind, int expected) {
            super(ASM9, downstream);
            this.kind = kind;
            this.expected = expected;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String called, String descriptor, boolean itf) {
            if (opcode == INVOKESTATIC && owner.equals("zombie/entity/EntitySimulation")) {
                if (kind == Kind.FRAME && called.equals("getSimulationTicksThisFrame") && descriptor.equals("()I")) {
                    hook(EntitySimulationClock.class, "captureTicks", descriptor);
                    return;
                }
                if ((kind == Kind.SECONDS || kind == Kind.FLUID) && called.equals("getGameSecondsPerTick")
                        && descriptor.equals("()D")) {
                    hook(EntitySimulationClock.class, "gameSeconds", descriptor);
                    return;
                }
            }
            if ((kind == Kind.SELECTION || kind == Kind.RESOURCE) && opcode == INVOKEVIRTUAL) {
                if (owner.equals("zombie/entity/EntityBucket") && called.equals("getEntities")
                        && descriptor.equals("()Lzombie/entity/util/ImmutableArray;")) {
                    hook(EntityDueIndex.class, "select", "(Lzombie/entity/EntityBucket;)Lzombie/entity/util/ImmutableArray;");
                    return;
                }
                if (called.equals("isValidEntity") && descriptor.equals("(" + ENTITY_TYPE + ")Z")) {
                    mv.visitInsn(DUP_X1);
                    super.visitMethodInsn(opcode, owner, called, descriptor, itf);
                    hook(EntitySimulationClock.class, "isDue", "(" + ENTITY_TYPE + "Z)Z");
                    return;
                }
            }
            if (kind == Kind.BUCKET && opcode == INVOKEVIRTUAL && owner.equals("zombie/entity/util/Array")) {
                if (called.equals("add") && descriptor.equals("(Ljava/lang/Object;)V")) {
                    mv.visitVarInsn(ALOAD, 0);
                    hook(EntityDueIndex.class, "append", "(Lzombie/entity/util/Array;" + ENTITY_TYPE + "Lzombie/entity/EntityBucket;)V");
                    return;
                }
                if (called.equals("removeValue") && descriptor.equals("(Ljava/lang/Object;Z)Z")) {
                    mv.visitVarInsn(ALOAD, 0);
                    hook(EntityDueIndex.class, "remove", "(Lzombie/entity/util/Array;" + ENTITY_TYPE + "ZLzombie/entity/EntityBucket;)Z");
                    return;
                }
            }
            afterElapsed = kind == Kind.FURNACE_TICKS && called.equals("getElapsedTime") && descriptor.equals("()I");
            super.visitMethodInsn(opcode, owner, called, descriptor, itf);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if ((kind == Kind.RESOURCE && Float.valueOf(0.05F).equals(value))
                    || (kind == Kind.FLUID && Float.valueOf(1.0E-4F).equals(value))) {
                scaleProduct = true;
            }
            super.visitLdcInsn(value);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            if (kind == Kind.USING_PLAYER_WRITE && opcode == PUTFIELD && owner.equals("zombie/entity/GameEntity")
                    && name.equals("usingPlayer") && descriptor.equals("Lzombie/characters/IsoPlayer;")) {
                mv.visitVarInsn(ALOAD, 0);
                hook(UsingPlayerIndex.class, "changed", "(Ljava/lang/Object;)V");
            }
        }

        @Override
        public void visitInsn(int opcode) {
            if (afterElapsed && opcode == ICONST_1) {
                afterElapsed = false;
                hook(EntitySimulationClock.class, "effectiveTicks", "()I");
                return;
            }
            afterElapsed = false;
            super.visitInsn(opcode);
            if (scaleProduct && opcode == FMUL) {
                scaleProduct = false;
                hook(EntitySimulationClock.class, "scale", "(F)F");
            }
        }

        private void hook(Class<?> helper, String method, String descriptor) {
            anchors++;
            OptimizationVisitor.callStatic(mv, helper, method, descriptor);
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("entity simulation " + kind, anchors, expected);
            super.visitEnd();
        }
    }
}
