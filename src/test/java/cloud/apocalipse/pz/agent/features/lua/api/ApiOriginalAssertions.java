package cloud.apocalipse.pz.agent.features.lua.api;

import java.util.concurrent.TimeUnit;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.luaj.compiler.LuaCompiler;

/** Runs only in the original-first premain smoke JVM, with fake HTTP and real Kahlua callbacks. */
public final class ApiOriginalAssertions {
    public static void run(Runnable pump) throws Exception {
        Class<?> lua=Class.forName("zombie.Lua.LuaManager");KahluaTable env=(KahluaTable)lua.getField("env").get(null);
        var dispatcher=new ApiDispatcher(new ApiDispatcher.Transport(){
            public ApiDispatcher.Reply send(java.net.URI uri){return new ApiDispatcher.Reply(200,"{\"balance\":42}",null,0);}
            public void close(){}
        },1500,System::nanoTime);
        var bridge=new ApiBridgeLifecycle(dispatcher);
        var field=ApiBridgeLifecycle.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,bridge);
        dispatcher.start();
        try {
            var callback=LuaCompiler.loadstring("local result = ...; apiFixture = result", "api-fixture",env);
            String input="{\"runtimeUuid\":\"12345678-1234-1234-1234-123456789abc\",\"steamId\":\"76561198000000000\",\"sourceServerId\":1}";
            String id=ApocBrApiBridge.request(ApocBrApiBridge.CHARACTER_BALANCE,input,callback);
            if(id==null)throw new AssertionError("API request rejected");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            var results=ApiDispatcher.class.getDeclaredField("results");results.setAccessible(true);
            var queue=(java.util.Queue<?>)results.get(dispatcher);
            while(queue.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(1);
            if(queue.isEmpty())throw new AssertionError("HTTP fixture did not finish");
            pump.run();
            KahluaTable result=(KahluaTable)env.rawget("apiFixture");
            if(result==null || !Boolean.TRUE.equals(result.rawget("ok")) || !Double.valueOf(42).equals(result.rawget("balance")))
                throw new AssertionError("Server-loop API callback did not return the decoded balance");
            if(!id.equals(result.rawget("requestId")))throw new AssertionError("Wrong callback identity");
        }finally{ApiBridgeLifecycle.shutdown();}
        if(ApocBrApiBridge.isEnabled())throw new AssertionError("API remained running after shutdown");
    }
}
