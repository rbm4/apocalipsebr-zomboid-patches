package cloud.apocalipse.pz.agent.features.telemetry;

import java.io.File;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class TelemetryServerLoopVisitorTest {
    @Test
    void transformsInstalledVanillaGameServerLoopWhenJarIsProvided() throws Exception {
        String gameJar = System.getProperty("pz.game.jar");
        assumeTrue(gameJar != null && !gameJar.isBlank(), "Set -Dpz.game.jar to test the installed 42.22.2 JAR");

        try (ClassFileLocator locator = vanillaGameLocator(gameJar)) {
            assertTransformsLoop(locator, false);
        }
    }

    @Test
    void transformsRuntimePatchedGameServerLoopWhenInstallIsProvided() throws Exception {
        String gameInstall = System.getProperty("pz.game.install");
        String gameJar = System.getProperty("pz.game.jar");
        assumeTrue(
            gameInstall != null && !gameInstall.isBlank() && gameJar != null && !gameJar.isBlank(),
            "Set -Dpz.game.install and -Dpz.game.jar to test the runtime classpath"
        );

        try (ClassFileLocator locator = new ClassFileLocator.Compound(
                new ClassFileLocator.ForFolder(new File(gameInstall)),
                ClassFileLocator.ForJarFile.of(new File(gameJar)),
                ClassFileLocator.ForClassLoader.ofPlatformLoader(),
                ClassFileLocator.ForClassLoader.ofSystemLoader())) {
            assertTransformsLoop(locator, true);
        }
    }

    private static ClassFileLocator vanillaGameLocator(String gameJar) throws java.io.IOException {
        return new ClassFileLocator.Compound(
            ClassFileLocator.ForJarFile.of(new File(gameJar)),
            ClassFileLocator.ForClassLoader.ofPlatformLoader(),
            ClassFileLocator.ForClassLoader.ofSystemLoader()
        );
    }

    private static void assertTransformsLoop(
            ClassFileLocator locator,
            boolean hasLegacyTelemetryAnchors) throws Exception {
        var gameServer = TypePool.Default.of(locator)
            .describe("zombie.network.GameServer")
            .resolve();
        DynamicType.Unloaded<?> transformed = new ByteBuddy()
            .redefine(gameServer, locator)
            .visit(new TelemetryServerLoopVisitor())
            .make();

        assertTrue(transformed.getBytes().length > 0);
        assertTrue(TelemetryServerLoopMetrics.isEnabled());
        assertEquals(hasLegacyTelemetryAnchors, TelemetryServerLoopMetrics.hasLegacyTickAnchor());
        assertEquals(hasLegacyTelemetryAnchors, TelemetryServerLoopMetrics.hasLegacyLoopAnchor());
    }
}
