// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.intent.Intent;
import org.javacord.api.entity.message.Message;
import org.javacord.api.event.message.MessageCreateEvent;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.server.IEventController;
import zombie.util.StringUtils;

public class DiscordBot implements IEventController {
    private static final String LOG_CHANNEL_IS_NOT_CONFIGURED = "Log channel name is not configured";
    private static final String CHAT_CHANNEL_IS_NOT_CONFIGURED = "Chat channel name is not configured";
    private static final String COMMAND_CHANNEL_IS_NOT_CONFIGURED = "Command channel name is not configured";
    private static final String SERVER_NAME_IS_NOT_CONFIGURED = "Server name is not configured";
    private static final String CONNECTION_ERROR = "Can't connect to discord";
    private static final String INVITE_URL = "invite-url ";
    private static final long CONNECTION_TIMEOUT = 30L;
    private final DiscordSender sender;
    private TextChannel chatChannel;
    private TextChannel logChannel;
    private TextChannel commandChannel;

    @Override
    public void process(String event) {
        this.logEvent(event);
    }

    public void logEvent(String event) {
        if (this.logChannel != null) {
            this.logChannel.sendMessage(event);
        }
    }

    public void sendMessage(String user, String text) {
        if (this.chatChannel != null) {
            this.chatChannel.sendMessage(user + ": " + text);
        }
    }

    public DiscordBot(DiscordSender sender) {
        this.sender = sender;
    }

    public void connect(String serverName, boolean enabled, String token, String chatChannelName, String logChannelName, String commandChannelName) {
        if (enabled && !StringUtils.isNullOrEmpty(token)) {
            CompletableFuture<DiscordApi> login = new DiscordApiBuilder().setToken(token).setIntents(Intent.GUILD_MESSAGES, Intent.MESSAGE_CONTENT).login();

            try {
                DiscordApi api = login.get(30L, TimeUnit.SECONDS);
                if (api != null) {
                    if (!StringUtils.isNullOrEmpty(logChannelName)) {
                        this.logChannel = api.getTextChannelsByName(logChannelName).stream().findFirst().orElse(null);
                    } else {
                        DebugType.Discord.debugln("Log channel name is not configured");
                    }

                    if (!StringUtils.isNullOrEmpty(chatChannelName)) {
                        this.chatChannel = api.getTextChannelsByName(chatChannelName).stream().findFirst().orElse(null);
                    } else {
                        DebugType.Discord.debugln("Chat channel name is not configured");
                    }

                    if (!StringUtils.isNullOrEmpty(commandChannelName)) {
                        this.commandChannel = api.getTextChannelsByName(commandChannelName).stream().findFirst().orElse(null);
                    } else {
                        DebugType.Discord.debugln("Command channel name is not configured");
                    }

                    if (!StringUtils.isNullOrEmpty(serverName)) {
                        api.updateUsername(serverName);
                    } else {
                        DebugType.Discord.debugln("Server name is not configured");
                    }

                    if (this.chatChannel != null || this.commandChannel != null) {
                        api.addMessageCreateListener(this::receiveMessage);
                        DebugType.Discord.println("invite-url " + api.createBotInvite());
                    }
                }
            } catch (TimeoutException var9) {
                login.whenComplete((lateApi, error) -> {
                    if (lateApi != null) {
                        lateApi.disconnect();
                    }
                });
                DebugType.Discord.printException(var9, "Can't connect to discord", LogSeverity.Error);
            } catch (Exception var10) {
                DebugType.Discord.printException(var10, "Can't connect to discord", LogSeverity.Error);
            }
        }
    }

    private void receiveMessage(MessageCreateEvent messageCreateEvent) {
        TextChannel messageChannel = messageCreateEvent.getChannel();
        if (messageChannel != null) {
            Message message = messageCreateEvent.getMessage();
            if (message != null) {
                if (this.chatChannel != null && this.chatChannel.getId() == messageChannel.getId() && !message.getAuthor().isYourself()) {
                    String content = removeSmilesAndImages(message.getReadableContent());
                    if (!content.isEmpty()) {
                        this.sender.sendMessageFromDiscord(message.getAuthor().getDisplayName(), content);
                    }
                }

                if (this.commandChannel != null && this.commandChannel.getId() == messageChannel.getId() && !message.getAuthor().isYourself()) {
                    String response = GameServer.rcon(message.getReadableContent());
                    if (response != null) {
                        this.commandChannel.sendMessage(response);
                    }
                }
            }
        }
    }

    private static String removeSmilesAndImages(String content) {
        StringBuilder sb = new StringBuilder();
        char[] var2 = content.toCharArray();
        int var3 = var2.length;

        for (int var4 = 0; var4 < var3; var4++) {
            Character cur = var2[var4];
            if (!Character.isLowSurrogate(cur) && !Character.isHighSurrogate(cur)) {
                sb.append(cur);
            }
        }

        return sb.toString();
    }
}
