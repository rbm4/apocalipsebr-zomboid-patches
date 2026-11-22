package zombie.apocalipse.humans;

import zombie.core.raknet.UdpConnection;
import zombie.characters.IsoPlayer;
import zombie.characters.NetworkPlayerVariables;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.PlayerPacketReliable;
import zombie.network.chat.ChatServer;

/** Only vanilla 42.22.1 packets cross the wire; clients need no new Java classes. */
final class ServerHumanTransport {
    record Equipment(int primary,int secondary,int condition,int ammo,boolean chambered,boolean jammed) { }
    private ServerHumanTransport() {
    }

    static void announce(ServerHuman human, UdpConnection connection) {
        GameServer.sendPlayerConnected(human.player, connection);
        human.viewers.add(connection);
        human.equipmentPresentation.remove(connection);
        equipment(human,connection);
        if(human.aiming)new ServerHumanAimPacket(human,true).sendToClient(PacketTypes.PacketType.State,connection);
        human.movementPresentation.remove(connection);
        movementPresentation(human, connection, human.movement.presentation().animation());
        // Also clear a previously interrupted emote when a known client reloads.
        emote(human, connection, human.emote != null);
        human.movement.announceTraversal(connection);
        human.actuator.announceCorpseDrag(connection);
        if(human.healing!=null)human.healing.requestAnnouncement(connection);
        if(human.seating!=null)human.seating.transport.announce(connection);
    }

    static void idle(ServerHuman human, UdpConnection connection) {
        if(human.seating!=null&&!human.seating.transport.movementAllowed(connection))return;
        var motion = human.movement.snapshot();
        movementPresentation(human, connection, motion.animation());
        movementPacket(human, motion).sendToClient(PacketTypes.PacketType.PlayerUpdateReliable, connection);
    }

    /** Factory-owner thread only. Capture once, send synchronously, retain no actor snapshot. */
    static void movement(ServerHuman human) {
        PlayerPacketReliable packet = null;
        ServerHumanMovement.Snapshot motion = null;
        for (var viewer : human.viewers) {
            if (!human.isRelevant(viewer)) continue;
            if(human.seating!=null&&!human.seating.transport.movementAllowed(viewer))continue;
            if (packet == null) {
                motion = human.movement.snapshot();
                packet = movementPacket(human, motion);
            }
            movementPresentation(human, viewer, motion.animation());
            packet.sendToClient(PacketTypes.PacketType.PlayerUpdateReliable, viewer);
        }
    }

    /** One native position publication at a seating boundary; never mutates the actor for a viewer. */
    static void seatingPosition(ServerHuman human,UdpConnection viewer,zombie.apocalipse.humans.furniture.FurnitureAffordances.Position point){
        var motion=human.movement.snapshot();
        var snapshot=new ServerHumanMovement.Snapshot(point.x(),point.y(),point.z(),human.player.getDirectionAngleRadians(),
            human.player.getDirectionAngleRadians(),ServerHumanMovement.Gait.IDLE,0,0,motion.animation());
        movementPacket(human,snapshot).sendToClient(PacketTypes.PacketType.PlayerUpdateReliable,viewer);
    }

    private static PlayerPacketReliable movementPacket(ServerHuman human, ServerHumanMovement.Snapshot motion) {
        PlayerPacketReliable packet = new PlayerPacketReliable();
        packet.player.set(human.player);
        // Native prediction, clipped to a collision-checked straight segment.
        packet.prediction.type = 0;
        packet.prediction.x = motion.x();
        packet.prediction.y = motion.y();
        packet.prediction.z = motion.z();
        packet.prediction.direction = motion.facing();
        int predictionUnits = motion.predictionUnits();
        if (predictionUnits > 0) {
            packet.prediction.type = 1;
            packet.prediction.moveDirection = motion.travelDirection();
            packet.prediction.speed = motion.speed();
            packet.prediction.distance = (byte)predictionUnits;
        }
        packet.prediction.position.set(packet.prediction.x, packet.prediction.y, packet.prediction.z);
        packet.hitVehicleId.set(null);
        // The native movement flag drives client walking; god mode travels in ConnectedPlayer.
        packet.booleanVariables = motion.moving()
            ? (short)(1 << NetworkPlayerVariables.Flags.hasDeferredMovement.ordinal()) : 0;
        if (motion.gait() == ServerHumanMovement.Gait.RUN)
            packet.booleanVariables |= (short)(1 << NetworkPlayerVariables.Flags.isRunning.ordinal());
        if (motion.gait() == ServerHumanMovement.Gait.SPRINT)
            packet.booleanVariables |= (short)(1 << NetworkPlayerVariables.Flags.isSprinting.ordinal());
        if (motion.animation().sneaking())
            packet.booleanVariables |= (short)(1 << NetworkPlayerVariables.Flags.isSneaking.ordinal());
        // Every movement update replaces these native flags on the receiving player.
        // Omitting continuation makes the client lay down an otherwise valid grapple.
        if (human.player.isDoContinueGrapple())
            packet.booleanVariables |= (short)(1 << NetworkPlayerVariables.Flags.isDoContinueGrapple.ordinal());
        // State packets start timed actions; movement flags must preserve them until exit.
        if (human.player.isPerformingAnAction())
            packet.booleanVariables |= (short)(1 << NetworkPlayerVariables.Flags.isPerformingAction.ordinal());
        packet.disconnected = false;
        packet.variables.set(human.player);
        return packet;
    }

    private static void movementPresentation(ServerHuman human, UdpConnection connection,
            zombie.apocalipse.humans.movement.MovementModifiers.Animation animation) {
        if (animation.equals(human.movementPresentation.get(connection))) return;
        // Native injury packet supplies the cached walk/run modifiers used by remote-player animation.
        INetworkPacket.send(connection, PacketTypes.PacketType.PlayerInjuries, human.player);
        human.movementPresentation.put(connection, animation);
    }

    static void remove(ServerHuman human, UdpConnection connection) {
        INetworkPacket.send(connection, PacketTypes.PacketType.PlayerTimeout, human.player);
    }

    static void emote(ServerHuman human, UdpConnection connection, boolean playing) {
        new ServerHumanEmotePacket(human, playing).sendToClient(PacketTypes.PacketType.State, connection);
    }
    static void equipment(ServerHuman human){for(var viewer:human.viewers)if(human.isRelevant(viewer))equipment(human,viewer);}
    private static void equipment(ServerHuman human,UdpConnection connection){
        var primary=human.player.getPrimaryHandItem();var secondary=human.player.getSecondaryHandItem();
        var weapon=primary instanceof zombie.inventory.types.HandWeapon w?w:null;
        var snapshot=new Equipment(primary==null?-1:primary.getID(),secondary==null?-1:secondary.getID(),
            primary==null?0:primary.getCondition(),primary==null?0:primary.getCurrentAmmoCount(),
            weapon!=null&&weapon.isRoundChambered(),weapon!=null&&weapon.isJammed());
        var previous=human.equipmentPresentation.get(connection);
        if(snapshot.equals(previous)||primary==null&&previous==null)return;
        INetworkPacket.send(connection,PacketTypes.PacketType.Equip,human.player);
        human.equipmentPresentation.put(connection,snapshot);
    }
    static void aim(ServerHuman human,boolean enabled){
        if(human.aiming==enabled)return;
        human.aiming=enabled;human.player.setIsAiming(enabled);
        for(var viewer:human.viewers)if(human.isRelevant(viewer))new ServerHumanAimPacket(human,enabled).sendToClient(PacketTypes.PacketType.State,viewer);
    }

    static void hey(ServerHuman human){
        // Stock transmitPlayerVoiceSound only transmits from a client. Server-owned actors send directly.
        for(var viewer:human.viewers)if(human.isRelevant(viewer))
            zombie.network.packets.INetworkPacket.send(viewer,PacketTypes.PacketType.PlaySound,"ShoutHey",(byte)2,human.player);
        human.player.addWorldSoundUnlessInvisible(30,30,false);
    }

    static boolean shout(ServerHuman human,String text){
        float range=ChatServer.getInstance().humanShoutRange();if(range<=0)return false;
        boolean sent=false;
        for(var viewer:human.viewers){
            if(!human.isRelevant(viewer))continue;
            for(int slot=0;slot<4;slot++){
                var listener=viewer.getPlayerAt(slot);
                if(listener==null||listener.isDead()||listener.getZi()!=human.player.getZi())continue;
                double dx=listener.getX()-human.player.getX(),dy=listener.getY()-human.player.getY();
                if(dx*dx+dy*dy>range*range)continue;
                sent|=ChatServer.getInstance().sendHumanShout(listener.getOnlineID(),human.player.getUsername(),text);break;
            }
        }
        return sent;
    }

    static boolean say(ServerHuman human, String text) {
        boolean sent = false;
        for (UdpConnection connection : human.viewers) {
            if (!human.isRelevant(connection)) continue;
            // One chat packet per connection, including split-screen viewers.
            for (int slot = 0; slot < 4; slot++) {
                IsoPlayer listener = connection.getPlayerAt(slot);
                if (listener == null || listener.isDead() || listener.getZi() != human.definition.z()) continue;
                double dx = listener.getX() - human.player.getX();
                double dy = listener.getY() - human.player.getY();
                if (dx * dx + dy * dy > 30.0 * 30.0) continue;
                sent |= ChatServer.getInstance().sendHumanSay(listener.getOnlineID(), human.player.getUsername(), text);
                break;
            }
        }
        return sent;
    }
}
