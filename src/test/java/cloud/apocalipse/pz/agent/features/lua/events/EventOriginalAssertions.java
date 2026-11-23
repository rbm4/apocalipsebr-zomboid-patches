package cloud.apocalipse.pz.agent.features.lua.events;

import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import se.krka.kahlua.vm.*;
import se.krka.kahlua.luaj.compiler.LuaCompiler;

/** Fake detached schedule, real registered Lua event, real server-loop helper. */
public final class EventOriginalAssertions {
    private static final String ID=UUID.randomUUID().toString();
    @SuppressWarnings("unchecked")
    public static void prepare()throws Exception {
        var control=new ApocBREventControl("fixture","world",1000,3000,ZoneId.of("UTC"),
            ()->{throw new AssertionError("Unexpected JDBC");},()->10000L,System::nanoTime);
        var incoming=ApocBREventControl.class.getDeclaredField("incoming");incoming.setAccessible(true);
        ((AtomicReference<ApocBREventControl.Observed>)incoming.get(control)).set(new ApocBREventControl.Observed(
            new ApocBREventControl.Schedule(ID,1,"fixture",9000,"scheduled"),null,System.nanoTime(),new ApocBREventControl.Guide(null,0,null,0,0)));
        var instance=ApocBREventControl.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,control);
        Class<?> lua=Class.forName("zombie.Lua.LuaManager");var env=(KahluaTable)lua.getField("env").get(null);
        var callback=LuaCompiler.loadstring("Events.OnApocBRLobbyEventDue.Add(function(id, revision, context) eventFixture = context end)","event-fixture",env);
        Object caller=lua.getField("caller").get(null);
        caller.getClass().getMethod("protectedCallVoid",KahluaThread.class,Object.class,Object[].class)
            .invoke(caller,lua.getField("thread").get(null),callback,new Object[0]);
    }
    public static void verify()throws Exception {
        var env=(KahluaTable)Class.forName("zombie.Lua.LuaManager").getField("env").get(null);
        Object value=env.rawget("eventFixture");
        if(!(value instanceof KahluaTable result)||!ID.equals(result.rawget("eventId"))||!Double.valueOf(1).equals(result.rawget("revision")))
            throw new AssertionError("Due schedule did not reach the registered Lua callback");
    }
}
