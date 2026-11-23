package cloud.apocalipse.pz.agent.features.optimization.actor.allocation;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import static net.bytebuddy.matcher.ElementMatchers.named;

/** Reduces boxing at the existing stat, animal perception and dirty-ID write sites. */
public final class ActorUpdateAllocationModule extends OptimizationModule {
    private static final String STATS = "zombie.characters.Stats";
    private static final String BEHAVIOR = "zombie.characters.animals.behavior.BaseAnimalBehavior";
    private static final String ANIMALS = "zombie.popman.animal.AnimalInstanceManager";
    private static final String MAP_PUT = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String SHORT_BOX = "(S)Ljava/lang/Short;";

    public ActorUpdateAllocationModule() {
        super("actor-update-allocation", AgentConfiguration.ACTOR_UPDATE_ALLOCATION_OPTIMIZATION);
    }

    @Override
    public List<HookCheck> hookChecks() {
        String helper = ActorUpdateAllocationModule.class.getName();
        return List.of(
            new HookCheck("stat-write", STATS, "set", helper, "putFloat", false),
            new HookCheck("acceptance-write", BEHAVIOR, "spotted", helper, "putFloat", false),
            new HookCheck("animal-dirty-id", ANIMALS, "updateLocal", helper, "boxedAnimalId", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder
            .type(named(STATS)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method("set", "(Lzombie/characters/CharacterStat;F)Z",
                    downstream -> new FloatWriteVisitor(downstream, "java/util/Map"))))
            .type(named(BEHAVIOR)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method("spotted", "(Lzombie/iso/IsoMovingObject;ZF)V",
                    downstream -> new FloatWriteVisitor(downstream, "java/util/HashMap"))))
            .type(named(ANIMALS)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method("updateLocal", "(Lzombie/characters/animals/IsoAnimal;)V",
                    AnimalIdVisitor::new)));
    }

    public static <K> Object putFloat(Map<K, Float> map, K key, float value) {
        // Animal acceptance maps are public. A replacement map can observe every put.
        if (map.getClass() == HashMap.class) {
            Float stored = map.get(key);
            if (stored != null && Float.floatToRawIntBits(stored) == Float.floatToRawIntBits(value)) {
                return stored;
            }
        }
        return map.put(key, value);
    }

    public static Short boxedAnimalId(short id) {
        if (id >= -128 && id <= 127) {
            return Short.valueOf(id);
        }
        int index = Short.toUnsignedInt(id);
        Short boxed = AnimalIds.VALUES.get(index);
        if (boxed != null) {
            return boxed;
        }
        boxed = Short.valueOf(id);
        if (AnimalIds.VALUES.compareAndSet(index, null, boxed)) {
            return boxed;
        }
        return AnimalIds.VALUES.get(index);
    }

    private static final class AnimalIds {
        // One entry per distinct ID, shared across animals, with no world references.
        private static final AtomicReferenceArray<Short> VALUES = new AtomicReferenceArray<>(1 << Short.SIZE);
    }

    private static final class FloatWriteVisitor extends MethodVisitor {
        private final String mapOwner;
        private boolean boxing;
        private int matches;

        FloatWriteVisitor(MethodVisitor downstream, String mapOwner) {
            super(Opcodes.ASM9, downstream);
            this.mapOwner = mapOwner;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (boxing) {
                if (!mapOwner.equals(owner) || !"put".equals(name) || !MAP_PUT.equals(descriptor)) {
                    throw new IllegalStateException("Expected vanilla map write after float boxing");
                }
                OptimizationVisitor.callStatic(mv, ActorUpdateAllocationModule.class, "putFloat",
                    "(Ljava/util/Map;Ljava/lang/Object;F)Ljava/lang/Object;");
                boxing = false;
                matches++;
            } else if (opcode == Opcodes.INVOKESTATIC && "java/lang/Float".equals(owner)
                    && "valueOf".equals(name) && "(F)Ljava/lang/Float;".equals(descriptor)) {
                boxing = true;
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches(mapOwner + " float write", matches, 1);
            super.visitEnd();
        }
    }

    private static final class AnimalIdVisitor extends MethodVisitor {
        private int matches;

        AnimalIdVisitor(MethodVisitor downstream) {
            super(Opcodes.ASM9, downstream);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC && "java/lang/Short".equals(owner)
                    && "valueOf".equals(name) && SHORT_BOX.equals(descriptor)) {
                OptimizationVisitor.callStatic(mv, ActorUpdateAllocationModule.class, "boxedAnimalId", SHORT_BOX);
                matches++;
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("animal dirty ID", matches, 1);
            super.visitEnd();
        }
    }
}
