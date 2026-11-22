// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.util.ArrayList;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.core.logger.LoggerManager;
import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.packets.character.PlayerDropHeldItemsPacket;

public final class ThrownItemManager {
    private static final ThrownItemManager instance = new ThrownItemManager();
    private static final long MIN_THROW_INTERVAL_MS = 500L;
    private static final long RECORD_LIFETIME_MS = 30000L;
    private final List<ThrownItemManager.ThrownItem> thrownItems = new ArrayList<>();

    private ThrownItemManager() {
    }

    public static ThrownItemManager getInstance() {
        return instance;
    }

    public void throwHeldItem(IsoPlayer thrower, int targetX, int targetY, int targetZ) {
        IsoGridSquare origin = thrower.getCurrentSquare();
        if (origin != null && thrower.getPrimaryHandItem() instanceof HandWeapon weapon && weapon.getPhysicsObject() != null) {
            long throwTimeMs = System.currentTimeMillis();
            this.forgetExpiredThrows(throwTimeMs);
            if (!this.isThrowAllowed(thrower, throwTimeMs)) {
                thrower.dropHeldItemsServer(origin.getX(), origin.getY(), origin.getZ(), PlayerDropHeldItemsPacket.Flags.dropPrimary.getFlags());
            } else {
                thrower.dropHeldItemsServer(origin.getX(), origin.getY(), origin.getZ(), PlayerDropHeldItemsPacket.Flags.throwPrimaryItem.getFlags());
                if (thrower.getPrimaryHandItem() != weapon) {
                    this.thrownItems
                        .add(
                            new ThrownItemManager.ThrownItem(
                                thrower, weapon, origin.getX(), origin.getY(), origin.getZ(), targetX, targetY, targetZ, throwTimeMs
                            )
                        );
                    LoggerManager.getLogger("map")
                        .write(String.format("%s threw %s at %d,%d,%d", thrower.getUsername(), weapon.getFullType(), targetX, targetY, targetZ));
                    IsoGridSquare target = IsoWorld.instance.currentCell.getGridSquare(targetX, targetY, targetZ);
                    thrower.setAttackTargetSquare(target);
                    weapon.setAttackTargetSquare(target);
                    thrower.Throw(weapon);
                }
            }
        }
    }

    private boolean isThrowAllowed(IsoPlayer thrower, long throwTimeMs) {
        for (int i = 0; i < this.thrownItems.size(); i++) {
            ThrownItemManager.ThrownItem thrownItem = this.thrownItems.get(i);
            if (thrownItem.thrower == thrower && throwTimeMs - thrownItem.throwTimeMs < 500L) {
                DebugType.Multiplayer.warn("ThrownItemManager: %s throws too fast", thrower.getUsername());
                return false;
            }
        }

        return true;
    }

    private void forgetExpiredThrows(long throwTimeMs) {
        for (int i = this.thrownItems.size() - 1; i >= 0; i--) {
            if (throwTimeMs - this.thrownItems.get(i).throwTimeMs > 30000L) {
                this.thrownItems.remove(i);
            }
        }
    }

    private record ThrownItem(
        IsoPlayer thrower, InventoryItem item, int originX, int originY, int originZ, int targetX, int targetY, int targetZ, long throwTimeMs
    ) {
    }
}
