// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.commands.serverCommands;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.commands.AltCommandArgs;
import zombie.commands.CommandArgs;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.CommandNames;
import zombie.commands.RequiredCapabilities;
import zombie.commands.RequiredCapability;
import zombie.core.logger.LoggerManager;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

@CommandNames({@CommandName(name = "teleport"), @CommandName(name = "tp")})
@AltCommandArgs(
    {@CommandArgs(required = "(.+)", argName = "just port to user"), @CommandArgs(required = {"(.+)", "(.+)"}, argName = "teleport user1 to user2")}
)
@CommandHelp(helpText = "UI_ServerOptionDesc_Teleport")
@RequiredCapabilities(
    {
            @RequiredCapability(requiredCapability = Capability.TeleportToPlayer, argName = "just port to user"),
            @RequiredCapability(requiredCapability = Capability.TeleportPlayerToAnotherPlayer, argName = "teleport user1 to user2")
    }
)
public class TeleportCommand extends TeleportPlayerCommand {
    public static final String JUST_TO_USER = "just port to user";
    public static final String USER_TO_USER = "teleport user1 to user2";

    public TeleportCommand(String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    protected String Command() {
        String var1 = this.argsName;

        return switch (var1) {
            case "just port to user" -> {
                this.username1 = this.getCommandArg(0);
                yield this.TeleportMeToUser();
            }
            case "teleport user1 to user2" -> {
                this.username1 = this.getCommandArg(0);
                this.username2 = this.getCommandArg(1);
                yield this.TeleportUser1ToUser2();
            }
            default -> this.CommandArgumentsNotMatch();
        };
    }

    private String TeleportMeToUser() {
        if (this.connection == null) {
            return "Need player to teleport to, ex /teleport user1 user2";
        } else {
            IsoPlayer pl = GameServer.getPlayerByUserNameForCommand(this.username1);
            if (pl != null) {
                GameServer.sendTeleport(this.connection.players[0], pl.getX(), pl.getY(), pl.getZ());
                this.username1 = pl.getUsername();
                LoggerManager.getLogger("admin").write(this.getExecutorUsername() + " teleport to " + this.username1);
                return "teleported to " + this.username1 + " please wait two seconds to show the map around you.";
            } else {
                return "Can't find player " + this.username1;
            }
        }
    }
}
