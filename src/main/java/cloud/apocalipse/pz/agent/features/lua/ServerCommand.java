package cloud.apocalipse.pz.agent.features.lua;

import java.util.Arrays;
import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.commands.CommandArgs;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;

/** Sends the raw metadata from an RCON or dedicated-console command to server Lua. */
@CommandName(name = ServerCommand.NAME)
@CommandArgs(varArgs = true)
@CommandHelp(helpText = ServerCommand.USAGE, shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DisplayServerMessage)
public final class ServerCommand extends CommandBase {
    static final String NAME = "servercommand";
    static final String USAGE = "Usage: servercommand <metadata>";
    static final String EVENT = "OnApocBRServerCommand";

    public ServerCommand(String username, Role role, String command, UdpConnection connection) {
        super(username, role, command, connection);
    }

    public static Class<?>[] appendToRegistry(Class<?>[] commands) {
        Class<?>[] registered = Arrays.copyOf(commands, commands.length + 1);
        registered[commands.length] = ServerCommand.class;
        return registered;
    }

    @Override
    protected String Command() {
        if (!isCommandComeFromServerConsole()) {
            return "servercommand is available through RCON and the server console.";
        }
        // The vanilla argument parser removes quotes. Keep the raw payload for JSON and other formats.
        String input = getCommand();
        int separator = NAME.length();
        if (input.length() <= separator || !Character.isWhitespace(input.charAt(separator))) {
            return USAGE;
        }
        String metadata = input.substring(separator).stripLeading();
        if (metadata.isEmpty()) {
            return USAGE;
        }
        if (!LuaRuntime.isServerLuaThread()) {
            return "Server Lua is unavailable on this thread.";
        }
        LuaEventManager.triggerEvent(EVENT, metadata);
        return "Server command dispatched.";
    }
}
