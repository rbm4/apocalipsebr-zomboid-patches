package zombie.apocalipse.humans;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import zombie.apocalipse.humans.brain.GuideBrain;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoCell;

/** A server-owned presentation actor, deliberately outside live-player simulation. */
final class ServerHuman {
    final ServerHumanDefinition definition;
    final IsoPlayer player;
    final Set<UdpConnection> viewers = Collections.newSetFromMap(new IdentityHashMap<>());
    final GuideBrain brain = new GuideBrain();
    final ServerHumanActuator actuator;
    final ServerHumanRoaming roaming;
    boolean moving;
    float postX, postY;
    String emote;

    ServerHuman(ServerHumanDefinition definition, short onlineId, IsoCell cell) {
        this.definition = definition;
        SurvivorDesc descriptor = new SurvivorDesc(false);
        descriptor.setFemale(definition.female());
        descriptor.setForename(definition.displayName());
        descriptor.setSurname("");
        descriptor.getHumanVisual().setSkinTextureIndex(0);
        descriptor.getHumanVisual().setHairModel(definition.female() ? "Bob" : "Short");
        descriptor.getHumanVisual().setBeardModel("");
        descriptor.dressInNamedOutfit(definition.outfit());

        // Zero coordinates avoid IsoGameCharacter's automatic addMovingObject path.
        this.player = new IsoPlayer(cell, descriptor, 0, 0, 0);
        // Also detach additions made by constructor callbacks. No server chunks are pinned.
        cell.getAddList().remove(this.player);
        cell.getObjectList().remove(this.player);
        this.player.removeFromSquare();
        this.player.remote = true;
        this.player.playerIndex = -1;
        this.player.setOnlineID(onlineId);
        // The factory reserves names for the process lifetime to protect chat caches.
        this.player.setUsername(definition.username());
        this.player.setDisplayName(definition.displayName());
        this.player.setGodMod(true, true);
        this.player.setInvisible(false, true);
        this.player.setGhostMode(false, true);
        this.player.setX(definition.x());
        this.player.setY(definition.y());
        this.player.setZ(definition.z());
        this.player.realx = definition.x();
        this.player.realy = definition.y();
        this.player.realz = (byte)definition.z();
        this.player.setForwardIsoDirection(definition.facing());
        this.player.setVariable("IdleSpeed", 1.0F);
        this.postX = definition.x();
        this.postY = definition.y();
        this.roaming = new ServerHumanRoaming(this);
        this.actuator = new ServerHumanActuator(this);
    }

    boolean isRelevant(UdpConnection connection) {
        return connection.isFullyConnected() && connection.isRelevantTo(player.getX(), player.getY());
    }

    void dispose() {
        ServerHumanTrading.close(this);
        brain.stop(System.nanoTime(), actuator);
        roaming.stop();
        // Never call normal disconnectPlayer(): this actor has no account/connection/save.
        for (UdpConnection connection : viewers) {
            if (connection.isFullyConnected()) ServerHumanTransport.remove(this, connection);
        }
        viewers.clear();
        player.getDescriptor().setInstance(null);
        player.getCell().getAddList().remove(player);
        player.getCell().getObjectList().remove(player);
        player.removeFromSquare();
        player.getEmitter().stopAll();
    }
}
