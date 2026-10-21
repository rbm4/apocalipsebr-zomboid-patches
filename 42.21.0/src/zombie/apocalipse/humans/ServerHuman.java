package zombie.apocalipse.humans;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import zombie.apocalipse.humans.brain.HumanActivityController;
import zombie.apocalipse.humans.profiles.HumanProfile;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoCell;

/** A server-owned presentation actor, deliberately outside live-player simulation. */
final class ServerHuman {
    final ServerHumanDefinition definition;
    final IsoPlayer player;
    final Set<UdpConnection> viewers = Collections.newSetFromMap(new IdentityHashMap<>());
    final java.util.Map<UdpConnection, zombie.apocalipse.humans.movement.MovementModifiers.Animation> movementPresentation = new IdentityHashMap<>();
    final HumanActivityController activity;
    final HumanProfile profile;
    final ServerHumanActuator actuator;
    final ServerHumanMovement movement;
    final ServerHumanWalking walking;
    final ServerHumanPerception perception;
    final ServerHumanTerritory territory;
    final ServerHumanCombat combat;
    final ServerHumanInventory inventory;
    boolean aiming;
    final java.util.Map<UdpConnection,ServerHumanTransport.Equipment> equipmentPresentation=new IdentityHashMap<>();
    final zombie.apocalipse.humans.state.HumanStateStore state;
    final ServerHumanPhysiology physiology;
    float postX, postY;
    String emote;
    private boolean disposed;

    ServerHuman(ServerHumanDefinition definition, short onlineId, IsoCell cell) {
        this(definition,onlineId,cell,new zombie.apocalipse.humans.state.HumanStateStore(zombie.apocalipse.humans.state.HumanState.INITIAL));
    }

    ServerHuman(ServerHumanDefinition definition, short onlineId, IsoCell cell, zombie.apocalipse.humans.state.HumanStateStore state) {
        this(definition, onlineId, cell, state, new zombie.apocalipse.humans.traversal.TraversalWorld());
    }

    ServerHuman(ServerHumanDefinition definition, short onlineId, IsoCell cell, zombie.apocalipse.humans.state.HumanStateStore state,
                zombie.apocalipse.humans.traversal.TraversalWorld traversalWorld) {
        this.state=state;
        this.definition = definition;
        this.profile = ServerHumanProfiles.registry().require(definition.profileId());
        this.activity = new HumanActivityController(ServerHumanProfiles.registry().create(profile),profile.dialogue(),profile.trading());
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
        this.movement = new ServerHumanMovement(this, traversalWorld);
        this.walking = new ServerHumanWalking(this);
        this.territory = new ServerHumanTerritory(this);
        this.perception = new ServerHumanPerception(this);
        this.inventory = new ServerHumanInventory(this);
        this.combat = new ServerHumanCombat(this);
        this.actuator = new ServerHumanActuator(this);
        this.physiology = new ServerHumanPhysiology(this);
    }

    boolean isRelevant(UdpConnection connection) {
        return connection.isFullyConnected() && connection.isRelevantTo(player.getX(), player.getY());
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        activity.stop(System.nanoTime(), actuator);
        combat.stop();
        ServerHumanDialogue.close(this, "removed");
        ServerHumanTrading.close(this);
        movement.close();
        perception.clear();
        // Never call normal disconnectPlayer(): this actor has no account/connection/save.
        for (UdpConnection connection : viewers) {
            if (connection.isFullyConnected()) ServerHumanTransport.remove(this, connection);
        }
        viewers.clear();
        movementPresentation.clear();
        equipmentPresentation.clear();
        player.getDescriptor().setInstance(null);
        player.getCell().getAddList().remove(player);
        player.getCell().getObjectList().remove(player);
        player.removeFromSquare();
        player.getEmitter().stopAll();
    }
}
