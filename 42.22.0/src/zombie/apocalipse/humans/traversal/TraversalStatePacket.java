package zombie.apocalipse.humans.traversal;

import zombie.ai.State;
import zombie.ai.states.ClimbOverFenceState;
import zombie.ai.states.ClimbThroughWindowPositioningParams;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.ai.states.IdleState;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoDirections;
import zombie.iso.IsoObject;
import zombie.network.packets.actions.StatePacket;

/** Existing 42.21 State wire format. No fake client ownership and no local animation simulation. */
final class TraversalStatePacket extends StatePacket {
    TraversalStatePacket(IsoPlayer player, TraversalEdge edge, boolean entering, boolean landed) {
        set(player.getX(), player.getY(), player.getZ());
        characterId.set(player);
        isSubState = false;
        stage = entering ? State.Stage.Enter : State.Stage.Exit;
        IsoDirections direction = direction(edge);
        String outcome = entering || landed ? "success" : "back";
        params.rawset("prev_state", IdleState.instance());
        params.rawset("zombie_on_floor", false);
        params.rawset("outcome", outcome);
        if (edge.kind() == TraversalEdge.Kind.FENCE) {
            state.set(ClimbOverFenceState.instance());
            params.rawset("start_x", edge.from().x()); params.rawset("start_y", edge.from().y());
            params.rawset("end_x", edge.to().x()); params.rawset("end_y", edge.to().y());
            params.rawset("z", edge.z()); params.rawset("dir", direction);
            params.rawset("scratch", false); params.rawset("counter", false);
            params.rawset("solid_floor", true); params.rawset("sheet_rope", false);
            params.rawset("run", false); params.rawset("sprint", false); params.rawset("collidable", false);
            if (entering) events.get().put("EventClimbFence", "");
        } else {
            state.set(ClimbThroughWindowState.instance());
            // Reuse the actor-owned native parameter holder; sends serialize immediately on this thread.
            ClimbThroughWindowPositioningParams positioning = player.get(ClimbThroughWindowState.PARAMS);
            positioning.canClimb = true; positioning.climbDir = direction;
            positioning.climbingCharacter = player;
            IsoObject portal = (IsoObject)edge.obstacle();
            // A removed/unloaded portal must not reach NetObject.setObject with a null square.
            positioning.windowObject = portal.getSquare() != null && portal.getObjectIndex() >= 0 ? portal : null;
            positioning.startX = edge.from().x(); positioning.startY = edge.from().y(); positioning.z = edge.z();
            positioning.endX = positioning.oppositeX = edge.to().x();
            positioning.endY = positioning.oppositeY = edge.to().y();
            positioning.scratch = positioning.isCounter = positioning.isSheetRope = false;
            positioning.isFloor = true;
            params.rawset("params", positioning); params.rawset("scratched", false);
            if (entering) events.get().put("EventClimbWindow", "");
        }
    }
    static IsoDirections direction(TraversalEdge edge) {
        if (edge.to().x() > edge.from().x()) return IsoDirections.E;
        if (edge.to().x() < edge.from().x()) return IsoDirections.W;
        return edge.to().y() > edge.from().y() ? IsoDirections.S : IsoDirections.N;
    }
}
