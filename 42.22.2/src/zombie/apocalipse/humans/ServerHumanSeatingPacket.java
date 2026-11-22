package zombie.apocalipse.humans;

import zombie.ai.State;
import zombie.ai.states.PlayerSitOnFurnitureState;
import zombie.ai.states.PlayerGetUpState;
import zombie.iso.IsoDirections;
import zombie.iso.IsoObject;
import zombie.apocalipse.humans.seating.SeatingGeometry.Plan;
import zombie.network.packets.actions.StatePacket;

/** Exact native State payload. No synthetic client receive/authority path. */
final class ServerHumanSeatingPacket extends StatePacket {
    ServerHumanSeatingPacket(ServerHuman human,IsoObject object,Plan plan,boolean standing,boolean entering){
        set(human.player.getX(),human.player.getY(),human.player.getZ());characterId.set(human.player);
        state.set(standing?PlayerGetUpState.instance():PlayerSitOnFurnitureState.instance());
        isSubState=false;stage=entering?State.Stage.Enter:State.Stage.Exit;
        var direction=IsoDirections.fromString(plan.facing());
        if(standing){params.rawset("force",entering);params.rawset("moving",false);params.rawset("iso_direction",direction);}
        else {params.rawset("dir",direction);params.rawset("sit_object",object);params.rawset("before_sit_dir",plan.side());if(entering)events.get().put("EventSitOnFurniture","");}
    }
}
