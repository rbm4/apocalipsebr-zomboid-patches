package cloud.apocalipse.pz.agent.features.npc;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class NpcGameplayAdvice {
    public static class WorldReset {
        @Advice.OnMethodEnter public static void enter(){NpcRuntime.worldReset();}
    }
    public static class Lifetime {
        @Advice.OnMethodEnter public static void enter(@Advice.This Object object){NpcObjectLifetime.changed(object);}
    }
    public static class SetSquare {
        @Advice.OnMethodEnter public static void enter(@Advice.This Object object,@Advice.FieldValue("square") Object old,@Advice.Argument(0) Object next){
            if(old!=next)NpcObjectLifetime.changed(object);
        }
    }
    public static class RemoveSquare {
        @Advice.OnMethodEnter public static void enter(@Advice.This Object object,@Advice.FieldValue("square") Object square){
            if(square!=null)NpcObjectLifetime.changed(object);
        }
    }
    public static class Slot {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class) public static boolean enter(){return true;}
        @Advice.OnMethodExit public static void exit(@Advice.Return(readOnly=false) short result){result=NpcGameplayHooks.freeSlot();}
    }
    public static class DataRequest {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object packet,@Advice.Argument(1) Object connection){return NpcGameplayHooks.dataRequest(packet,connection);}
    }
    public static class TradeParse {
        @Advice.OnMethodExit public static void exit(@Advice.This Object packet,@Advice.FieldValue("destination") Object destination){
            NpcGameplayHooks.resolveTrade(packet,destination);
        }
    }
    public static class Trade {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object packet,@Advice.Argument(1) Object connection){return NpcGameplayHooks.trade(packet,connection);}
    }
    public static class WalkOnly {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object target,@Advice.Argument(0) Object request){return NpcGameplayHooks.walkOnly(target,request);}
        @Advice.OnMethodExit public static void exit(@Advice.This Object target,@Advice.Enter boolean handled,@Advice.Return(readOnly=false,typing=Assigner.Typing.DYNAMIC) Object result){if(handled)result=target;}
    }
}
