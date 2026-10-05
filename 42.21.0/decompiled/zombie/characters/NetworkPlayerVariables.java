// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.characters;

import zombie.GameTime;
import zombie.core.Core;
import zombie.iso.Vector2;
import zombie.util.flags.OrdinalShortFlag;
import zombie.util.flags.ShortFlags;

public class NetworkPlayerVariables {
    private static final Vector2 deferredMovement = new Vector2();
    private static final float MOVEMENT_FLAG_THRESHOLD = 0.01F;

    private static boolean getPlayerHasDeferredMovement(IsoPlayer isoPlayer) {
        return isoPlayer.getDeferredMovement(deferredMovement).getLength() > 0.01F * GameTime.instance.fpsMultiplier;
    }

    public static ShortFlags getBooleanVariables(IsoPlayer isoPlayer) {
        ShortFlags flags = ShortFlags.alloc();
        flags.set(NetworkPlayerVariables.Flags.isSneaking, isoPlayer.isSneaking());
        flags.set(NetworkPlayerVariables.Flags.isOnFire, isoPlayer.isOnFire());
        flags.set(NetworkPlayerVariables.Flags.isAsleep, isoPlayer.isAsleep());
        flags.set(NetworkPlayerVariables.Flags.isRunning, isoPlayer.isRunning());
        flags.set(NetworkPlayerVariables.Flags.isSprinting, isoPlayer.isSprinting());
        flags.set(NetworkPlayerVariables.Flags.isCharging, isoPlayer.isCharging);
        flags.set(NetworkPlayerVariables.Flags.isChargingLT, isoPlayer.isChargingLt);
        flags.set(NetworkPlayerVariables.Flags.isDoShove, isoPlayer.isDoShove());
        flags.set(NetworkPlayerVariables.Flags.isDoGrapple, isoPlayer.isDoGrapple());
        flags.set(NetworkPlayerVariables.Flags.isDoContinueGrapple, isoPlayer.isDoContinueGrapple());
        flags.set(NetworkPlayerVariables.Flags.hasDeferredMovement, getPlayerHasDeferredMovement(isoPlayer));
        flags.set(NetworkPlayerVariables.Flags.isOnFloor, isoPlayer.isOnFloor());
        flags.set(NetworkPlayerVariables.Flags.isCheatMode, isoPlayer.isGodMod());
        flags.set(NetworkPlayerVariables.Flags.isDebugMode, Core.debug);
        flags.set(NetworkPlayerVariables.Flags.isAutoDrinkEnabled, isoPlayer.getAutoDrink());
        flags.set(NetworkPlayerVariables.Flags.isPerformingAction, isoPlayer.isPerformingAnAction());
        return flags;
    }

    public static void setBooleanVariables(IsoPlayer isoPlayer, short val) {
        setBooleanVariables(isoPlayer, ShortFlags.toFlags(val));
    }

    public static void setBooleanVariables(IsoPlayer isoPlayer, ShortFlags val) {
        isoPlayer.setSneaking(val.has(NetworkPlayerVariables.Flags.isSneaking));
        if (val.has(NetworkPlayerVariables.Flags.isOnFire)) {
            isoPlayer.SetOnFire();
        } else {
            isoPlayer.StopBurning();
        }

        isoPlayer.setAsleep(val.has(NetworkPlayerVariables.Flags.isAsleep));
        isoPlayer.setRunning(val.has(NetworkPlayerVariables.Flags.isRunning));
        isoPlayer.setSprinting(val.has(NetworkPlayerVariables.Flags.isSprinting));
        isoPlayer.isCharging = val.has(NetworkPlayerVariables.Flags.isCharging);
        isoPlayer.isChargingLt = val.has(NetworkPlayerVariables.Flags.isChargingLT);
        isoPlayer.setDoShove(isoPlayer.isDoShove() || val.has(NetworkPlayerVariables.Flags.isDoShove));
        isoPlayer.setDoGrapple(val.has(NetworkPlayerVariables.Flags.isDoGrapple));
        isoPlayer.setDoContinueGrapple(val.has(NetworkPlayerVariables.Flags.isDoContinueGrapple));
        isoPlayer.getNetworkCharacterAI().moving = val.has(NetworkPlayerVariables.Flags.hasDeferredMovement);
        isoPlayer.setOnFloor(val.has(NetworkPlayerVariables.Flags.isOnFloor));
        isoPlayer.setAutoDrink(val.has(NetworkPlayerVariables.Flags.isAutoDrinkEnabled));
        isoPlayer.setPerformingAnAction(val.has(NetworkPlayerVariables.Flags.isPerformingAction));
    }

    public static enum Flags implements OrdinalShortFlag {
        isSneaking,
        isOnFire,
        isAsleep,
        isRunning,
        isSprinting,
        isCharging,
        isChargingLT,
        isDoShove,
        hasDeferredMovement,
        isOnFloor,
        isCheatMode,
        isDebugMode,
        isDoGrapple,
        isDoContinueGrapple,
        isAutoDrinkEnabled,
        isPerformingAction;

        private Flags() {
            if (this.ordinal() >= 16) {
                throw new IllegalStateException("Too many Flags to fit inside a single 16-bit short.");
            }
        }
    }
}
