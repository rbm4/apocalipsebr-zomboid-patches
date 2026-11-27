package cloud.apocalipse.pz.agent.features.optimization;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Stack;
import cloud.apocalipse.pz.agent.AgentModules;
import cloud.apocalipse.pz.agent.features.optimization.animal.perception.ServerAnimalPerception;
import zombie.GameTime;
import zombie.MovingObjectUpdateScheduler;
import zombie.MovingObjectUpdateSchedulerUpdateBucket;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.PlayerCheats;
import zombie.characters.animals.datas.AnimalBreed;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.characters.animals.datas.AnimalData;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;

import static cloud.apocalipse.pz.agent.features.optimization.OptimizationOriginalSmokeMain.allocate;
import static cloud.apocalipse.pz.agent.features.optimization.OptimizationOriginalSmokeMain.check;
import static cloud.apocalipse.pz.agent.features.optimization.OptimizationOriginalSmokeMain.field;
import static cloud.apocalipse.pz.agent.features.optimization.OptimizationOriginalSmokeMain.gameClass;

final class AnimalPerceptionOriginalSmoke {
    private static final RuntimeException INHERITED_UPDATE = new RuntimeException("Inherited player update reached");
    private static final RuntimeException LOS_FAILURE = new RuntimeException("Fixture LOS failure");

    private AnimalPerceptionOriginalSmoke() {
    }

    static void run(Path original, Path evidence, boolean enabled) throws Exception {
        var module = AgentModules.all().stream().filter(it -> it.name().equals("animal-perception")).findFirst().orElseThrow();
        check((module.disabledReason() == null) == enabled, "Wrong animal perception configuration");
        for (var hook : module.hookChecks()) {
            Class<?> target = gameClass(hook.targetClass());
            check(Path.of(target.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(original),
                "Animal hook target did not come from the original JAR");
            target.getDeclaredMethods();
            var calls = new Properties();
            Path file = evidence.resolve(hook.targetClass() + ".properties");
            if (Files.isRegularFile(file)) {
                try (var input = Files.newInputStream(file)) { calls.load(input); }
            }
            String key = hook.method() + "|" + hook.helperClass().replace('.', '/') + "|" + hook.helperMethod();
            check(calls.containsKey(key) == enabled, "Wrong animal hook evidence for " + hook.id());
        }

        IsoCell cell = new IsoCell(1, 1);
        TestAnimal first = animal(cell, 0);
        TestAnimal samePhase = animal(cell, 10);
        TestAnimal staggered = animal(cell, 5);
        IsoGridSquare square = new IsoGridSquare(cell, null, 0, 0, 0);
        IsoPlayer human = allocate(IsoPlayer.class);
        human.cheats = new PlayerCheats();
        human.setCurrentSquare(square);
        human.setX(100);
        IsoZombie near = zombie(square, 10);
        IsoZombie far = zombie(square, 40);
        var objects = cell.getObjectList();
        objects.addAll(List.of(first, samePhase, staggered, near, far, human));
        var scheduler = MovingObjectUpdateScheduler.instance;
        var buckets = (MovingObjectUpdateSchedulerUpdateBucket[]) field(
            MovingObjectUpdateScheduler.class, "simulationLevels").get(scheduler);
        var full = buckets[UpdateSchedulerSimulationLevel.FULL.getUpdateOrderIndex()];
        full.clear();
        full.add(first);
        full.add(samePhase);
        full.add(staggered);
        var tick = field(MovingObjectUpdateScheduler.class, "frameCounter");
        try {
            List<IsoMovingObject> expected = objects.stream()
                .filter(actor -> actor == human || actor == near || (!enabled && actor == far)).toList();
            float initialAlert = first.getBehavior().lastAlerted;
            step(scheduler, tick, 0);
            if (enabled) {
                check(first.getBehavior().lastAlerted == initialAlert - GameTime.getInstance().getMultiplier(),
                    "Candidate reactions aged the cooldown again");
            }
            check(first.targets.equals(expected) && samePhase.targets.equals(expected), "Wrong candidates or cell order");
            check(first.spottedListContents().equals(List.of(first)), "Native LOS lost its self entry");
            check(first.inherited == 1 && staggered.inherited == 1, "LOS hook interrupted the native update sequence");
            check(staggered.scans == (enabled ? 0 : 1), "Staggered animal scanned on the wrong tick");

            // Move a zombie across buckets between two queries with identical membership.
            first.afterScan = () -> far.setX(4);
            samePhase.targets.clear();
            step(scheduler, tick, 10);
            check(samePhase.targets.contains(far), "Same-frame position refresh missed a moved zombie");

            IsoZombie added = zombie(square, 3);
            first.afterScan = () -> {
                var iterator = objects.iterator();
                while (iterator.hasNext()) {
                    if (iterator.next() == near) { iterator.remove(); break; }
                }
                objects.add(added);
            };
            samePhase.targets.clear();
            step(scheduler, tick, 20);
            check(samePhase.targets.containsAll(List.of(human, far, added)) && !samePhase.targets.contains(near),
                "Same-frame index missed an equal-size membership change");
            first.afterScan = null;

            int scans = first.scans;
            float cooldown = first.getBehavior().lastAlerted;
            staggered.adef.fleeZombies = false;
            staggered.targets.clear();
            step(scheduler, tick, 25);
            check(first.scans == scans + (enabled ? 0 : 1), "LOS cadence did not follow the scheduler tick");
            check(staggered.targets.contains(human) && (staggered.targets.contains(far) != enabled),
                "Flee configuration pruned the wrong candidates");
            check(first.getBehavior().lastAlerted < cooldown, "Skipped LOS froze the alert cooldown");

            int explicit = first.scans;
            first.updateLOS();
            check(first.scans == explicit + 1, "Explicit LOS call was throttled");
            first.fail = true;
            try {
                step(scheduler, tick, 30);
                throw new AssertionError("Expected a native LOS callback failure");
            } catch (RuntimeException failure) {
                if (failure != LOS_FAILURE) throw failure;
            }
            if (enabled) {
                check(field(ServerAnimalPerception.class, "current").get(null) == null, "Failed update retained its frame");
            }
            first.fail = false;

            GameServer.server = false;
            scans = first.scans;
            step(scheduler, tick, 31);
            check(first.scans == scans + 1, "Non-server LOS was throttled");
        } finally {
            GameServer.server = true;
            full.clear();
            tick.setLong(scheduler, 0);
        }
    }

    private static void step(MovingObjectUpdateScheduler scheduler, Field tick, long value) throws Exception {
        tick.setLong(scheduler, value);
        scheduler.update();
    }

    private static IsoZombie zombie(IsoGridSquare square, float x) throws Exception {
        IsoZombie zombie = allocate(IsoZombie.class);
        zombie.setX(x);
        zombie.setCurrentSquare(square);
        return zombie;
    }

    private static TestAnimal animal(IsoCell cell, int id) throws Exception {
        TestAnimal animal = allocate(TestAnimal.class);
        animal.testCell = cell;
        animal.testId = id;
        animal.targets = new ArrayList<>();
        animal.adef = new AnimalDefinitions();
        animal.adef.fleeZombies = true;
        field(IsoPlayer.class, "spottedList").set(animal, new Stack<IsoMovingObject>());
        field(IsoAnimal.class, "zoneCheckTimer").setFloat(animal, Float.MAX_VALUE);
        field(IsoAnimal.class, "data").set(animal, allocate(QuietData.class));
        var behavior = new BaseAnimalBehavior(animal) {
            @Override public void update() {}

            @Override public void spotted(IsoMovingObject other, boolean forced, float distance) {
                animal.targets.add(other);
                super.spotted(other, forced, distance);
            }
        };
        behavior.lastAlerted = 1_000.0F;
        field(IsoAnimal.class, "behavior").set(animal, behavior);
        return animal;
    }

    private static final class QuietData extends AnimalData {
        QuietData(IsoAnimal parent, AnimalBreed breed) { super(parent, breed); }
        @Override public void update() {}
    }

    private static final class TestAnimal extends IsoAnimal {
        IsoCell testCell;
        int testId;
        List<IsoMovingObject> targets;
        Runnable afterScan;
        int scans;
        int inherited;
        boolean fail;

        TestAnimal() { super(null); }
        List<IsoMovingObject> spottedListContents() { return spottedList; }

        @Override public IsoCell getCell() { return testCell; }
        @Override public int getID() { return testId; }
        @Override public boolean getVariableBoolean(String name) { return false; }
        @Override public boolean isDead() { return false; }
        @Override public boolean isExistInTheWorld() { return false; }
        @Override public float getAnimalSize() { return 1; }
        @Override public void preupdate() {}
        @Override public void frameStep() {}
        @Override public void updateEmitter() {}
        @Override public void separate() {}
        @Override public void updateStress() {}
        @Override public void respondToSound() {}
        @Override public boolean tryThump(IsoGridSquare square) { return false; }

        @Override public boolean isAnimal() {
            // The first check in the inherited IsoPlayer update marks continuation past LOS.
            throw INHERITED_UPDATE;
        }

        @Override public void update() {
            try {
                super.update();
            } catch (RuntimeException failure) {
                if (failure != INHERITED_UPDATE) throw failure;
                inherited++;
            }
        }

        @Override public void updateLOS() {
            scans++;
            if (fail) throw LOS_FAILURE;
            super.updateLOS();
            if (afterScan != null) afterScan.run();
        }
    }
}
