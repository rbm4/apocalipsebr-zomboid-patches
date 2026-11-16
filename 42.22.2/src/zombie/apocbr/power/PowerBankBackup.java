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
            && source.getISABackupOwner() != null;
    }

    public static boolean claim(IsoGenerator source, KahluaTable owner) {
        if (!enabled || !GameServer.server || source instanceof IsoPowerBank || zombie.network.ApocBRManagedGenerators.isTagged(source)) return false;
        for (String key : new String[]{"x", "y", "z"}) {
            if (!(owner.rawget(key) instanceof Double coordinate) || !Double.isFinite(coordinate)
                || coordinate != Math.rint(coordinate)) return false;
        }
        Object previous = source.getISABackupOwner();
        if (previous instanceof KahluaTable old) {
            for (String key : new String[]{"x", "y", "z"}) {
                if (!java.util.Objects.equals(old.rawget(key), owner.rawget(key))) return false;
            }
        }
        if (!(previous instanceof KahluaTable)) {
            source.setISABackupOwner(owner);
            stop(source);
            source.flagForHotSave();
            source.transmitModData();
        }
        return true;
    }

    public static void release(IsoGenerator source) {
        // Stop while the ownership guard still protects the source, then unlock it.
        stop(source);
        source.setISABackupOwner(null);
        source.resetISABackupClock();
        source.flagForHotSave();
        source.transmitModData();
    }

    public static void stop(IsoGenerator source) {
        if (isControlled(source)) source.applyISABackupState(source.getFuel(), source.getCondition(), false);
        else source.setActivated(false);
    }

    public static void request(IsoGenerator source) {
        Object owner = source.getISABackupOwner();
        if (source.getModData().rawget(OWNER) != owner) {
            source.getModData().rawset(OWNER, owner);
            source.transmitModData();
        }
        LuaHookManager.TriggerHook(HOOK, source, owner, GameTime.getInstance().getWorldAgeHours());
    }
}
