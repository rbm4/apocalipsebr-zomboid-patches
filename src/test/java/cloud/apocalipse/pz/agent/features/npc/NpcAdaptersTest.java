package cloud.apocalipse.pz.agent.features.npc;

import java.util.*;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcAdaptersTest {
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
    @Test void transformedFurnitureInvalidatesOnlyTheOriginalLifecycleConditions()throws Exception {
        var type=NpcModule.actorTransform("zombie.iso.IsoObject",new ByteBuddy().redefine(Furniture.class))
            .make().load(getClass().getClassLoader(),ClassLoadingStrategy.Default.CHILD_FIRST).getLoaded();
        var object=type.getConstructor().newInstance();Object square=new Object();
        type.getMethod("removeFromSquare").invoke(object);assertEquals(0,NpcObjectLifetime.revision(object));
        type.getMethod("setSquare",Object.class).invoke(object,square);assertEquals(1,NpcObjectLifetime.revision(object));
        type.getMethod("setSquare",Object.class).invoke(object,square);assertEquals(1,NpcObjectLifetime.revision(object));
        type.getMethod("removeFromSquare").invoke(object);assertEquals(2,NpcObjectLifetime.revision(object));
        type.getMethod("setSquare",Object.class).invoke(object,square);assertEquals(3,NpcObjectLifetime.revision(object));
        for(String method:List.of("addToWorld","removeFromWorld","reset"))type.getMethod(method).invoke(object);
        assertEquals(6,NpcObjectLifetime.revision(object));
    }
    @Test void lifetimeKeysUseIdentityEvenForEqualObjects(){
        Object first=new String("same"),second=new String("same");
        NpcObjectLifetime.changed(first);NpcObjectLifetime.changed(first);NpcObjectLifetime.changed(second);
        assertEquals(2,NpcObjectLifetime.revision(first));assertEquals(1,NpcObjectLifetime.revision(second));
    }
}
