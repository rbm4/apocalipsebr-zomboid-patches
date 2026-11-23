package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryDecodeTest {
    @TempDir Path directory;
    @Test void everyDeclaredHookExistsInTransformedOriginalArchive() throws Exception {
        try (var locator = ClassFileLocator.ForJarFile.of(new File(System.getProperty("pz.game.jar", "Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar")))) {
            var pool = TypePool.Default.of(new ClassFileLocator.Compound(locator, ClassFileLocator.ForClassLoader.ofSystemLoader()));
            var calls = new HashSet<String>();
            for (String target : DictionaryDecodeModule.TARGETS) {
                var type = pool.describe(target).resolve();
                byte[] bytes = DictionaryDecodeModule.transform(target, new ByteBuddy().redefine(type, locator)).make().getBytes();
                var after = TypePool.Default.of(new ClassFileLocator.Compound(ClassFileLocator.Simple.of(target, bytes), locator)).describe(target).resolve();
                assertEquals(type.getDeclaredMethods().size(), after.getDeclaredMethods().size());
                assertEquals(type.getDeclaredFields().size(), after.getDeclaredFields().size());
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                                calls.add(target + "|" + method + "|" + owner.replace('/', '.') + "|" + name);
                            }
                        };
                    }
                }, 0);
            }
            for (var check : new DictionaryDecodeModule().hookChecks()) assertTrue(calls.contains(check.targetClass() + "|" + check.method() + "|" + check.helperClass() + "|" + check.helperMethod()), check.toString());
        }
    }
    @Test void installedModuleExecutesOriginalNativeDecoderAndRestoresScopes() throws Exception {
        String name = DecodeOriginalSmokeMain.class.getName();
        String resource = name.replace('.', '/') + ".class";
        Path isolated = directory.resolve("classes"), destination = isolated.resolve(resource);
        Files.createDirectories(destination.getParent()); Files.copy(Path.of("target/test-classes").resolve(resource), destination);
        var manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", name);
        Path agent = directory.resolve("probe.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(agent), manifest)) {
            out.putNextEntry(new JarEntry(resource)); Files.copy(destination, out); out.closeEntry();
        }
        var entries = new ArrayList<String>(); entries.add(isolated.toString());
        for (String entry : System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(java.util.regex.Pattern.quote(File.pathSeparator)))
            if (!Path.of(entry).toAbsolutePath().normalize().equals(Path.of("target/test-classes").toAbsolutePath().normalize())) entries.add(entry);
        Path output = directory.resolve("decode.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xverify:all", "-javaagent:" + agent,
            "-Dapocbr.postgres.enabled=true", "-Dapocbr.postgres.serverId=1", "-cp", String.join(File.pathSeparator, entries), name)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "decoder smoke timed out");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
