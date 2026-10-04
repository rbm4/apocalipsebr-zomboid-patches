// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.entity;

import zombie.characters.IsoPlayer;
import zombie.entity.util.ImmutableArray;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.ApocBRServerTelemetryLite;

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
            if (GameServer.server) {
                ServerUsingPlayerIndex index = this.isoEntities.getUsingPlayerIndex();
                long visited = 0L, fallbackVisited = 0L;
                ApocBRServerTelemetryLite.count("entities.usingPlayer.bucketEntriesAtStart", this.isoEntities.getEntities().size());
                int position = -1;
                ServerUsingPlayerIndex.Member member;
                try {
                    while ((member = index.nextAfter(position)) != null) {
                        position = member.position;
                        visited++;
                        if (member.alwaysCheck) fallbackVisited++;
                        this.updateUsingPlayer(member.entity);
                    }
                } finally {
                    ApocBRServerTelemetryLite.count("entities.usingPlayer.candidatesVisited", visited);
                    ApocBRServerTelemetryLite.count("entities.usingPlayer.fallbackCandidatesVisited", fallbackVisited);
                }
            } else {
                ImmutableArray<GameEntity> entities = this.isoEntities.getEntities();
                for (int i = 0; i < entities.size(); i++) this.updateUsingPlayer(entities.get(i));
            }
        }
    }

    private void updateUsingPlayer(GameEntity entity) {
        if (entity.isValidEngineEntity()) {
            IsoPlayer usingPlayer = entity.getUsingPlayer();
            if (entity.getUsingPlayer() != null) {
                int distance = 10;
                if (usingPlayer.getX() < entity.getX() - 10.0F
                    || usingPlayer.getX() > entity.getX() + 10.0F
                    || usingPlayer.getY() < entity.getY() - 10.0F
                    || usingPlayer.getY() > entity.getY() + 10.0F
                    || usingPlayer.getZ() != entity.getZ()
                    || usingPlayer.isDead()) {
                    entity.setUsingPlayer(null);
                    if (GameServer.server) ApocBRServerTelemetryLite.count("entities.usingPlayer.cleared", 1);
                }
            }
        }
    }
}
