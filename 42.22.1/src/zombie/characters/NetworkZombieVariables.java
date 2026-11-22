// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.characters;

import zombie.util.flags.OrdinalShortFlag;
import zombie.util.flags.ShortFlags;

public class NetworkZombieVariables {
    public static ShortFlags getBooleanVariables(IsoZombie zombie) {
        return ShortFlags.toFlags(getBooleanVariablesShort(zombie));
    }

    public static short getBooleanVariablesShort(IsoZombie zombie) {
        short flags = 0;
        if (zombie.isFakeDead()) flags = (short)(flags | NetworkZombieVariables.Flag.IsFakeDead.flag());
        if (zombie.lunger) flags = (short)(flags | NetworkZombieVariables.Flag.IsLunger.flag());
        if (zombie.running) flags = (short)(flags | NetworkZombieVariables.Flag.IsRunning.flag());
        if (zombie.isCrawling()) flags = (short)(flags | NetworkZombieVariables.Flag.IsCrawling.flag());
        if (zombie.isSitAgainstWall()) flags = (short)(flags | NetworkZombieVariables.Flag.IsSitAgainstWall.flag());
        if (zombie.isReanimatedPlayer()) flags = (short)(flags | NetworkZombieVariables.Flag.IsReanimatedPlayer.flag());
        if (zombie.isOnFire()) flags = (short)(flags | NetworkZombieVariables.Flag.IsOnFire.flag());
        if (zombie.isUseless()) flags = (short)(flags | NetworkZombieVariables.Flag.IsUseless.flag());
        if (zombie.isOnFloor()) flags = (short)(flags | NetworkZombieVariables.Flag.IsOnFloor.flag());
        if (zombie.isReanimatedForGrappleOnly()) flags = (short)(flags | NetworkZombieVariables.Flag.IsReanimatedForGrappleOnly.flag());
        if (zombie.isCanWalk()) flags = (short)(flags | NetworkZombieVariables.Flag.IsCanWalk.flag());
        if (zombie.isSkeleton()) flags = (short)(flags | NetworkZombieVariables.Flag.IsSkeleton.flag());
        if (zombie.isFallOnFront()) flags = (short)(flags | NetworkZombieVariables.Flag.IsFallOnFront.flag());
        if (zombie.isAnimationRecorderActive()) flags = (short)(flags | NetworkZombieVariables.Flag.IsAnimationRecording.flag());
        return flags;
    }

    public static void setBooleanVariables(IsoZombie zombie, short val) {
        setBooleanVariables(zombie, ShortFlags.toFlags(val));
    }

    public static void setBooleanVariables(IsoZombie zombie, ShortFlags val) {
        zombie.setFakeDead(val.has(NetworkZombieVariables.Flag.IsFakeDead));
        zombie.lunger = val.has(NetworkZombieVariables.Flag.IsLunger);
        zombie.running = val.has(NetworkZombieVariables.Flag.IsRunning);
        zombie.setCrawler(val.has(NetworkZombieVariables.Flag.IsCrawling));
        zombie.setSitAgainstWall(val.has(NetworkZombieVariables.Flag.IsSitAgainstWall));
        zombie.setReanimatedPlayer(val.has(NetworkZombieVariables.Flag.IsReanimatedPlayer));
        if (val.has(NetworkZombieVariables.Flag.IsOnFire)) {
            zombie.SetOnFire();
        } else {
            zombie.StopBurning();
        }

        zombie.setUseless(val.has(NetworkZombieVariables.Flag.IsUseless));
        if (zombie.isReanimatedPlayer()) {
            zombie.setOnFloor(val.has(NetworkZombieVariables.Flag.IsOnFloor));
        }

        zombie.setReanimatedForGrappleOnly(val.has(NetworkZombieVariables.Flag.IsReanimatedForGrappleOnly));
        zombie.setCanWalk(val.has(NetworkZombieVariables.Flag.IsCanWalk));
        zombie.setSkeleton(val.has(NetworkZombieVariables.Flag.IsSkeleton));
        zombie.setFallOnFront(val.has(NetworkZombieVariables.Flag.IsFallOnFront));
        zombie.setAnimRecorderActive(val.has(NetworkZombieVariables.Flag.IsAnimationRecording), true);
    }

    public static enum Flag implements OrdinalShortFlag {
        IsFakeDead,
        IsLunger,
        IsRunning,
        IsCrawling,
        IsSitAgainstWall,
        IsReanimatedPlayer,
        IsOnFire,
        IsUseless,
        IsOnFloor,
        IsReanimatedForGrappleOnly,
        IsCanWalk,
        IsSkeleton,
        IsFallOnFront,
        IsAnimationRecording;
    }
}
