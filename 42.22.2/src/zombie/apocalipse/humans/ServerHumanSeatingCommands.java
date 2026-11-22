package zombie.apocalipse.humans;

import java.util.Set;
import se.krka.kahlua.vm.*;
import zombie.Lua.LuaManager;
import zombie.network.GameServer;

/** Trusted server-Lua commands only. No client command receiver or actor-specific behavior. */
final class ServerHumanSeatingCommands {
    private static KahluaTable environment;
    private ServerHumanSeatingCommands() { }
    static void install(){
        if(!GameServer.server||LuaManager.env==null||LuaManager.env==environment)return;
        var api=LuaManager.platform.newTable();
        api.rawset("sitAt",(JavaFunction)(frame,count)->{
            boolean accepted=false;
            if(count==6&&frame.get(0) instanceof String key&&frame.get(4) instanceof String side&&Set.of("Front","Left","Right").contains(side)){
                Integer x=integer(frame.get(1)),y=integer(frame.get(2)),z=integer(frame.get(3)),duration=integer(frame.get(5));
                if(x!=null&&y!=null&&z!=null&&duration!=null&&x>=0&&y>=0&&x<Integer.MAX_VALUE&&y<Integer.MAX_VALUE&&z>=-32&&z<=31&&duration>=1&&duration<=86_400_000)
                    accepted=ServerHumanFactory.getInstance().sitAt(key,x,y,z,side,duration);
            }
            frame.push(accepted);return 1;
        });
        api.rawset("stand",(JavaFunction)(frame,count)->{frame.push(count==1&&frame.get(0) instanceof String key&&ServerHumanFactory.getInstance().stand(key));return 1;});
        api.rawset("status",(JavaFunction)(frame,count)->{frame.push(count==1&&frame.get(0) instanceof String key?ServerHumanFactory.getInstance().seatingStatus(key):null);return 1;});
        api.rawset("version",1.0);LuaManager.env.rawset("ApocBRSeating",api);environment=LuaManager.env;
    }
    private static Integer integer(Object value){
        if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()!=number.intValue())return null;
        return number.intValue();
    }
}
