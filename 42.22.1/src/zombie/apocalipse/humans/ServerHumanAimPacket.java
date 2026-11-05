package zombie.apocalipse.humans;

import zombie.ai.State;
import zombie.ai.states.PlayerAimState;
import zombie.network.packets.actions.StatePacket;

/** Native player aim-state wire format; no custom client class or emote animation. */
final class ServerHumanAimPacket extends StatePacket {
    ServerHumanAimPacket(ServerHuman human,boolean aiming){
        set(human.player.getX(),human.player.getY(),human.player.getZ());
        characterId.set(human.player);state.set(PlayerAimState.instance());isSubState=true;
        stage=aiming?State.Stage.Enter:State.Stage.Exit;
        params.rawset("aim",aiming);params.rawset("aim_floor",false);params.rawset("aim_floor_distance",0F);
    }
}
