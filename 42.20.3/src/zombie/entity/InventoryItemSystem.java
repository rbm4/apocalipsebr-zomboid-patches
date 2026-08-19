// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.entity;

import zombie.entity.util.ImmutableArray;
import zombie.inventory.InventoryItem;

public class InventoryItemSystem extends EngineSystem {
    private static final long UPDATE_INTERVAL_MILLIS = 1000L;
    EntityBucket itemEntities;
    private long lastUpdateMillis;

    public InventoryItemSystem(int updatePriority) {
        super(true, false, updatePriority);
    }

    @Override
    public void addedToEngine(Engine engine) {
        this.itemEntities = engine.getInventoryItemBucket();
    }

    @Override
    public void update() {
        long now = System.currentTimeMillis();
        if (this.lastUpdateMillis != 0L && now - this.lastUpdateMillis < UPDATE_INTERVAL_MILLIS) {
            return;
        }

        this.lastUpdateMillis = now;
        ImmutableArray<GameEntity> entities = this.itemEntities.getEntities();

        for (int i = 0; i < entities.size(); i++) {
            GameEntity entity = entities.get(i);
            InventoryItem item = (InventoryItem)entity;
            if (item.getEquipParent() == null || item.getEquipParent().isDead()) {
                GameEntityManager.UnregisterEntity(entity);
            }
        }
    }
}
