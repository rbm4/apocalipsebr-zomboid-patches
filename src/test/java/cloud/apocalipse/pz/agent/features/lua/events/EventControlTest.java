package cloud.apocalipse.pz.agent.features.lua.events;

import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventControlTest {
    @SuppressWarnings("unchecked")
    static void publish(ApocBREventControl control,ApocBREventControl.Observed snapshot)throws Exception {
        var field=ApocBREventControl.class.getDeclaredField("incoming");field.setAccessible(true);
        ((AtomicReference<ApocBREventControl.Observed>)field.get(control)).set(snapshot);control.acceptIncoming();
    }
    @Test void appliesOnlyFreshMonotonicSchedulesAndAcknowledgesExactIdentity() throws Exception {
        AtomicLong wall=new AtomicLong(10000),nano=new AtomicLong(1000000);
        var control=new ApocBREventControl("fixture","world",1000,3000,ZoneId.of("UTC"),()->{throw new AssertionError("Unexpected JDBC");},wall::get,nano::get);
        String id=UUID.randomUUID().toString();
        var first=new ApocBREventControl.Schedule(id,2,"fixture",11000,"scheduled");
        var guide=new ApocBREventControl.Guide(null,0,null,0,0);
        publish(control,new ApocBREventControl.Observed(first,null,nano.get(),guide));
        assertTrue(control.fresh());assertFalse(control.due(id,2));assertFalse(control.acknowledge(id,2));
        wall.set(11000);assertTrue(control.due(id,2));assertFalse(control.acknowledge(id,1));assertTrue(control.acknowledge(id,2));
        assertTrue(control.acknowledge(id,2));
        var replacement=new ApocBREventControl.Schedule(id,2,"changed",11000,"scheduled");
        publish(control,new ApocBREventControl.Observed(replacement,null,nano.get(),guide));assertFalse(control.fresh());
        publish(control,new ApocBREventControl.Observed(first,null,nano.get(),guide));assertTrue(control.fresh());
        nano.addAndGet(3_000_000_001L);assertFalse(control.fresh());assertFalse(control.authorized(id,2));
    }
    @Test void validatesDetachedMetadataBeforePublication() {
        String id=UUID.randomUUID().toString();
        assertThrows(RuntimeException.class,()->new ApocBREventControl.Schedule(id,1,"name",100,"scheduled","[]"));
        assertThrows(RuntimeException.class,()->new ApocBREventControl.Schedule(id,1,"name",100,"idle"));
        assertThrows(RuntimeException.class,()->new ApocBREventControl.Schedule(id,0,"name",100,"scheduled"));
        assertDoesNotThrow(()->new ApocBREventControl.Schedule(null,0,null,0,"idle","{}"));
    }
}
