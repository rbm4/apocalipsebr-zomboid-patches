package cloud.apocalipse.pz.agent.features.npc;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import zombie.chat.ChatBase;
import zombie.chat.ChatUtility;
import zombie.network.chat.ChatServer;
import zombie.network.chat.ChatType;

/** Native chat construction and recipient eligibility, with no fake account/chat membership. */
final class NpcChat {
    private static final Field CHATS=field("defaultChats"), PLAYERS=field("players");
    private static Field field(String name) {
        try {var f=ChatServer.class.getDeclaredField(name);f.setAccessible(true);return f;}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Original chat contract unavailable",failure);}
    }
    private static ChatBase chat(ChatType type) {
        if(!ChatServer.isInited()||!ChatUtility.chatStreamEnabled(type))return null;
        try {return (ChatBase)((Map<?,?>)CHATS.get(null)).get(type);}
        catch(IllegalAccessException failure){throw new IllegalStateException(failure);}
    }
    static float range(){var chat=chat(ChatType.shout);return chat==null?0:chat.getRange();}
    static boolean send(short recipient,String author,String text,boolean shout){
        var chat=chat(shout?ChatType.shout:ChatType.say);if(chat==null)return false;
        try {if(!((Set<?>)PLAYERS.get(null)).contains(recipient))return false;}
        catch(IllegalAccessException failure){throw new IllegalStateException(failure);}
        var message=chat.createMessage(text);message.setAuthor(author);chat.sendMessageToPlayer(recipient,message);return true;
    }
}
