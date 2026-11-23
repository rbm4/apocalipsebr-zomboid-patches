package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CellBorderHookTest {
    private static final String CELL = "zombie.network.ServerMap$ServerCell";

    @Test void changesOnlyFourPerimeterLookupsAndPreservesNativeCallbackOrder() throws Exception {
        String property = AgentConfiguration.CELL_BORDER_OPTIMIZATION;
        String previous = System.getProperty(property);
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        File jar = Path.of(System.getProperty("pz.game.jar", windows
            ? "Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar"
            : "/opt/pzserver/java/projectzomboid.jar")).toFile();
        try (var locator = ClassFileLocator.ForJarFile.of(jar)) {
            byte[] original = locator.locate(CELL).resolve();
            System.clearProperty(property);
            assertNull(transform(original));
            var nativeCalls = calls(original);
            assertEquals(12, Collections.frequency(nativeCalls, "getGridSquare"));
            assertEquals(4, Collections.frequency(nativeCalls, "RecalcAllWithNeighbours"));
            System.setProperty(property, "true");
            byte[] transformed = transform(original);
            assertNotNull(transformed);
            var expected = new ArrayList<>(nativeCalls);
            int lookup = 0;
            for (int index = 0; index < expected.size(); index++) {
                if (expected.get(index).equals("getGridSquare") && ++lookup > 8) {
                    expected.set(index, "borderSquare");
                }
            }
            assertEquals(expected, calls(transformed));
            System.setProperty(property, "false");
            assertNull(transform(original));
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    private static byte[] transform(byte[] input) throws Exception {
        return new CellBorderModule().configure(new AgentBuilder.Default().disableClassFormatChanges())
            .makeRaw().transform(CellBorderHookTest.class.getClassLoader(), CELL.replace('.', '/'),
                null, null, input);
    }

    private static List<String> calls(byte[] bytes) {
        var calls = new ArrayList<String>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!name.equals("RecalcAll2") || !descriptor.equals("()V")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                            String descriptor, boolean itf) {
                        calls.add(method);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }
}
