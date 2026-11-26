package cloud.apocalipse.pz.agent;

import cloud.apocalipse.pz.agent.experimental.luapreparation.AsyncLuaPreparation;
import cloud.apocalipse.pz.agent.experimental.luapreparation.AsyncLuaPreparationModule;
import java.io.BufferedReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.IndieFileLoader;
import zombie.core.network.ByteBufferReader;
import zombie.core.random.RandStandard;
import zombie.gameStates.ConnectToServerState;
import zombie.network.GameClient;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Forked original-JAR fixture with a probe for the vanilla reader overload. */
public final class AsyncLuaPreparationSmokeMain {
    private static int vanillaReads;
    private static Path reloadProbe;
    private static final RuntimeException STOP_BEFORE_GRAPHICS = new RuntimeException("Fixture stops the reload before graphics");

    public static void premain(String ignored, Instrumentation instrumentation) {
        var module = new AsyncLuaPreparationModule();
        module.initialize();
        module.configure(new AgentBuilder.Default().disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly()))
            .type(named("zombie.core.IndieFileLoader"))
            .transform((dynamic, type, loader, javaModule, domain) -> dynamic.visit(Advice.to(VanillaReader.class)
                .on(named("getStreamReader").and(hasDescriptor("(Ljava/lang/String;Z)Ljava/io/InputStreamReader;")))))
            .type(named("zombie.GameWindow"))
            .transform((dynamic, type, loader, javaModule, domain) -> dynamic.visit(Advice.to(ReloadFrame.class)
                .on(named("render").and(hasDescriptor("()V")))))
            .installOn(instrumentation);
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of("media", "lua", "shared", "prepared.lua");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "return 'olá'");
        ZomboidFileSystem.instance.setCacheDir(Path.of("cache").toAbsolutePath().toString());
        ZomboidFileSystem.instance.init();
        RandStandard.INSTANCE.init();
        GameClient.client = true;
        var connection = new ConnectToServerState(new ByteBufferReader(ByteBuffer.allocate(0)));
        connection.enter();
        expectRead(path, "return 'olá'", true);

        // Enter the same production adapter scope as Core.ResetLua without starting a graphics context.
        require(AsyncLuaPreparation.beginReload("ConnectedToServer"), "join preparation was not started");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            int before = vanillaReads;
            require(read(path).equals("return 'olá'"), "prepared or vanilla content changed");
            if (vanillaReads == before) break;
            require(System.nanoTime() < deadline, "worker result never reached the original loader");
            Thread.sleep(10);
        }
        AsyncLuaPreparation.endReload(true);

        reloadProbe = path;
        try {
            Core.class.getMethod("ResetLua", String.class, String.class)
                .invoke(Core.getInstance(), "client", "ConnectedToServer");
            throw new AssertionError("expected the fixture to stop the reload before rendering");
        } catch (InvocationTargetException expected) {
            if (expected.getCause() != STOP_BEFORE_GRAPHICS) throw expected;
        } finally {
            reloadProbe = null;
        }
        expectRead(path, "return 'olá'", true);

        require(AsyncLuaPreparation.beginReload("ConnectedToServer"), "failed reload leaked its reader scope");
        Files.writeString(path, "return 'updated content'");
        expectRead(path, "return 'updated content'", true);
        Path uncached = Path.of("uncached.lua");
        Files.writeString(uncached, "return true");
        expectRead(uncached, "return true", true);

        connection.exit();
        require(!AsyncLuaPreparation.beginReload("ConnectedToServer"), "join leaked across connection exit");
        expectRead(path, "return 'updated content'", true);
        System.out.println("Client Lua preparation smoke passed");
    }

    private static void expectRead(Path path, String expected, boolean vanilla) throws Exception {
        int before = vanillaReads;
        require(read(path).equals(expected), "unexpected content for " + path);
        require(vanillaReads - before == (vanilla ? 1 : 0), "unexpected reader for " + path);
    }

    private static String read(Path path) throws Exception {
        try (var reader = new BufferedReader(IndieFileLoader.getStreamReader(path.toAbsolutePath().toString()))) {
            return reader.readLine();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void countVanillaRead() { vanillaReads++; }

    public static final class VanillaReader {
        @Advice.OnMethodEnter public static void enter() { countVanillaRead(); }
    }

    public static void stopBeforeGraphics() throws Exception {
        if (reloadProbe == null) return;
        expectRead(reloadProbe, "return 'olá'", false);
        throw STOP_BEFORE_GRAPHICS;
    }

    public static final class ReloadFrame {
        @Advice.OnMethodEnter public static void enter() throws Exception { stopBeforeGraphics(); }
    }
}
