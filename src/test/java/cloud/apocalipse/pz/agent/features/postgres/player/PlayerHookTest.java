package cloud.apocalipse.pz.agent.features.postgres.player;

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

class PlayerHookTest {
    @TempDir Path directory;
    @Test void disabledAuthorityLeavesOriginalPlayerEntryPointsUntouched()throws Exception {
        String key=cloud.apocalipse.pz.agent.config.AgentConfiguration.PLAYER_ENABLED_PROPERTY,previous=System.getProperty(key);
        try{
            System.setProperty(key,"false");var module=new PlayerModule();module.initialize();assertNotNull(module.disabledReason());
            var raw=module.configure(new net.bytebuddy.agent.builder.AgentBuilder.Default().disableClassFormatChanges()).makeRaw();
            try(var locator=ClassFileLocator.ForJarFile.of(new File(System.getProperty("pz.game.jar","Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar")))){
                for(String target:PlayerModule.TARGETS)assertNull(raw.transform(getClass().getClassLoader(),target.replace('.','/'),null,null,locator.locate(target).resolve()),target);
            }
        }finally{if(previous==null)System.clearProperty(key);else System.setProperty(key,previous);}
    }
    @Test void registeredContractsTransformExactOriginalArchiveWithoutStructuralChanges() throws Exception {
        var calls = new HashSet<String>();
        try (var locator = new ClassFileLocator.Compound(ClassFileLocator.ForJarFile.of(new File(System.getProperty("pz.game.jar", "Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar"))), ClassFileLocator.ForClassLoader.ofPlatformLoader(), ClassFileLocator.ForClassLoader.ofSystemLoader())) {
            var pool = TypePool.Default.of(new ClassFileLocator.Compound(locator, ClassFileLocator.ForClassLoader.ofSystemLoader()));
            for (String target : PlayerModule.TARGETS) {
                var type = pool.describe(target).resolve();
                byte[] bytes = PlayerModule.transform(target, new ByteBuddy().redefine(type, locator)).make().getBytes();
                var after = TypePool.Default.of(new ClassFileLocator.Compound(ClassFileLocator.Simple.of(target, bytes), locator)).describe(target).resolve();
                assertEquals(type.getDeclaredFields().size(), after.getDeclaredFields().size(), target);
                assertEquals(type.getDeclaredMethods().size(), after.getDeclaredMethods().size(), target);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] errors) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) { calls.add(target + "|" + method + "|" + owner.replace('/', '.') + "|" + name); }
                        };
                    }
                }, 0);
            }
        }
        for (var check : new PlayerModule().hookChecks()) assertTrue(calls.contains(check.targetClass() + "|" + check.method() + "|" + check.helperClass() + "|" + check.helperMethod()), check.toString());
    }
    @Test void installedModuleReconstructsOriginalPlayerWithoutPublishingTemporaryState() throws Exception {
        String name = "cloud.apocalipse.pz.agent.features.postgres.player.PlayerOriginalSmokeMain", resource = name.replace('.', '/') + ".class";
        Path isolated = directory.resolve("classes"), destination = isolated.resolve(resource); Files.createDirectories(destination.getParent());
        Files.copy(Path.of("target/test-classes").resolve(resource), destination);
        try (var files = Files.list(Path.of("target/test-classes").resolve(resource).getParent())) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("PlayerOriginalSmokeMain$")).toList()) Files.copy(file, destination.getParent().resolve(file.getFileName()));
        }
        var manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0"); manifest.getMainAttributes().putValue("Premain-Class", name);
        Path agent = directory.resolve("probe.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(agent), manifest)) { out.putNextEntry(new JarEntry(resource)); Files.copy(destination, out); out.closeEntry(); }
        var paths = new ArrayList<String>(); paths.add(isolated.toString());
        for (String path : System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(java.util.regex.Pattern.quote(File.pathSeparator)))
            if (!Path.of(path).toAbsolutePath().normalize().equals(Path.of("target/test-classes").toAbsolutePath().normalize())) paths.add(path);
        Path output = Path.of("target/player-original-smoke.log");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xverify:all", "-javaagent:" + agent,
            "-Dapocbr.postgres.enabled=true", "-Dapocbr.postgres.url=jdbc:postgresql://localhost/fixture", "-Dapocbr.postgres.players.enabled=true",
            "-Dapocbr.postgres.serverId=1", "-cp", String.join(File.pathSeparator, paths), name, directory.resolve("cache").toString())
            .directory(new File(System.getProperty("pz.game.jar", "Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar")).getParentFile())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try { assertTrue(child.waitFor(45, TimeUnit.SECONDS), "player fixture timed out"); assertEquals(0, child.exitValue(), new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8)); }
        finally { if (child.isAlive()) child.destroyForcibly(); }
    }
}
