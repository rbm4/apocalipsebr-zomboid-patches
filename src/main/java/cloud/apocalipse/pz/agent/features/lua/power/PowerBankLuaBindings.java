package cloud.apocalipse.pz.agent.features.lua.power;

import se.krka.kahlua.vm.*;
import zombie.Lua.LuaManager;
import zombie.iso.objects.IsoGenerator;

/** Lua-only compatibility for legacy added instance methods; the native JVM type stays unchanged. */
public final class PowerBankLuaBindings {
    private PowerBankLuaBindings() { }
    public static void install() {
        Object table=KahluaUtil.getClassMetatables(LuaManager.platform,LuaManager.env).rawget(IsoGenerator.class);
        if(!(table instanceof KahluaTable metatable) || !(metatable.rawget("__index") instanceof KahluaTable methods))
            throw new IllegalStateException("Native generator Lua method table is unavailable");
        for(Operation operation:Operation.values()) {
            Object existing=methods.rawget(operation.name());
            if(existing instanceof Binding binding && binding.operation==operation)continue;
            if(existing!=null)throw new IllegalStateException("Generator Lua extension conflicts with an existing method: "+operation.name());
            methods.rawset(operation.name(),new Binding(operation));
        }
    }
    private enum Operation {
        applyISABackupState(4),resetISABackupClock(1),getISABackupOwner(1),setISABackupOwner(2),isISABackupActivationAllowed(2);
        final int arguments;
        Operation(int arguments){this.arguments=arguments;}
    }
    private static final class Binding implements JavaFunction {
        final Operation operation;
        Binding(Operation operation){this.operation=operation;}
        @Override public int call(LuaCallFrame frame,int arguments) {
            if(arguments!=operation.arguments || !(frame.get(0) instanceof IsoGenerator source))
                throw new IllegalArgumentException("Invalid generator Lua extension arguments: "+operation.name());
            return switch(operation) {
                case applyISABackupState -> {
                    double condition=number(frame,2);
                    if(condition<Integer.MIN_VALUE || condition>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid backup condition");
                    PowerBankBackup.applyISABackupState(source,number(frame,1),(int)condition,bool(frame,3));yield 0;
                }
                case resetISABackupClock -> {PowerBankBackup.resetISABackupClock(source);yield 0;}
                case getISABackupOwner -> frame.push(PowerBankBackup.getISABackupOwner(source));
                case setISABackupOwner -> {
                    Object owner=frame.get(1);
                    if(owner!=null && !(owner instanceof KahluaTable))throw new IllegalArgumentException("Invalid backup owner");
                    PowerBankBackup.setISABackupOwner(source,(KahluaTable)owner);yield 0;
                }
                case isISABackupActivationAllowed -> frame.push(PowerBankBackup.isISABackupActivationAllowed(source,bool(frame,1)));
            };
        }
        private static double number(LuaCallFrame frame,int index) {
            if(!(frame.get(index) instanceof Double value)||!Double.isFinite(value))throw new IllegalArgumentException("Finite backup value required");
            return value;
        }
        private static boolean bool(LuaCallFrame frame,int index) {
            if(!(frame.get(index) instanceof Boolean value))throw new IllegalArgumentException("Boolean backup value required");
            return value;
        }
    }
}
