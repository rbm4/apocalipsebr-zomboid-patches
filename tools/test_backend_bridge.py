"""Exercise the standalone 42.21.0 production bridge without game/native stubs."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r"""
import zombie.network.ApocBRBackendBridge;
import zombie.network.ApocBRBackendBridge.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public class BackendBridgeTest {
    static int checks;
    static final Context CONTEXT = new Context(1_000L, "1993-07-09T13:20", 9_000L);
    static final PublicCharacter ALICE = new PublicCharacter("Alice", "Alice");
    static final PublicCharacter BOB = new PublicCharacter("Bob", "Bob");
    static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    static Settings settings(int capacity, int batch) {
        return new Settings(capacity, batch, Duration.ofMillis(10),
                Duration.ofMillis(1), Duration.ofMillis(4));
    }
    static void await(CountDownLatch latch) throws Exception {
        check(latch.await(5, TimeUnit.SECONDS), "fixture coordination timed out");
    }
    static void eventually(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("fixture condition timed out");
            Thread.sleep(1);
        }
    }
    static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("expected validation failure"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    static void stopped(Runnable action) {
        try { action.run(); throw new AssertionError("expected lifecycle failure"); }
        catch (IllegalStateException expected) { checks++; }
    }
    static void stop(ApocBRBackendBridge bridge) throws Exception {
        bridge.close();
        check(bridge.awaitTermination(Duration.ofSeconds(5)), "worker terminated");
    }

    static void boundsAndRetry() throws Exception {
        var bridge = new ApocBRBackendBridge("pve", settings(3, 2));
        check(bridge.publishChat(CONTEXT, ALICE, "Hello survivors"), "accept chat");
        check(bridge.publishDeath(CONTEXT, ALICE, BOB), "accept death");
        check(bridge.publishConnection(CONTEXT, BOB, true), "accept presence");
        check(!bridge.publishPopulation(CONTEXT, 2, 42), "full queue rejects newest");
        check(bridge.status().state() == State.NEW && bridge.status().queued() == 3,
                "publication does not start worker or drain queue");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<Event>> firstAttempt = new AtomicReference<>();
        AtomicReference<Thread> deliveryThread = new AtomicReference<>();
        List<Event> received = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        bridge.start(events -> {
            deliveryThread.set(Thread.currentThread());
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstAttempt.set(events);
                throw new IOException("fixture failure");
            }
            if (call == 2 && events != firstAttempt.get()) {
                throw new AssertionError("retry must keep the exact immutable batch");
            }
            received.addAll(events);
            for (Event event : events) done.countDown();
        });
        await(done);
        stop(bridge);
        check(deliveryThread.get().isVirtual(), "delivery runs on virtual thread");
        check(deliveryThread.get() != Thread.currentThread(), "delivery isolated from producer");
        check(calls.get() == 3, "retry first batch then send final batch");
        check(received.size() == 3 && received.get(0).type() == EventType.PLAYER_CHAT
                && received.get(1).type() == EventType.PLAYER_DEATH
                && received.get(2).type() == EventType.PLAYER_CONNECTED, "FIFO across retry");
        for (int i = 0; i < received.size(); i++) {
            Event event = received.get(i);
            check(event.sourceEventId().endsWith(":" + (i + 1)), "ordered source identity");
            check(event.serverKey().equals("pve")
                    && event.source().equals("pz-engine") && event.schemaVersion() == 3,
                    "backend identity envelope");
            check(event.occurredAt() == 1_000L && event.expiresAt() == 9_000L
                    && event.gameTime().equals(CONTEXT.gameTime()), "capture timestamps retained");
        }
        check(received.get(1).publicPayload().get("killerCharacterName").equals("Bob")
                && received.get(0).publicPayload().get("characterName").equals("Alice")
                && !received.get(0).publicPayload().containsKey("characterId"), "name-based character identity");
        try { firstAttempt.get().clear(); throw new AssertionError("mutable batch"); }
        catch (UnsupportedOperationException expected) { checks++; }
        try { received.getFirst().publicPayload().put("x", 1); throw new AssertionError("mutable payload"); }
        catch (UnsupportedOperationException expected) { checks++; }
        Status status = bridge.status();
        check(status.accepted() == 3 && status.delivered() == 3 && status.deliveryFailures() == 1
                && status.droppedFull() == 1 && status.queued() == 0 && status.inFlight() == 0,
                "delivery and overflow accounting");
        check(!bridge.publishChat(CONTEXT, ALICE, "after stop")
                && bridge.status().rejectedStopped() == 1, "terminal rejection counted");
        stopped(() -> bridge.start(events -> {}));
    }

    static void typedPayloads() throws Exception {
        var bridge = new ApocBRBackendBridge("pve", settings(8, 8));
        bridge.publishDeath(CONTEXT, ALICE, null);
        bridge.publishConnection(CONTEXT, ALICE, false);
        bridge.publishPopulation(CONTEXT, null, 0);
        bridge.publishPopulation(CONTEXT, 2, null);
        bridge.publishWorldClock(CONTEXT, 25.5);
        List<Event> received = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(5);
        bridge.start(events -> {
            received.addAll(events);
            for (Event event : events) done.countDown();
        });
        await(done);
        stop(bridge);
        check(!received.get(0).publicPayload().containsKey("killerCharacterName"), "unknown killer omitted");
        check(received.get(1).type() == EventType.PLAYER_DISCONNECTED, "disconnect type");
        check(received.get(2).publicPayload().equals(Map.of("loadedZombieCount", 0)),
                "known zero differs from missing online count");
        check(received.get(3).publicPayload().equals(Map.of("playersOnline", 2)),
                "missing zombie count is not invented");
        check(received.get(4).publicPayload().equals(Map.of("worldAgeHours", 25.5)), "world age field");
        var another = new ApocBRBackendBridge("pve", settings(1, 1));
        AtomicReference<Event> otherEvent = new AtomicReference<>();
        another.publishDeath(CONTEXT, ALICE, null);
        another.start(events -> otherEvent.set(events.getFirst()));
        eventually(() -> another.status().delivered() == 1);
        stop(another);
        check(!otherEvent.get().sourceEventId().equals(received.getFirst().sourceEventId()),
                "new instance cannot reuse source identity");
    }

    static void concurrentProducers() throws Exception {
        var bridge = new ApocBRBackendBridge("pve", settings(2_048, 100));
        List<Thread> producers = new ArrayList<>();
        AtomicBoolean rejected = new AtomicBoolean();
        for (int t = 0; t < 8; t++) {
            Thread producer = Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 200; i++) {
                    if (!bridge.publishChat(CONTEXT, ALICE, "message " + i)) rejected.set(true);
                }
            });
            producers.add(producer);
        }
        for (Thread producer : producers) producer.join();
        check(!rejected.get() && bridge.status().queued() == 1_600, "concurrent publication bounded");
        List<Event> received = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1_600);
        bridge.start(events -> {
            received.addAll(events);
            for (Event event : events) done.countDown();
        });
        await(done);
        stop(bridge);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < received.size(); i++) {
            String id = received.get(i).sourceEventId();
            check(ids.add(id), "source identities unique under concurrent publication");
            check(Long.parseLong(id.substring(id.lastIndexOf(':') + 1)) == i + 1,
                    "source sequence follows enqueue order");
        }
        check(bridge.status().delivered() == 1_600, "all concurrent observations delivered");
    }

    static void shutdown() throws Exception {
        var bridge = new ApocBRBackendBridge("pve", settings(4, 2));
        for (int i = 0; i < 3; i++) bridge.publishChat(CONTEXT, ALICE, "message " + i);
        CountDownLatch entered = new CountDownLatch(1);
        bridge.start(events -> { entered.countDown(); new CountDownLatch(1).await(); });
        await(entered);
        for (int i = 0; i < 3; i++) {
            check(bridge.publishPopulation(CONTEXT, 2, i), "producer progresses during blocked delivery");
        }
        check(!bridge.publishPopulation(CONTEXT, 2, 99), "queue remains bounded behind blocked delivery");
        stopped(() -> bridge.start(events -> {}));
        stop(bridge);
        Status status = bridge.status();
        check(status.state() == State.STOPPED && status.inFlight() == 2 && status.queued() == 4
                && status.delivered() == 0 && status.accepted() == 6 && status.droppedFull() == 1,
                "interrupted delivery retains unacknowledged data within queue plus batch bounds");
        bridge.close();
        check(bridge.awaitTermination(Duration.ofSeconds(1)), "shutdown idempotent");

        var batching = new ApocBRBackendBridge("pve",
                new Settings(4, 4, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        AtomicInteger calls = new AtomicInteger();
        batching.publishChat(CONTEXT, ALICE, "batching");
        batching.start(events -> calls.incrementAndGet());
        eventually(() -> batching.status().queued() == 0);
        stop(batching);
        check(calls.get() == 0 && batching.status().inFlight() == 1,
                "interruption while gathering does not lose removed events");

        var idle = new ApocBRBackendBridge("pve", settings(1, 1));
        idle.start(events -> { throw new AssertionError("no empty deliveries"); });
        stop(idle);
        check(idle.status().accepted() == 0 && idle.status().delivered() == 0, "idle shutdown");
        var neverStarted = new ApocBRBackendBridge("pve", settings(1, 1));
        stop(neverStarted);
        stopped(() -> neverStarted.start(events -> {}));
    }

    static void validation() throws Exception {
        var bridge = new ApocBRBackendBridge("pve", settings(4, 2));
        invalid(() -> new PublicCharacter(" ", "Alice"));
        invalid(() -> new PublicCharacter("life", "x".repeat(129)));
        invalid(() -> new Context(-1, null, null));
        invalid(() -> new Context(10, null, 10L));
        invalid(() -> new Settings(0, 1, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofSeconds(2)));
        invalid(() -> new Settings(1, 2, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofSeconds(2)));
        invalid(() -> new Settings(1, 1, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2)));
        invalid(() -> new Settings(1, 1, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1)));
        invalid(() -> bridge.publishChat(CONTEXT, ALICE, "x".repeat(2_049)));
        invalid(() -> bridge.publishPopulation(CONTEXT, null, null));
        invalid(() -> bridge.publishPopulation(CONTEXT, -1, null));
        invalid(() -> bridge.publishPopulation(CONTEXT, null, -1));
        invalid(() -> bridge.publishWorldClock(CONTEXT, Double.NaN));
        invalid(() -> bridge.publishWorldClock(new Context(1_000, null, null), 1));
        check(bridge.status().accepted() == 0, "invalid observations never enter queue");
        stop(bridge);
    }

    static void retryPolicy() throws Exception {
        var bridge = new ApocBRBackendBridge("pve", settings(1, 1));
        bridge.publishChat(CONTEXT, ALICE, "retry policy");
        AtomicInteger calls = new AtomicInteger();
        AtomicLong firstAttempt = new AtomicLong();
        AtomicLong retryAttempt = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);
        bridge.start(events -> {
            if (calls.incrementAndGet() == 1) {
                firstAttempt.set(System.nanoTime());
                throw new ApocBRBackendBridge.DeliveryFailure("HTTP_429", false, 80);
            }
            retryAttempt.set(System.nanoTime());
            done.countDown();
        });
        await(done);
        stop(bridge);
        check(retryAttempt.get() - firstAttempt.get() >= TimeUnit.MILLISECONDS.toNanos(80),
                "adapter cooldown overrides shorter exponential retry delay");
    }

    public static void main(String[] args) throws Exception {
        boundsAndRetry();
        typedPayloads();
        concurrentProducers();
        shutdown();
        validation();
        retryPolicy();
        System.out.println("Passed " + checks + " backend bridge assertions.");
    }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-backend-bridge-") as directory:
        work = Path(directory)
        harness = work / "BackendBridgeTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        output = work / "classes"
        production = ROOT / "42.21.0/src/zombie/network/ApocBRBackendBridge.java"
        subprocess.run([
            str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-d", str(output),
            str(production), str(harness),
        ], check=True, timeout=60)
        subprocess.run([
            str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "BackendBridgeTest",
        ], check=True, timeout=60)


if __name__ == "__main__":
    main()
