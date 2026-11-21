package cloud.apocalipse.pz.agent.features.npc;

import cloud.apocalipse.pz.agent.config.AgentConfiguration.NpcSettings;
import cloud.apocalipse.pz.agent.features.npc.planning.NpcDecisionPlanner;
import cloud.apocalipse.pz.agent.features.npc.movement.MovementGrid.Tile;
import cloud.apocalipse.pz.agent.features.npc.brain.*;
import cloud.apocalipse.pz.agent.features.npc.state.HumanState;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcEngineTest {
    static final Tile START = new Tile(1,1), GOAL = new Tile(2,1);
    static NpcDecisionPlanner.Route route() {
        return new NpcDecisionPlanner.Route(new NpcDecisionPlanner.Grid(Set.of(START,GOAL),
            List.of(new NpcDecisionPlanner.Edge(START,GOAL,1))), START, GOAL, false);
    }

    @Test void disabledAndNotReadyDoNotStart() {
        try (var disabled = new NpcEngine(new NpcSettings(false,true));
             var waiting = new NpcEngine(new NpcSettings(true,true))) {
            disabled.update(new Object(), 0);
            waiting.update(null, 0);
            assertFalse(disabled.status().started());
            assertFalse(waiting.status().started());
            assertEquals(0, waiting.status().generation());
        }
    }

    @Test void synchronousPlanningCadenceAndWorldInvalidation() {
        try (var engine = new NpcEngine(new NpcSettings(true,false))) {
            Object world = new Object();
            engine.update(world, 0);
            var planner = engine.planner();
            var slot = planner.open();
            long ticket = slot.submit(route(), 0);
            assertEquals(List.of(START,GOAL), ((NpcDecisionPlanner.Planned)slot.take(ticket).answer()).route());
            engine.update(world, 1);
            assertEquals(1,engine.status().serverTicks());
            engine.update(world, NpcEngine.PERIOD);
            assertEquals(2,engine.status().serverTicks());
            long stale = slot.submit(route(), NpcEngine.PERIOD);
            engine.update(new Object(), 2*NpcEngine.PERIOD);
            assertNull(slot.take(stale));
            assertEquals(-1,slot.submit(route(), 0));
            assertThrows(IllegalStateException.class,planner::open);
            assertEquals(2,engine.status().generation());
            engine.update(null, 3*NpcEngine.PERIOD);
            assertFalse(engine.status().started());
        }
    }

    @Test void asyncWorkRunsDetachedAndStopsOffOwner() throws Exception {
        var engine = new NpcEngine(new NpcSettings(true,true));
        try {
            engine.update(new Object(), 0);
            var slot = engine.planner().open();
            long ticket = slot.submit(route(), 0);
            NpcDecisionPlanner.Result result = null;
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while (result == null && System.nanoTime()<deadline) {
                result = slot.take(ticket);
                if (result == null) Thread.sleep(5);
            }
            assertNotNull(result);
            assertEquals(List.of(START,GOAL), ((NpcDecisionPlanner.Planned)result.answer()).route());
            assertTrue(engine.status().workerTicks()>0);
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThrows(ExecutionException.class, () -> executor.submit(engine::planner).get());
                executor.submit(engine::close).get(3,TimeUnit.SECONDS);
            }
            assertTrue(engine.status().stopped());
            assertNull(slot.take(ticket));
        } finally { engine.close(); }
    }

    @Test void failureStopsAdmissionsUntilRestart() {
        try (var engine = new NpcEngine(new NpcSettings(true,false))) {
            Object world = new Object(); engine.update(world,0);
            var planner = engine.planner();
            var slot = planner.open();
            long ticket = slot.submit(route(),0);
            planner.fail(new IllegalArgumentException("planning failed"));
            engine.update(world,1);
            assertTrue(engine.status().failed());
            assertTrue(engine.status().stopped());
            assertNull(slot.take(ticket));
            assertThrows(IllegalStateException.class,engine::planner);
            engine.update(new Object(),NpcEngine.PERIOD);
            assertEquals(1,engine.status().generation());
        }
    }

    @Test void plannerAdmissionCaptureAndCancellationAreBounded() {
        var planner = new NpcDecisionPlanner(false); planner.bindOwner(); planner.beginServerTick();
        for (int i=0;i<4;i++) assertTrue(planner.admitCapture());
        assertFalse(planner.admitCapture());
        var first = planner.open();
        long ticket = first.submit(route(),0); first.cancel(); assertNull(first.take(ticket));
        for (int i=1;i<NpcDecisionPlanner.MAX_SLOTS;i++) planner.open();
        assertThrows(IllegalStateException.class,planner::open);
        first.close(); planner.open(); planner.stop();
        assertThrows(IllegalStateException.class,planner::open);
    }

    @Test void workerPublishesFailureAndNeverRunsOnAuthority() throws Exception {
        var failure = new CompletableFuture<Throwable>();
        Thread authority = Thread.currentThread();
        var worker = new ServerHumanSimulationWorker(1_000_000L, ignored -> {
            assertNotSame(authority,Thread.currentThread());
            throw new IllegalArgumentException("worker failure");
        }, failure::complete);
        try {
            worker.start();
            assertInstanceOf(IllegalArgumentException.class,failure.get(3,TimeUnit.SECONDS));
            assertTrue(worker.failed());
        } finally { worker.shutdown(); }
    }

    @Test void asyncPassHonorsJobLimitAndReplacementTickets() throws Exception {
        var planner = new NpcDecisionPlanner(true); planner.bindOwner();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var slots = new java.util.ArrayList<NpcDecisionPlanner.Slot>();
            var tickets = new java.util.ArrayList<Long>();
            for (int i=0;i<65;i++) {
                var slot = planner.open(); slots.add(slot); tickets.add(slot.submit(route(),0));
            }
            long replaced = tickets.getFirst();
            tickets.set(0,slots.getFirst().submit(route(),1));
            executor.submit(() -> planner.tick(2)).get(3,TimeUnit.SECONDS);
            assertNull(slots.getFirst().take(replaced));
            int completed = 0;
            for (int i=0;i<65;i++) if (slots.get(i).take(tickets.get(i)) != null) completed++;
            assertEquals(64,completed);
            executor.submit(() -> planner.tick(3)).get(3,TimeUnit.SECONDS);
            assertNotNull(slots.getLast().take(tickets.getLast()));
        } finally { planner.stop(); }
    }

    @Test void copiedEngagementSelectsZombiesAndRetainsPlayerSafety() {
        var planner = new NpcDecisionPlanner(false); planner.bindOwner();
        try {
            var zombie = new HumanSenses.Identity(
                HumanSenses.Kind.ZOMBIE,(short)10,1,1);
            var player = new HumanSenses.Identity(
                HumanSenses.Kind.PLAYER,(short)11,2,2);
            var contacts = List.of(
                new HumanSenses.Contact(zombie,1,1,0,4,0,10,true),
                new HumanSenses.Contact(player,1,1,0,1,0,10,true));
            var senses = new HumanSenses.Frame(true,10,contacts,
                HumanSenses.Coverage.COMPLETE,
                HumanSenses.Coverage.COMPLETE,false,
                new HumanSenses.Work(1,1,1,1,1));
            var observation = new HumanObservation(10,List.of(),true,
                HumanState.INITIAL,senses,
                new HumanTerritory(true,true,Set.of(zombie,player)));
            var slot = planner.open();
            long ticket = slot.submit(new NpcDecisionPlanner.Select(observation,null,
                HumanEngagement.Settings.DEFAULT),10);
            var selection = ((NpcDecisionPlanner.Selected)slot.take(ticket).answer()).selection();
            assertEquals(zombie,selection.target().identity());
            assertEquals(HumanEngagement.Choice.ENGAGE,selection.choice());
        } finally { planner.stop(); }
    }
}
