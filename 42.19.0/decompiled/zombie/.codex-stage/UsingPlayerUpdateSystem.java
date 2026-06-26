// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.entity;

import zombie.characters.IsoPlayer;
import zombie.entity.util.ImmutableArray;
import zombie.network.GameClient;

public class UsingPlayerUpdateSystem extends EngineSystem {
    EntityBucket isoEntities;

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
            ImmutableArray<GameEntity> entities = this.isoEntities.getEntities();
            // ApocBR: async chunk/cell retirement can mutate the entity bucket while
            // this engine system is scanning it. Snapshot before iterating and skip
            // transient null entries so we do not process or propagate bad entity state.
            GameEntity[] snapshot = entities.toArray(GameEntity.class);

            for (int i = 0; i < snapshot.length; i++) {
                GameEntity entity = snapshot[i];
                if (entity == null) {
                    continue;
                }

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
