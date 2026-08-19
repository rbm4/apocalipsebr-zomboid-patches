// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.entity;

import zombie.characters.IsoPlayer;
import zombie.entity.util.ImmutableArray;
import zombie.network.GameClient;

public class UsingPlayerUpdateSystem extends EngineSystem {
    private static final long UPDATE_INTERVAL_MILLIS = 1000L;
    EntityBucket isoEntities;
    private long lastUpdateMillis;

    public UsingPlayerUpdateSystem(int updatePriority) {
        super(true, false, updatePriority);
    }

    @Override
    public void addedToEngine(Engine engine) {
        this.isoEntities = engine.getIsoObjectBucket();
    }

    @Override
    public void update() {
        if (!GameClient.client) {
            long now = System.currentTimeMillis();
            if (this.lastUpdateMillis != 0L && now - this.lastUpdateMillis < UPDATE_INTERVAL_MILLIS) {
                return;
            }

            this.lastUpdateMillis = now;
            ImmutableArray<GameEntity> entities = this.isoEntities.getEntities();

            for (int i = 0; i < entities.size(); i++) {
                GameEntity entity = entities.get(i);
                if (entity.isValidEngineEntity()) {
                    IsoPlayer usingPlayer = entity.getUsingPlayer();
                    if (usingPlayer != null) {
                        int distance = 10;
                        if (usingPlayer.getX() < entity.getX() - 10.0F
                            || usingPlayer.getX() > entity.getX() + 10.0F
                            || usingPlayer.getY() < entity.getY() - 10.0F
                            || usingPlayer.getY() > entity.getY() + 10.0F
                            || usingPlayer.getZ() != entity.getZ()
                            || usingPlayer.isDead()) {
                            entity.setUsingPlayer(null);
                        }
                    }
                }
            }
        }
    }
}
