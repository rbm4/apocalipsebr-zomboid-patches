package cloud.apocalipse.pz.agent.features.postgres.player;

import java.io.File;
import java.nio.file.*;
import java.time.Instant;
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
    @Test void forcedDispatchDoesNotPoisonTheNextCoalescedBatch() {
        var queue = new PlayerMutations.DirtyQueue();
        var candidate = new PlayerDatabase.Candidate(UUID.randomUUID(), "fixture", "world", "player", false, 0, 0, 0, null, List.of());
        var session = UUID.randomUUID();
        var field = new PlayerMutations.Field("character_state", "x", null);
        long now = -100;
        assertTrue(queue.register(candidate.key(), session, now));
        queue.bind(candidate.key(), session, UUID.randomUUID());

        assertTrue(queue.mark(candidate, session, Map.of(field, 1f), now, Instant.EPOCH));
        var first = queue.poll(now, false);
        assertNotNull(first);
        queue.finish(first, true, now);

        assertTrue(queue.mark(candidate, session, Map.of(field, 2f), now + 1, Instant.EPOCH));
        assertTrue(queue.mark(candidate, session, Map.of(field, 3f), now + 2, Instant.EPOCH));
        assertNull(queue.poll(now + 2, false));
        var forced = queue.poll(now + 2, true);
        assertNotNull(forced);
        assertEquals(3f, forced.fields().get(field).value());
        queue.finish(forced, true, now + 2);

        assertTrue(queue.mark(candidate, session, Map.of(field, 4f), now + 3, Instant.EPOCH));
        var nextWindow = queue.poll(now + 2 + PlayerMutations.CADENCE, false);
        assertNotNull(nextWindow);
        assertEquals(4f, nextWindow.fields().get(field).value());
    }
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
        Path gameJar = Path.of(System.getProperty("pz.game.jar", "Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar")).toAbsolutePath().normalize();
        Path gameInstall;
        String configuredInstall = System.getProperty("pz.game.install");
        if (configuredInstall == null || configuredInstall.isBlank()) configuredInstall = System.getProperty("pz.it.home");
        if (configuredInstall != null && !configuredInstall.isBlank()) {
            gameInstall = Path.of(configuredInstall).toAbsolutePath().normalize();
        } else {
            Path jarDirectory = gameJar.getParent();
            gameInstall = Files.isDirectory(jarDirectory.resolve("media"))
                ? jarDirectory
                : jarDirectory.getParent();
            if (!Files.isDirectory(gameInstall.resolve("media"))) {
                throw new AssertionError("Game installation media directory required; set -Dpz.game.install or -Dpz.it.home");
            }
        }
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xverify:all", "-javaagent:" + agent,
            "-Dapocbr.postgres.enabled=true", "-Dapocbr.postgres.url=jdbc:postgresql://localhost/fixture", "-Dapocbr.postgres.players.enabled=true",
            "-Dapocbr.postgres.serverId=1", "-Dapocbr.postgres.playerRelational.mutations.sampleMs=1000",
            "-Dapocbr.postgres.playerRelational.mutations.reconcileMs=60000",
            "-cp", String.join(File.pathSeparator, paths), name, directory.resolve("cache").toString())
            .directory(gameInstall.toFile())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try { assertTrue(child.waitFor(45, TimeUnit.SECONDS), "player fixture timed out"); assertEquals(0, child.exitValue(), new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8)); }
        finally { if (child.isAlive()) child.destroyForcibly(); }
    }
}
