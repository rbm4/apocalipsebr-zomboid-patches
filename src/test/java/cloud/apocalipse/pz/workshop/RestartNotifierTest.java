package cloud.apocalipse.pz.workshop;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RestartNotifierTest {
    private static final URI ENDPOINT = URI.create("https://example.invalid/api/server/mod-update/restart");

    @Test
    void configurationRejectsUnsafeDestinationsAndTokens() {
        assertEquals(ENDPOINT, ModUpdateRestartNotifier.validatedEndpoint(ENDPOINT.toString(), "test-token"));
        for (String url : new String[] {"http://example.invalid/api/server/mod-update/restart",
                "https://user@example.invalid/api/server/mod-update/restart",
                ENDPOINT + "?token=x", ENDPOINT + "#fragment", "https://example.invalid/other"}) {
            assertThrows(IllegalArgumentException.class,
                () -> ModUpdateRestartNotifier.validatedEndpoint(url, "test-token"));
        }
        assertThrows(IllegalArgumentException.class,
            () -> ModUpdateRestartNotifier.validatedEndpoint(ENDPOINT.toString(), "bad\r\nkey"));
        assertThrows(IllegalArgumentException.class,
            () -> ModUpdateRestartNotifier.validatedEndpoint(null, null));
    }

    @Test
    void submissionIsNonBlockingVirtualAndCoalesced() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        ModUpdateRestartNotifier notifier = new ModUpdateRestartNotifier(ENDPOINT, "test-token", (url, token) -> {
            worker.set(Thread.currentThread());
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return RestartHttpClient.Result.ACCEPTED;
        }, clock::get);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertTrue(notifier.submit()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(worker.get().isVirtual());
            assertFalse(notifier.submit());
        } finally {
            release.countDown();
        }
        worker.get().join(5000);
        assertFalse(worker.get().isAlive());
        clock.set(Duration.ofMinutes(2).toNanos());
        assertFalse(notifier.submit(), "Acknowledgement suppresses requests until process restart");
        assertEquals(1, calls.get());
    }

    @Test
    void failureCanRetryOnLaterDetectionAfterCooldown() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        ModUpdateRestartNotifier notifier = new ModUpdateRestartNotifier(ENDPOINT, "test-token", (url, token) -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            throw new java.io.IOException("transport failure");
        }, clock::get);
        assertTrue(notifier.submit());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        worker.get().join(5000);
        assertFalse(worker.get().isAlive());
        assertFalse(notifier.submit());
        clock.set(Duration.ofSeconds(60).toNanos());
        assertTrue(notifier.submit());
    }

    @Test
    void responseRequiresMatchingHttpAndJsonStatus() throws Exception {
        assertEquals(RestartHttpClient.Result.ACCEPTED, RestartHttpClient.decode(202, "{\"status\":\"accepted\"}"));
        assertEquals(RestartHttpClient.Result.ALREADY_IN_PROGRESS,
            RestartHttpClient.decode(200, "{\"status\":\"already_in_progress\"}"));
        assertThrows(java.io.IOException.class, () -> RestartHttpClient.decode(200, "{\"status\":\"accepted\"}"));
        assertThrows(java.io.IOException.class, () -> RestartHttpClient.decode(401, "{\"status\":\"accepted\"}"));
        assertThrows(java.io.IOException.class, () -> RestartHttpClient.decode(202, "{}"));
        assertThrows(java.io.IOException.class, () -> RestartHttpClient.decode(202, "{\"status\":[\"accepted\"]}"));
        assertThrows(java.io.IOException.class, () -> RestartHttpClient.decode(202, "not json"));
    }

    @Test
    void httpContractRedirectRejectionAndBodyLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> observed = new AtomicReference<>();
        AtomicReference<String> response = new AtomicReference<>("{\"status\":\"accepted\"}");
        AtomicInteger status = new AtomicInteger(202);
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/api/server/mod-update/restart", exchange -> {
            observed.set(exchange.getRequestMethod() + "|" + exchange.getRequestHeaders().getFirst("X-API-Key")
                + "|" + exchange.getRequestHeaders().getFirst("Content-Type") + "|"
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (status.get() == 302) exchange.getResponseHeaders().set("Location", "/redirect");
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/redirect", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            // Loopback HTTP is used only to exercise transport; runtime configuration requires HTTPS.
            URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + ENDPOINT.getPath());
            RestartHttpClient client = RestartHttpClient.create();
            assertEquals(RestartHttpClient.Result.ACCEPTED, client.post(local, "test-token"));
            assertEquals("POST|test-token|application/json|{\"message\":\"Restart para update de mods\"}", observed.get());
            status.set(302);
            assertThrows(java.io.IOException.class, () -> client.post(local, "test-token"));
            assertEquals(0, redirected.get());
            status.set(202);
            response.set("x".repeat(4097));
            assertThrows(java.io.IOException.class, () -> client.post(local, "test-token"));
        } finally {
            server.stop(0);
        }
    }
}
