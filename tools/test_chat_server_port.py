"""Review44 extracted native chat dispatch/whisper and actual added helper bodies.

Remaining candidate text is checked against target vanilla. Chat/network/filter/
backend ports are recording doubles; full ChatServer/JAR/UI/backend not executed.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"
REL = "zombie/network/chat/ChatServer.java"
DIFF = V / "docs/reports/2026-10-28-merge-44-chat-server.diff"


def method(source, signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def main():
    with tempfile.TemporaryDirectory(prefix="pz-chat-server-") as directory:
        w = Path(directory)
        p = w / "42.22.0/src" / REL
        p.parent.mkdir(parents=True)
        native = (V / "decompiled" / REL).read_text(encoding="utf-8-sig")
        p.write_text(native, encoding="utf-8", newline="\n")
        subprocess.run(["git", "apply", str(DIFF)], cwd=w, check=True)
        candidate = p.read_text()
        old = (ROOT / "42.21.0/src" / REL).read_text()
        a = old.index("    /** Server-owned presentation actors have no chat account;")
        helpers = old[a:old.index("    public void sendMessageFromDiscordToGeneralChat", a)]
        hook = '''                        if (chat.getType() == ChatType.general) {
                            zombie.network.ApocBRBackendBridgeHooks.onChat(connection, message.getAuthor(), message.getText(), "general");
                        }
'''
        assert candidate.replace(helpers, "").replace(hook, "") == native
        bodies = "\n".join(method(candidate, signature) for signature in [
            "public void processMessageFromPlayerPacket(", "public void processPlayerStartWhisperChatPacket(",
            "private void sendMessage(ChatMessage", "public boolean sendHumanSay(",
            "public float humanShoutRange(", "public boolean sendHumanShout(",
        ])
        short_send = method((V / "decompiled/zombie/chat/ChatBase.java").read_text(), "public void sendMessageToPlayer(short")
        bridge = w / "zombie/network/ApocBRBackendBridgeHooks.java"
        bridge.parent.mkdir(parents=True)
        bridge.write_text('''package zombie.network;public class ApocBRBackendBridgeHooks {public static java.util.List<java.util.List<Object>>events=new java.util.ArrayList<>();public static void onChat(Object c,String a,String t,String channel){events.add(java.util.List.of(c,a,t,channel));}}''')
        shell = r'''
import java.util.*;import java.nio.*;import zombie.network.ApocBRBackendBridgeHooks;
class ChatServer {
 boolean inited=true;static Set<Short>players=new HashSet<>();static Map<Integer,ChatBase>chats=new HashMap<>();static Map<ChatType,ChatBase>defaultChats=new HashMap<>();static Map<String,Object>tabs=new HashMap<>();static Logger logger=new Logger();int notFound,next=100;
 int getNextChatID(){return next++;}void sendPlayerNotFoundMessage(UdpConnection c,String n){notFound++;}
''' + bodies + "}\n" + PORTS + "\nclass ChatBase extends Base {\n" + short_send + "}\n" + TEST
        java = w / "ChatTest.java"
        java.write_text(shell, encoding="utf-8")
        subprocess.run(["javac", "-d", str(w), str(java), str(bridge)], check=True)
        subprocess.run(["java", "-cp", str(w), "ChatTest"], check=True)


PORTS = r'''
enum ChatType {general,say,shout,server,whisper}
class Logger {void write(String s,Object...v){}}
class ByteBufferReader {final ByteBuffer b;ByteBufferReader(ByteBuffer b){this.b=b;}int getInt(){return b.getInt();}String getUTF(){byte[]v=new byte[b.getShort()];b.get(v);return new String(v,java.nio.charset.StandardCharsets.UTF_8);}}
class IsoPlayer {short id;String name;boolean muted;IsoPlayer(int id,String n){this.id=(short)id;name=n;}short getOnlineID(){return id;}String getUsername(){return name;}void setAllChatMuted(boolean v){muted=v;}}
class UdpConnection {Set<String>names=new HashSet<>();Set<Short>ids=new HashSet<>();boolean hasPlayer(String n){return names.contains(n);}String getUserName(){return "fixture";}}
class ChatUtility {static Set<ChatType>disabled=new HashSet<>();static Map<String,IsoPlayer>players=new HashMap<>();static Map<Short,UdpConnection>connections=new HashMap<>();static boolean chatStreamEnabled(ChatType t){return !disabled.contains(t);}static IsoPlayer findPlayer(String n){return players.get(n);}static UdpConnection findConnection(short id){return connections.get(id);}}
class ChatMessage {int id;String author,text;ChatMessage(int id,String a,String t){this.id=id;author=a;text=t;}int getChatID(){return id;}String getAuthor(){return author;}String getText(){return text;}void setAuthor(String a){author=a;}void setText(String t){text=t;}}
class Base {int id,unpacks,broadcasts,direct;float range=30;boolean fail;ChatType type;ChatMessage last;Set<Short>members=new HashSet<>();int getID(){return id;}ChatType getType(){return type;}float getRange(){return range;}boolean hasMember(UdpConnection c){return c.ids.stream().anyMatch(members::contains);}ChatMessage unpackMessage(ByteBufferReader b){unpacks++;return new ChatMessage(id,b.getUTF(),b.getUTF());}ChatMessage createMessage(String t){return new ChatMessage(id,"",t);}void sendMessageToChatMembers(ChatMessage m){if(fail)throw new IllegalStateException("fixture send");broadcasts++;last=m;}void sendChatMessageToPlayer(UdpConnection c,ChatMessage m){direct++;last=m;}}
class WhisperChat extends ChatBase {WhisperChat(int id,Object tab,String a,String b){this.id=id;this.type=ChatType.whisper;}void addMember(short id){members.add(id);}}
class WordsFilter {record SearchResult(){}static WordsFilter getInstance(){return new WordsFilter();}List<SearchResult>searchText(String t){return t.contains("bad")?List.of(new SearchResult()):List.of();}String hideBadWords(String t,List<SearchResult>r,String replacement){return t.replace("bad",replacement);}}
class ServerOptions {static ServerOptions instance=new ServerOptions();static ServerOptions getInstance(){return instance;}IntOpt badWordPolicy=new IntOpt();StringOpt badWordReplacement=new StringOpt();static class IntOpt{int value=3;int getValue(){return value;}String getName(){return "policy";}}static class StringOpt{String getValue(){return "***";}}}
class AntiCheat {static int bans,kicks,logs;static void doBanUser(Object c,String p,String t){bans++;}static void doKickUser(Object c,String p,String t){kicks++;}static void doLogUser(Object c,String p,String t){logs++;}}
class DebugType {static DebugType DetailedInfo=new DebugType();void println(String s){}}
'''

TEST = r'''
class ChatTest {
 static int checks;static ChatServer server;static ChatBase general,say,shout;static UdpConnection connection;static IsoPlayer alice,bob;
 static void check(boolean b){if(!b)throw new AssertionError("check "+checks);checks++;}
 static ChatBase chat(int id,ChatType type){var c=new ChatBase();c.id=id;c.type=type;c.members.add((short)1);ChatServer.chats.put(id,c);ChatServer.defaultChats.put(type,c);return c;}
 static void setup(){server=new ChatServer();ChatServer.players.clear();ChatServer.chats.clear();ChatServer.defaultChats.clear();ChatUtility.disabled.clear();ChatUtility.players.clear();ChatUtility.connections.clear();ApocBRBackendBridgeHooks.events.clear();ServerOptions.instance.badWordPolicy.value=3;alice=new IsoPlayer(1,"Alice");bob=new IsoPlayer(2,"Bob");ChatUtility.players.put("Alice",alice);ChatUtility.players.put("Bob",bob);connection=new UdpConnection();connection.names.add("Alice");connection.ids.add((short)1);ChatUtility.connections.put((short)1,connection);ChatServer.players.add((short)1);general=chat(1,ChatType.general);say=chat(2,ChatType.say);shout=chat(3,ChatType.shout);}
 static ByteBufferReader body(int id,String...texts){var b=ByteBuffer.allocate(1024);if(id>=0)b.putInt(id);for(var s:texts){var v=s.getBytes(java.nio.charset.StandardCharsets.UTF_8);b.putShort((short)v.length).put(v);}return new ByteBufferReader(b.flip());}
 static void message(int id,String author,String text){server.processMessageFromPlayerPacket(body(id,author,text),connection);}
 public static void main(String[]args){
  setup();message(999,"Alice","hello");check(general.unpacks==0&&ApocBRBackendBridgeHooks.events.isEmpty());general.members.clear();message(1,"Alice","hello");check(general.unpacks==0&&general.broadcasts==0);general.members.add((short)1);general.type=ChatType.server;message(1,"Alice","hello");check(general.unpacks==0);
  setup();message(1,"Bob","spoofed");check(general.unpacks==1&&general.broadcasts==0&&ApocBRBackendBridgeHooks.events.isEmpty());ChatUtility.disabled.add(ChatType.general);message(1,"Alice","disabled");check(general.broadcasts==0&&ApocBRBackendBridgeHooks.events.isEmpty());
  setup();message(1,"Alice","hello bad word");check(general.broadcasts==1&&general.last.text.equals("hello *** word"));check(ApocBRBackendBridgeHooks.events.size()==1);var e=ApocBRBackendBridgeHooks.events.getFirst();check(e.get(0)==connection&&e.get(1).equals("Alice")&&e.get(2).equals("hello *** word")&&e.get(3).equals("general"));message(2,"Alice","local");message(3,"Alice","loud");check(ApocBRBackendBridgeHooks.events.size()==1&&say.broadcasts==1&&shout.broadcasts==1);
  setup();ServerOptions.instance.badWordPolicy.value=4;message(1,"Alice","bad");check(alice.muted&&general.broadcasts==0&&ApocBRBackendBridgeHooks.events.isEmpty());
  for(int policy:new int[]{1,2,3}){setup();ServerOptions.instance.badWordPolicy.value=policy;message(1,"Alice","bad");check(general.broadcasts==1&&ApocBRBackendBridgeHooks.events.size()==1);check(general.last.text.equals("***"));} // Preserve native switch flow after ban/kick/log.
  setup();general.fail=true;try{message(1,"Alice","hello");throw new AssertionError();}catch(IllegalStateException expected){check(ApocBRBackendBridgeHooks.events.isEmpty());}
  setup();server.processPlayerStartWhisperChatPacket(body(-1,"Bob","Alice"),connection);check(ChatServer.chats.size()==3&&server.notFound==0);server.processPlayerStartWhisperChatPacket(body(-1,"Alice","Missing"),connection);check(server.notFound==1);server.processPlayerStartWhisperChatPacket(body(-1,"Alice","Bob"),connection);check(ChatServer.chats.size()==4&&ChatServer.chats.get(100).members.containsAll(List.of((short)1,(short)2)));ChatUtility.disabled.add(ChatType.whisper);server.processPlayerStartWhisperChatPacket(body(-1,"Alice","Bob"),connection);check(ChatServer.chats.size()==4);
  for(boolean loud:new boolean[]{false,true}){setup();var c=loud?shout:say;check(loud?server.sendHumanShout((short)1,"NPC","hello"):server.sendHumanSay((short)1,"NPC","hello"));check(c.direct==1&&c.last.author.equals("NPC")&&c.last.text.equals("hello"));check(!ChatUtility.players.containsKey("NPC")&&ApocBRBackendBridgeHooks.events.isEmpty());server.inited=false;check(!(loud?server.sendHumanShout((short)1,"NPC","no"):server.sendHumanSay((short)1,"NPC","no")));server.inited=true;ChatServer.players.clear();check(!(loud?server.sendHumanShout((short)1,"NPC","no"):server.sendHumanSay((short)1,"NPC","no")));ChatServer.players.add((short)1);ChatUtility.disabled.add(c.type);check(!(loud?server.sendHumanShout((short)1,"NPC","no"):server.sendHumanSay((short)1,"NPC","no")));ChatUtility.disabled.clear();ChatServer.defaultChats.remove(c.type);check(!(loud?server.sendHumanShout((short)1,"NPC","no"):server.sendHumanSay((short)1,"NPC","no")));check(c.direct==1);}
  setup();shout.range=55;check(server.humanShoutRange()==55);server.inited=false;check(server.humanShoutRange()==0);server.inited=true;ChatUtility.disabled.add(ChatType.shout);check(server.humanShoutRange()==0);ChatUtility.disabled.clear();ChatServer.defaultChats.remove(ChatType.shout);check(server.humanShoutRange()==0);
  setup();ChatUtility.connections.clear();check(server.sendHumanSay((short)1,"NPC","stale recipient"));check(say.direct==0); // Native short-recipient overload silently drops missing connection; true is an attempt, not an ACK.
  System.out.println("ChatServer candidate: "+checks+" assertions passed (extracted native dispatch/whisper and NPC helpers, recording ports)");
 }
}
'''


if __name__ == "__main__":
    main()
