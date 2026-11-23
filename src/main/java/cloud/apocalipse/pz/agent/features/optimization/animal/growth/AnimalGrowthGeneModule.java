package cloud.apocalipse.pz.agent.features.optimization.animal.growth;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import static net.bytebuddy.matcher.ElementMatchers.named;

/** Preserves the public gene lookup while supplying the already-normalized growth key. */
public final class AnimalGrowthGeneModule extends OptimizationModule {
    private static final String TARGET = "zombie.characters.animals.datas.AnimalGrowStage";

    public AnimalGrowthGeneModule() {
        super("animal-growth-gene-key", AgentConfiguration.ANIMAL_GROWTH_GENE_OPTIMIZATION);
    }

    @Override public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("normalized-key", TARGET, "getAgeToGrow",
            AnimalGrowthGeneModule.class.getName(), "growthGeneKey", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method("getAgeToGrow", "(Lzombie/characters/animals/IsoAnimal;)I",
                GeneKeyVisitor::new)));
    }

    public static String growthGeneKey() {
        return "agetogrow";
    }

    private static final class GeneKeyVisitor extends MethodVisitor {
        private int matches;

        GeneKeyVisitor(MethodVisitor downstream) { super(Opcodes.ASM9, downstream); }

        @Override public void visitLdcInsn(Object value) {
            if ("ageToGrow".equals(value)) {
                OptimizationVisitor.callStatic(mv, AnimalGrowthGeneModule.class, "growthGeneKey", "()Ljava/lang/String;");
                matches++;
            } else {
                super.visitLdcInsn(value);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("animal growth key", matches, 1);
            super.visitEnd();
        }
    }
}

