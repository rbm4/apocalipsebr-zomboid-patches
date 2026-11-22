package zombie.apocalipse.humans;

import zombie.ai.State;
import zombie.ai.states.PlayerEmoteState;
import zombie.network.packets.actions.StatePacket;

/** Builds the existing State wire format without invoking a fake client receive path. */
final class ServerHumanEmotePacket extends StatePacket {
    ServerHumanEmotePacket(ServerHuman human, boolean playing) {
        set(human.player.getX(), human.player.getY(), human.player.getZ());
        characterId.set(human.player);
        state.set(PlayerEmoteState.instance());
        // The vanilla idle->emote transition is a substate.
        isSubState = true;
        stage = playing ? State.Stage.Enter : State.Stage.Exit;
        params.rawset("emote", human.emote);
        params.rawset("playing", playing);
        if (playing) events.get().put("EventEmote", "");
    }
}
