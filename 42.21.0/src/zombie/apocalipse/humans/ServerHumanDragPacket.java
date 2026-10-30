package zombie.apocalipse.humans;

import zombie.ai.State;
import zombie.ai.states.PlayerDraggingCorpse;
import zombie.characters.IsoZombie;
import zombie.network.packets.actions.StatePacket;

/** Native state payload built explicitly; setData would restore remote state on the sender. */
final class ServerHumanDragPacket extends StatePacket {
    ServerHumanDragPacket(ServerHuman human,IsoZombie target,String type,boolean entering,boolean continuing,float bearing){
        set(human.player.getX(),human.player.getY(),human.player.getZ());
        characterId.set(human.player);state.set(PlayerDraggingCorpse.instance());isSubState=false;
        stage=entering?State.Stage.Enter:State.Stage.Exit;
        if(target!=null)params.rawset("grappling_target",target);
        params.rawset("grappling_type",type);params.rawset("do_grapple",false);
        params.rawset("do_continue_grapple",continuing);params.rawset("bearing_from_grapple_target",bearing);
        params.rawset("is_throw_out_window",false);params.rawset("is_throw_over_fence",false);
        params.rawset("is_throw_into_container",false);params.rawset("grunt_counter",0);
    }
}
