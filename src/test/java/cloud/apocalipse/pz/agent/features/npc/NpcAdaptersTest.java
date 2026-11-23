package cloud.apocalipse.pz.agent.features.npc;

import java.util.*;
import java.lang.reflect.InvocationTargetException;
import cloud.apocalipse.pz.agent.features.npc.furniture.FurnitureBindings;
import cloud.apocalipse.pz.agent.features.npc.planning.NpcDecisionPlanner;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NpcAdaptersTest {
    public static class Cell {
        private final Set<Object> objectList=new HashSet<>();
        public Set<Object> objects(){return objectList;}
    }
    public static class Furniture {
        private Object square;
        public void setSquare(Object square){this.square=square;}
        public void reset(){square=null;}
        public void addToWorld(){}
        public void removeFromWorld(){}
        public void removeFromSquare(){square=null;}
    }
    @Test void transformedCellConstructorTracksEveryMutationWithoutChangingSetSemantics()throws Exception {
        var type=NpcModule.actorTransform("zombie.iso.IsoCell",new ByteBuddy().redefine(Cell.class))
            .make().load(getClass().getClassLoader(),ClassLoadingStrategy.Default.CHILD_FIRST).getLoaded();
        var cell=type.getConstructor().newInstance();var objects=(Set<Object>)type.getMethod("objects").invoke(cell);
        assertInstanceOf(NpcMembershipSet.class,objects);
        var tracked=(NpcMembershipSet<Object>)objects;Object a=new Object(),b=new Object(),c=new Object();
        assertTrue(objects.add(a));Object first=tracked.member(a);assertNotNull(first);
        assertFalse(objects.add(a));assertSame(first,tracked.member(a));
        objects.addAll(List.of(b,c));objects.removeIf(o->o==a);assertNull(tracked.member(a));
        objects.add(a);assertNotSame(first,tracked.member(a));
        var iterator=objects.iterator();Object removed=iterator.next();iterator.remove();assertNull(tracked.member(removed));
        assertThrows(IllegalStateException.class,iterator::remove);
        objects.retainAll(Set.of(a));assertNull(tracked.member(b));assertNull(tracked.member(c));
        objects.clear();assertTrue(objects.isEmpty());assertNull(tracked.member(a));
        objects.add(a);assertNotSame(first,tracked.member(a));
    }
    public static class NativeUnload {
        private final int wx;
        private final int wy;
        private final Runnable work;

        public NativeUnload(int wx, int wy, Runnable work) {
            this.wx = wx;
            this.wy = wy;
            this.work = work;
        }

        public void Unload() { work.run(); }
    }

    @Test void furnitureHooksRunInAnIsolatedGameFixture() throws Exception {
        var log = java.nio.file.Path.of("target/npc-furniture-fixture.log");
        var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Xverify:all", "-cp", System.getProperty("java.class.path"), NpcFactoryFixtureMain.class.getName(), "furniture")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Furniture fixture exceeded its timeout");
            assertEquals(0, process.exitValue(), java.nio.file.Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static void main(String[] args) throws Exception {
        var fixture = new NpcAdaptersTest();
        fixture.transformedFurnitureMutationsInvalidateIssuedBindingsOnly();
        fixture.transformedCellUnloadRejectsStaleTokensBeforeNativeWorkAndRestoresScopeOnFailure();
    }

    void transformedFurnitureMutationsInvalidateIssuedBindingsOnly() throws Exception {
        Class<?> type = transform("zombie.iso.IsoObject", Furniture.class);
        var object = type.getConstructor().newInstance();
        var equalLooking = type.getConstructor().newInstance();
        var square = new Object();
        try (var fixture = new FurnitureFixture()) {
            type.getMethod("removeFromWorld").invoke(object);
            assertTrue(fixture.bindings.isEmpty());
            type.getMethod("setSquare", Object.class).invoke(object, square);
            var token = fixture.bindings.bind(object, "chair", 0, 1, 1, 0, "S");
            var other = fixture.bindings.bind(equalLooking, "chair", 0, 2, 1, 0, "S");
            type.getMethod("setSquare", Object.class).invoke(object, square);
            assertSame(object, fixture.bindings.resolve(token), "The same square is a no-op");
            type.getMethod("setSquare", Object.class).invoke(object, new Object());
            assertNull(fixture.bindings.resolve(token));

            for (String method : List.of("removeFromSquare", "addToWorld", "removeFromWorld", "reset")) {
                var current = fixture.bindings.bind(object, "chair", 0, 1, 1, 0, "S");
                type.getMethod(method).invoke(object);
                assertNull(fixture.bindings.resolve(current), method);
                assertSame(equalLooking, fixture.bindings.resolve(other), method);
            }
        }
    }

    void transformedCellUnloadRejectsStaleTokensBeforeNativeWorkAndRestoresScopeOnFailure() throws Exception {
        Class<?> type = transform("zombie.network.ServerMap$ServerCell", NativeUnload.class);
        var constructor = type.getConstructor(int.class, int.class, Runnable.class);
        var unload = type.getMethod("Unload");
        var chair = new Object();
        var neighbor = new Object();
        try (var fixture = new FurnitureFixture()) {
            var old = fixture.bindings.bind(chair, "chair", 0, 63, 1, 0, "S");
            var adjacent = fixture.bindings.bind(neighbor, "chair", 1L << 32, 64, 1, 0, "S");
            var problem = new IllegalStateException("native unload failed");
            var cell = constructor.newInstance(0, 0, (Runnable) () -> {
                assertNull(fixture.bindings.resolve(old));
                assertSame(neighbor, fixture.bindings.resolve(adjacent));
                throw problem;
            });
            assertSame(problem, assertThrows(InvocationTargetException.class, () -> unload.invoke(cell)).getCause());
            assertTrue(fixture.scopeClosed(), "Exceptional native unload must close its retirement scope");
            assertSame(neighbor, fixture.bindings.resolve(adjacent));

            // Simulate rebind at the same coordinates with the exact same pooled object.
            var rebound = fixture.bindings.bind(chair, "chair", 0, 63, 1, 0, "S");
            assertNotEquals(old.generation(), rebound.generation());
            assertNull(fixture.bindings.resolve(old));
            assertSame(chair, fixture.bindings.resolve(rebound));

            var empty = constructor.newInstance(4, 4, (Runnable) () -> assertSame(chair, fixture.bindings.resolve(rebound)));
            unload.invoke(empty);
            assertTrue(fixture.scopeClosed());
            assertSame(chair, fixture.bindings.resolve(rebound));
        }
    }

    @Test void rebindingAndCapacityEvictionRemoveTheOldAreaIndex() {
        var bindings = new FurnitureBindings<Object>(1);
        Object chair = new String("chair"), equalChair = new String("chair");
        var old = bindings.bind(chair, "seat", 0, 1, 1, 0, "S");
        assertSame(old, bindings.bind(chair, "seat", 0, 1, 1, 0, "S"), "Rediscovery reuses the issued token");
        var moved = bindings.bind(chair, "seat", 1, 1, 65, 0, "S");
        assertEquals(0, bindings.removeArea(0));
        assertNull(bindings.resolve(old));
        assertSame(chair, bindings.resolve(moved));
        var replacement = bindings.bind(equalChair, "seat", 2, 1, 129, 0, "S");
        assertEquals(0, bindings.removeArea(1));
        assertNull(bindings.resolve(moved));
        assertSame(equalChair, bindings.resolve(replacement));
        assertEquals(1, bindings.removeArea(2));
        assertTrue(bindings.isEmpty());
    }

    private static Class<?> transform(String nativeName, Class<?> fixture) {
        return NpcModule.actorTransform(nativeName, new ByteBuddy().redefine(fixture))
            .make().load(fixture.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST).getLoaded();
    }

    private static final class FurnitureFixture implements AutoCloseable {
        private final ServerHumanFurnitureWorld world = new ServerHumanFurnitureWorld();
        private final java.lang.reflect.Field instance;
        private final Object previous;
        final FurnitureBindings<Object> bindings;

        @SuppressWarnings("unchecked")
        FurnitureFixture() throws ReflectiveOperationException {
            var bindingsField = ServerHumanFurnitureWorld.class.getDeclaredField("bindings");
            bindingsField.setAccessible(true);
            bindings = (FurnitureBindings<Object>) bindingsField.get(world);
            var constructor = ServerHumanFactory.class.getDeclaredConstructor(NpcDecisionPlanner.class);
            constructor.setAccessible(true);
            var factory = constructor.newInstance(new NpcDecisionPlanner(false));
            var owner = ServerHumanFactory.class.getDeclaredField("owner");
            owner.setAccessible(true);
            owner.set(factory, Thread.currentThread());
            var furniture = ServerHumanFactory.class.getDeclaredField("furniture");
            furniture.setAccessible(true);
            furniture.set(factory, new ServerHumanFurniture(world));
            instance = ServerHumanFactory.class.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            previous = instance.get(null);
            instance.set(null, factory);
        }

        boolean scopeClosed() throws ReflectiveOperationException {
            var scope = ServerHumanFurnitureWorld.class.getDeclaredField("unloading");
            scope.setAccessible(true);
            return scope.get(world) == null;
        }

        @Override public void close() throws IllegalAccessException {
            instance.set(null, previous);
            world.clear();
        }
    }
}
