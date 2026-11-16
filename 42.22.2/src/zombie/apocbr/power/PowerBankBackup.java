package zombie.apocbr.power;

import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.Lua.LuaHookManager;
import zombie.UsedFromLua;
import zombie.iso.objects.IsoGenerator;
import zombie.network.GameServer;

/** Exclusive fuel-accounting ownership; ordinary generators remain native. */
@UsedFromLua
public final class PowerBankBackup {
    public static final String HOOK = "OnPowerBankBackupUpdate";
    public static final String OWNER = "ApocBRISABackupOwner";
    private static boolean enabled;
    private PowerBankBackup() {}

    public static void enable() { enabled = GameServer.server; }

    public static boolean isControlled(IsoGenerator source) {
        return enabled && GameServer.server && !(source instanceof IsoPowerBank)
            && !zombie.network.ApocBRManagedGenerators.isTagged(source)
            && source.getModData().rawget(OWNER) instanceof KahluaTable;
    }

    public static boolean claim(IsoGenerator source, KahluaTable owner) {
        if (!enabled || !GameServer.server || source instanceof IsoPowerBank || zombie.network.ApocBRManagedGenerators.isTagged(source)) return false;
        for (String key : new String[]{"x", "y", "z"}) {
            if (!(owner.rawget(key) instanceof Double coordinate) || !Double.isFinite(coordinate)
                || coordinate != Math.rint(coordinate)) return false;
        }
        Object previous = source.getModData().rawget(OWNER);
        if (previous instanceof KahluaTable old) {
            for (String key : new String[]{"x", "y", "z"}) {
                if (!java.util.Objects.equals(old.rawget(key), owner.rawget(key))) return false;
            }
        }
        source.getModData().rawset(OWNER, owner);
        source.flagForHotSave();
        return true;
    }

    public static void release(IsoGenerator source) {
        source.getModData().rawset(OWNER, null);
        source.resetISABackupClock();
        source.flagForHotSave();
    }

    public static void request(IsoGenerator source) {
        Object owner = source.getModData().rawget(OWNER);
        LuaHookManager.TriggerHook(HOOK, source, owner, GameTime.getInstance().getWorldAgeHours());
    }
}
