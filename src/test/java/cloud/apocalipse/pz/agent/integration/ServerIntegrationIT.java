package cloud.apocalipse.pz.agent.integration;

import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

/** Failsafe only: one process for all registered assertions, including after assertion failures. */
@Execution(ExecutionMode.SAME_THREAD)
class ServerIntegrationIT {
    private static ServerTestContext server;
    private static int passed, failed, skipped;

    @BeforeAll
    static void start() throws Exception {
        try { server = ServerTestContext.start(); }
        catch (Throwable failure) {
            IntegrationLog.fail("Server startup: " + failure.getMessage());
            throw failure;
        }
    }

    @TestFactory
    Stream<DynamicTest> registeredAssertions() throws Exception {
        var assertions = ServiceLoader.load(ServerAssertion.class).stream()
            .map(ServiceLoader.Provider::get)
            .sorted(Comparator.comparing(ServerAssertion::name)).toList();
        assertFalse(assertions.isEmpty(), "No server assertions registered");
        assertEquals(assertions.size(), assertions.stream().map(ServerAssertion::name).distinct().count(),
            "Server assertion names must be unique");
        var inventory = server.hookInventory();
        var hooks = server.hooks().stream().map(hook -> reported(hook.name(), () -> {
            String state = inventory.getProperty("module." + hook.module());
            org.junit.jupiter.api.Assumptions.assumeTrue("ENABLED".equals(state), state);
            server.assertStartupHook(hook.target(), hook.method(), hook.helper(), hook.invocation());
        }));
        return Stream.concat(hooks, assertions.stream()
            .map(assertion -> reported(assertion.name(), () -> assertion.verify(server))));
    }

    private static DynamicTest reported(String name, Executable assertion) {
        return DynamicTest.dynamicTest(name, () -> {
            long start = System.nanoTime();
            IntegrationLog.info("RUN " + name);
            try {
                server.assertAlive();
                assertion.execute();
                server.assertHealthy();
                passed++;
                IntegrationLog.pass(name + " (" + (System.nanoTime() - start) / 1_000_000 + " ms)");
            } catch (org.opentest4j.TestAbortedException unverified) {
                skipped++;
                IntegrationLog.skip(name + ": " + unverified.getMessage());
                throw unverified;
            } catch (Throwable failure) {
                failed++;
                IntegrationLog.fail(name + ": " + failure.getMessage());
                throw failure;
            }
        });
    }

    @AfterAll
    static void stop() throws Exception {
        if (server == null) IntegrationLog.info("Assertions not run: server startup failed");
        else IntegrationLog.info("Assertions: " + passed + " passed, " + failed + " failed, " + skipped + " skipped");
        try { if (server != null) server.close(); }
        catch (Throwable failure) {
            IntegrationLog.fail("Server shutdown: " + failure.getMessage());
            throw failure;
        }
    }
}
