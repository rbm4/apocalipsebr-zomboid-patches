package cloud.apocalipse.pz.agent.features.lua.api;

import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiDispatcherTest {
    static final URI URI=java.net.URI.create("https://example.invalid/balance");
    @Test void boundsOutstandingWorkAndStopsActiveTransport() throws Exception {
        AtomicLong clock=new AtomicLong();CountDownLatch entered=new CountDownLatch(1),stop=new CountDownLatch(1);
        AtomicBoolean closed=new AtomicBoolean();
        var transport=new ApiDispatcher.Transport(){
            public ApiDispatcher.Reply send(java.net.URI uri)throws InterruptedException{entered.countDown();stop.await();return new ApiDispatcher.Reply(200,"{}",null,0);}
            public void close(){closed.set(true);}
        };
        var dispatcher=new ApiDispatcher(transport,1500,clock::get);
        try {
            dispatcher.start();assertNull(dispatcher.submit("first",URI));assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals("THROTTLED",dispatcher.submit("early",URI));
            for(int i=1;i<32;i++){clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1500));assertNull(dispatcher.submit("queued"+i,URI));}
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1500));assertEquals("QUEUE_FULL",dispatcher.submit("overflow",URI));
        }finally{dispatcher.close();}
        assertTrue(closed.get());assertFalse(dispatcher.isRunning());assertNull(dispatcher.poll());
        assertEquals("STOPPED",dispatcher.submit("after",URI));
    }
    @Test void returnsDetachedResultsAndAppliesBackendCooldown() throws Exception {
        AtomicLong clock=new AtomicLong();
        var dispatcher=new ApiDispatcher(new ApiDispatcher.Transport(){
            public ApiDispatcher.Reply send(java.net.URI uri){return new ApiDispatcher.Reply(429,null,"HTTP_429",60000);}
            public void close(){}
        },1500,clock::get);
        try {
            dispatcher.start();assertNull(dispatcher.submit("fixture",URI));
            ApiDispatcher.Result result=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(result==null&&System.nanoTime()<deadline){result=dispatcher.poll();Thread.sleep(1);}
            assertNotNull(result);assertEquals("fixture",result.id());assertEquals("HTTP_429",result.error());assertNull(result.body());
            clock.set(TimeUnit.SECONDS.toNanos(2));assertEquals("BACKEND_COOLDOWN",dispatcher.submit("next",URI));
            assertEquals("INVALID_METHOD",dispatcher.submit("method",URI,"DELETE"));
            assertEquals("INVALID_BODY",dispatcher.submit("body",URI,"GET","payload"));
        }finally{dispatcher.close();}
    }
    @Test void endpointValidatesIdentityAndMutationContract() {
        String identity="{\"runtimeUuid\":\"12345678-1234-1234-1234-123456789abc\",\"steamId\":\"76561198000000000\",\"sourceServerId\":1}";
        assertEquals("GET",ApocBrApiBridge.CHARACTER_BALANCE.method());
        assertTrue(ApocBrApiBridge.CHARACTER_BALANCE.resolve(identity).getPath().endsWith("/balance"));
        assertThrows(RuntimeException.class,()->ApocBrApiBridge.CHARACTER_BALANCE.resolve(identity+"garbage"));
        assertThrows(RuntimeException.class,()->ApocBrApiBridge.CHARACTER_DEBIT.resolve(identity));
        String debit=identity.substring(0,identity.length()-1)+",\"amount\":10,\"reason\":\"market\",\"idempotencyKey\":\"fixture-1\"}";
        assertEquals("POST",ApocBrApiBridge.CHARACTER_DEBIT.method());
        assertTrue(ApocBrApiBridge.CHARACTER_DEBIT.resolve(debit).getPath().endsWith("/debit"));
        assertEquals(10,new org.json.JSONObject(ApocBrApiBridge.CHARACTER_DEBIT.body(debit)).getInt("amount"));
        assertThrows(RuntimeException.class,()->ApocBrApiBridge.CHARACTER_DEBIT.resolve(debit.replace("10,","0,")));
        assertEquals(12.0,ApocBrApiBridge.CHARACTER_BALANCE.decode("{\"balance\":12}").get("balance"));
        assertThrows(RuntimeException.class,()->ApocBrApiBridge.CHARACTER_BALANCE.decode("{\"balance\":1e100}"));
    }
}
