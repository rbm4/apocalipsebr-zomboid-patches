package cloud.apocalipse.pz.agent.features.optimization;

import java.util.function.UnaryOperator;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

public final class OptimizationVisitor {
    private OptimizationVisitor() {
    }

    public static AsmVisitorWrapper.ForDeclaredMethods method(
            String name, String descriptor, UnaryOperator<MethodVisitor> visitor) {
        return new AsmVisitorWrapper.ForDeclaredMethods().method(named(name).and(hasDescriptor(descriptor)),
            (type, method, downstream, context, pool, writer, reader) -> visitor.apply(downstream));
    }

    public static void callStatic(MethodVisitor downstream, Class<?> owner, String name, String descriptor) {
        downstream.visitMethodInsn(Opcodes.INVOKESTATIC, owner.getName().replace('.', '/'), name, descriptor, false);
    }

    public static void requireMatches(String site, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalStateException(site + " expected " + expected + " vanilla bytecode anchors, found " + actual);
        }
    }
}
