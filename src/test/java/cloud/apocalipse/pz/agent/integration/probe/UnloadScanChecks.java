package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.entity.UsingPlayerIndex;
import cloud.apocalipse.pz.agent.features.optimization.streaming.CellRelevanceModule;
import java.nio.file.Files;
import java.nio.file.Path;
import zombie.entity.EntityBucket;
import zombie.entity.GameEntity;
import zombie.iso.IsoObject;
import zombie.network.ServerMap;

/** Exercises original objects and the transformed bucket entry point on the simulation owner. */
final class UnloadScanChecks {
    private UnloadScanChecks() {}

    static void verify(ServerMap map, Path directory) throws Exception {
        if (AgentConfiguration.cellRelevanceOptimizationEnabled()) {
            verifyRelevance(map);
            Files.createFile(directory.resolve("cell-relevance-oracle"));
        }
        if (Boolean.getBoolean(BucketUnloadProbe.PROPERTY)) {
            verifyLargeBucketOrder();
            Files.createFile(directory.resolve("large-bucket-order-oracle"));
        }
        if (AgentConfiguration.optimizationEnabled(AgentConfiguration.ENTITY_SIMULATION_OPTIMIZATION)) {
            verifyBucketRemoval();
            Files.createFile(directory.resolve("bucket-removal-oracle"));
        }
    }

    private static void verifyRelevance(ServerMap map) {
        var list = map.releventNow;
        var first = new ServerMap.ServerCell();
        var second = new ServerMap.ServerCell();
        require(list.isEmpty(), "Preupdate must clear relevance before the fixture");
        try {
            CellRelevanceModule.append(list, first);
            equalsVanilla(map, first);
            list.set(0, second);
            equalsVanilla(map, first);
            equalsVanilla(map, second);
            list.add(0, first);
            equalsVanilla(map, second);
            list.remove(first);
            equalsVanilla(map, first);
            equalsVanilla(map, second);
        } finally {
            CellRelevanceModule.clear(list);
        }
        equalsVanilla(map, second);
    }

    private static void equalsVanilla(ServerMap map, ServerMap.ServerCell cell) {
        require(CellRelevanceModule.contains(map.releventNow, cell) == map.releventNow.contains(cell),
            "Indexed relevance disagrees with vanilla after editing the public list");
    }

    private static void verifyBucketRemoval() throws Exception {
        var constructor = Class.forName("zombie.entity.EntityBucket$IsoObjectBucket").getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        var bucket = (EntityBucket) constructor.newInstance(0);
        var initialize = GameEntity.class.getDeclaredMethod("ensureComponents");
        var update = EntityBucket.class.getDeclaredMethod("updateMembership", GameEntity.class);
        var removing = GameEntity.class.getDeclaredField("removingFromEngine");
        initialize.setAccessible(true);
        update.setAccessible(true);
        removing.setAccessible(true);
        var first = new IsoObject();
        var middle = new IsoObject();
        var tail = new IsoObject();
        for (var entity : new IsoObject[] { first, middle, tail }) {
            initialize.invoke(entity);
            update.invoke(bucket, entity);
        }
        require(UsingPlayerIndex.update(bucket), "Expected a server-owned using-player index");
        var contents = bucket.getEntities();
        removing.setBoolean(middle, true);
        update.invoke(bucket, middle);
        require(contents.size() == 2 && contents.get(0) == first && contents.get(1) == tail,
            "Middle bucket removal must retain the native tail swap");
        removing.setBoolean(tail, true);
        update.invoke(bucket, tail);
        require(contents.size() == 1 && contents.get(0) == first, "The tail's indexed position must follow its swap");
        removing.setBoolean(first, true);
        update.invoke(bucket, first);
        require(contents.size() == 0, "The last removal must empty the native bucket");
    }

    private static void verifyLargeBucketOrder() throws Exception {
        var constructor = Class.forName("zombie.entity.EntityBucket$IsoObjectBucket").getDeclaredConstructor(int.class);
        var initialize = GameEntity.class.getDeclaredMethod("ensureComponents");
        var update = EntityBucket.class.getDeclaredMethod("updateMembership", GameEntity.class);
        var removing = GameEntity.class.getDeclaredField("removingFromEngine");
        constructor.setAccessible(true);
        initialize.setAccessible(true);
        update.setAccessible(true);
        removing.setAccessible(true);
        var bucket = (EntityBucket) constructor.newInstance(0);
        var expected = new java.util.ArrayList<GameEntity>();
        for (int index = 0; index < 384; index++) {
            GameEntity entity = new IsoObject();
            initialize.invoke(entity);
            update.invoke(bucket, entity);
            expected.add(entity);
        }
        GameEntity first = expected.get(expected.size() / 5);
        boolean readded = false;
        while (!expected.isEmpty()) {
            int index = expected.size() / 5;
            GameEntity target = expected.get(index);
            GameEntity tail = expected.removeLast();
            if (target != tail) expected.set(index, tail);
            removing.setBoolean(target, true);
            update.invoke(bucket, target);
            if (!readded && expected.size() == 370) {
                readded = true;
                removing.setBoolean(first, false);
                update.invoke(bucket, first);
                expected.add(first);
            }
            var actual = bucket.getEntities();
            require(actual.size() == expected.size(), "Unexpected native bucket size");
            for (int position = 0; position < expected.size(); position++) {
                require(actual.get(position) == expected.get(position), "Bucket order disagrees with vanilla tail swap");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
