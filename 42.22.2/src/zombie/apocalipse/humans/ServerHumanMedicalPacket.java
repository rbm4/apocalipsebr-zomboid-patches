package zombie.apocalipse.humans;

import zombie.ai.State;
import zombie.ai.states.PlayerActionsState;
import zombie.network.fields.Variables;
import zombie.network.packets.actions.StatePacket;

/** Exact native remote-player timed-action presentation; health and item use remain server-owned. */
final class ServerHumanMedicalPacket extends StatePacket {
    ServerHumanMedicalPacket(ServerHuman human,String region,boolean playing) {
        set(human.player.getX(),human.player.getY(),human.player.getZ());
        characterId.set(human.player);state.set(PlayerActionsState.instance());isSubState=true;
        stage=playing?State.Stage.Enter:State.Stage.Exit;
        var variables=new Variables();
        variables.get().put("PerformingAction","Bandage");variables.get().put("BandageType",region);
        params.rawset("variables",variables);params.rawset("primary","");params.rawset("secondary","");
        params.rawset("override",true);params.rawset("sitonground",false);params.rawset("reload_speed",1F);
        if(playing)events.get().put("EventBandage","");
    }
}
