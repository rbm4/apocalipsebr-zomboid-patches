"""Execute extracted original/patched packet dispatch against explicit external engine doubles."""
import json
import pathlib
import re
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = ROOT.parent/'jdk/bin'


def method(text, signature):
    start = text.index(signature)
    end = text.index('{', start)+1
    depth = 1
    while depth:
        depth += (text[end]=='{')-(text[end]=='}')
        end += 1
    return text[start:end]


class NetworkTelemetryTest(unittest.TestCase):
    def test_update_orchestration_branches(self):
        original=(ROOT/'decompiled/zombie/network/GameClient.java').read_text()
        patched=(ROOT/'src/zombie/network/GameClient.java').read_text()
        source=r'''
import java.util.*;import java.util.concurrent.*;import zombie.ApocBRClientTelemetryLite;
public class NetworkUpdateHarness {
 static int scenario;static ArrayList<String>events=new ArrayList<>();static void event(String s){events.add(s);}
 static class Option {boolean getValue(){return true;}}
 static class ServerOptions {static final ServerOptions instance=new ServerOptions();Option disableSafehouseWhenOwnerConnected=new Option();}
 static class SafeHouse {static void updateSafehousePlayersConnected(){event("safehouse");}}
 static class SteamUtils {static boolean isSteamModeEnabled(){return false;}}
 static class LuaEventManager {static void triggerEvent(String name){event(name);}static void triggerEvent(String name,Object arg){event(name+"="+arg);}}
 static class Translator {static String getText(String s){return s;}}
 static class DebugType {static final DebugType Network=new DebugType(),General=new DebugType();void printException(Exception e,LogSeverity l){event("print:"+e.getMessage());}}
 enum LogSeverity {Error}
 static class DebugLog {static void log(DebugType t,String s){event("log:"+s);}}
 static class ExceptionLogger {static void logException(Exception e){event("exception:"+e.getMessage());}}
 static class PacketTypes {enum PacketType {Kicked,Other,ChunkObjectStateRequest,VehicleRequest,ZombieHitThumpable}}
 static class ByteBufferReader {}
 static class ZomboidNetData {int id;long time;PacketTypes.PacketType type=PacketTypes.PacketType.Other;ByteBufferReader buffer=new ByteBufferReader();
  ZomboidNetData(int id,boolean future){this.id=id;time=System.currentTimeMillis()+(future?60000:-1000);}public String toString(){return "data"+id;}}
 static class KickedPacket {String reason="test";void parse(ByteBufferReader b,Object c){event("kick-parse");}String getMessage(){return "kicked";}}
 static class DebugMultiplayer {void warn(String s){event(s);}}
 static class WorldStreamer {static final WorldStreamer instance=new WorldStreamer();void updateMain(){event("streaming");if(scenario==8)throw new IllegalStateException("streaming");}}
 static class IsoWorld {static final IsoWorld instance=new IsoWorld();ArrayList<Object>addCoopPlayers=new ArrayList<>();}
 static class Frequency {boolean voice;Frequency(boolean v){voice=v;}boolean Check(){return voice?scenario!=10:scenario!=9;}}
 static class Connection {ArrayList<Object>chunkObjectStateRequests=new ArrayList<>(),vehicleRequests=new ArrayList<>(),thumpHits=new ArrayList<>();void checkReady(){event("ready");}}
 static class INetworkPacket {static void send(PacketTypes.PacketType t,Object o){event("send:"+t);}}
 static class VoiceManager {static VoiceManager getInstance(){return new VoiceManager();}void UpdateChannelsRoaming(Connection c){event("voice");}}
 static class GameTime {static GameTime getInstance(){return new GameTime();}float getMultiplier(){return 1;}}
 static class ChatManager {static void UpdateClient(){event("chat");}}
 static class StatisticManager {static StatisticManager getInstance(){return new StatisticManager();}void update(long t){event("statistics");}}
 static class GameWindow {static boolean serverDisconnected;static String kickReason;}
 static class ConnectionManager {static ConnectionManager getInstance(){return new ConnectionManager();}void process(){event("disconnect-cleanup");}}
 static class BaseClient {
  int safehouseUpdateTimer=1;boolean connectionLost,playerConnectSent=true;float timeSinceKeepAlive;long clientCycleLast,clientCycle;
  static Connection connection;final ArrayList<Integer>delayedDisconnect=new ArrayList<>();
  static final ConcurrentLinkedQueue<ZomboidNetData>MainLoopNetDataQ=new ConcurrentLinkedQueue<>();
  static final ArrayList<ZomboidNetData>MainLoopNetData=new ArrayList<>(),LoadingMainLoopNetData=new ArrayList<>(),DelayedCoopNetData=new ArrayList<>();
  Frequency requestsFrequency=new Frequency(false),updateChannelsRoamingLimit=new Frequency(true);
  void timeoutRemotePlayers(){event("timeout");if(scenario==7)throw new IllegalStateException("timeout");}
  void sendAddedRemovedItems(boolean f){event("inventory");if(scenario==6)throw new IllegalStateException("inventory");}
  void updateVehiclesAnticlipping(){event("vehicles");}
  boolean gameLoadingDealWithNetData(ZomboidNetData d){event("loading:"+d.id);return d.id%2==0;}
  void mainLoopDealWithNetData(ZomboidNetData d){event("packet:"+d.id);}
 }
 static class Vanilla extends BaseClient { ORIGINAL }
 static class Patched extends BaseClient { PATCHED }
 static String run(BaseClient c){
  events.clear();BaseClient.MainLoopNetDataQ.clear();BaseClient.MainLoopNetData.clear();BaseClient.LoadingMainLoopNetData.clear();BaseClient.DelayedCoopNetData.clear();
  BaseClient.connection=new Connection();BaseClient.connection.chunkObjectStateRequests.add(1);BaseClient.connection.vehicleRequests.add(1);BaseClient.connection.thumpHits.add(1);
  GameWindow.serverDisconnected=false;GameWindow.kickReason=null;IsoWorld.instance.addCoopPlayers.clear();
  BaseClient.MainLoopNetData.add(new ZomboidNetData(1,false));BaseClient.MainLoopNetData.add(new ZomboidNetData(2,true));
  ZomboidNetData kick=new ZomboidNetData(3,false);kick.type=PacketTypes.PacketType.Kicked;BaseClient.MainLoopNetDataQ.add(kick);
  BaseClient.LoadingMainLoopNetData.add(new ZomboidNetData(4,false));BaseClient.DelayedCoopNetData.add(new ZomboidNetData(5,false));
  c.playerConnectSent=scenario!=1&&scenario!=2;c.connectionLost=scenario==2||scenario==3;
  if(scenario==4)IsoWorld.instance.addCoopPlayers.add(1);
  if(scenario==5){c.safehouseUpdateTimer=0;c.delayedDisconnect.addAll(List.of(17,18,21,23,24,32));}
  String thrown="";try{if(c instanceof Vanilla v)v.update();else ((Patched)c).update();}catch(Exception e){thrown=e.getMessage();}
  return events+"/"+BaseClient.MainLoopNetData+"/"+BaseClient.LoadingMainLoopNetData+"/"+BaseClient.DelayedCoopNetData+"/"+c.safehouseUpdateTimer+"/"+c.delayedDisconnect+"/"+GameWindow.serverDisconnected+"/"+GameWindow.kickReason+"/"+(BaseClient.connection==null)+"/"+thrown;
 }
 public static void main(String[]args){for(scenario=0;scenario<11;scenario++){
  String expected=run(new Vanilla());ApocBRClientTelemetryLite.beginFrame(true);String actual=run(new Patched());ApocBRClientTelemetryLite.endFrame(1,2);
  if(!expected.equals(actual))throw new AssertionError(scenario+" "+expected+" != "+actual);
 }ApocBRClientTelemetryLite.shutdown();System.out.println("Actual update orchestration preserves loading, connected, disconnect, coop, exception and maintenance branches");}
}
'''
        # Complete the diagnostic-only external logger surface used by the disconnect branch.
        source=source.replace('static final DebugType Network=new DebugType(),General=new DebugType();',
                              'static final DebugType Network=new DebugType(),General=new DebugType();static DebugMultiplayer Multiplayer=new DebugMultiplayer();')
        source=source.replace('ORIGINAL',method(original,'public void update()')).replace('PATCHED',method(patched,'public void update()'))
        with tempfile.TemporaryDirectory(prefix='apocbr-net-update-') as temp:
            work=pathlib.Path(temp);harness=work/'NetworkUpdateHarness.java';harness.write_text(source)
            subprocess.run([str(JDK/'javac.exe'),'--release','25','-d',str(work),str(ROOT/'src/zombie/ApocBRClientTelemetryLite.java'),str(harness)],check=True)
            for enabled in ('false','true'):
                subprocess.run([str(JDK/'java.exe'),f'-Dapocbr.clientTelemetry.enabled={enabled}',
                    f'-Dapocbr.clientTelemetry.path={work/"capture.ndjson"}','-cp',str(work),'NetworkUpdateHarness'],check=True)

    def test_vanilla_call_sites_preserved(self):
        for relative in ('network/GameClient.java', 'network/PacketTypes.java'):
            original = (ROOT/'decompiled/zombie'/relative).read_text()
            patched = (ROOT/'src/zombie'/relative).read_text()
            patched = '\n'.join(line for line in patched.splitlines() if 'apoc' not in line
                                and 'zombie.ApocBRClientTelemetryLite.' not in line)
            # The added catch clause is purely rethrow/telemetry; compare actual game calls and control predicates.
            calls = lambda text: re.findall(r'\b([\w.]+)\s*\(', text)
            self.assertEqual(calls(original), calls(patched), relative)
        # Ensure the client probes never drift into server/loading dispatch paths.
        original=(ROOT/'decompiled/zombie/network/PacketTypes.java').read_text()
        patched=(ROOT/'src/zombie/network/PacketTypes.java').read_text()
        for signature in ('public void onServerPacket(', 'public boolean onClientLoadingPacket('):
            self.assertEqual(method(original,signature),method(patched,signature))

    def test_packet_order_delay_errors_and_stages(self):
        client = {kind: (ROOT/kind/'zombie/network/GameClient.java').read_text() for kind in ('src','decompiled')}
        types = {kind: (ROOT/kind/'zombie/network/PacketTypes.java').read_text() for kind in ('src','decompiled')}
        source = r'''
import java.util.*;
import java.nio.*;
import zombie.ApocBRClientTelemetryLite;
public class NetworkDispatchHarness {
 static int scenario;static ArrayList<String>events=new ArrayList<>();
 static class ByteBufferReader {
  final ByteBuffer b=ByteBuffer.allocate(16);int position(){return b.position();}void position(int p){b.position(p);}int remaining(){return b.remaining();}int limit(){return b.limit();}
 }
 static class Connection {INetworkPacket packet;INetworkPacket getPacket(BaseType type){events.add("lookup");return packet;}}
 interface INetworkPacket {
  boolean isPostponed();boolean shouldInstantiate();void parseClient(ByteBufferReader b,Connection c)throws Exception;
  boolean isConsistent(Connection c);String getDescription();void postpone();void processClient(Connection c);
 }
 public static class Packet implements INetworkPacket {
  public Packet(){events.add("construct");}
  public boolean isPostponed(){events.add("postponed?");return scenario==4;}
  public boolean shouldInstantiate(){events.add("instantiate?");return scenario==3;}
  public void parseClient(ByteBufferReader b,Connection c)throws Exception{events.add("parse");b.b.get();if(scenario==5)throw new Exception("parse failure");if(scenario==10)throw new AssertionError("parse error");}
  public boolean isConsistent(Connection c){events.add("validate");return scenario!=2;}
  public String getDescription(){return "unit";}public void postpone(){events.add("postpone");}
  public void processClient(Connection c){events.add("apply");if(scenario==6)throw new IllegalStateException("apply failure");}
 }
 interface Legacy {void call(ByteBufferReader b,short id)throws Exception;}
 static abstract class BaseType {
  int handlingType=2;Class<? extends INetworkPacket>handler=Packet.class;
  Legacy clientHandler=(b,id)->{events.add("legacy");b.b.get();};
  short getId(){return 23;}String name(){return "Unit\"Packet";}boolean isLogEnabled(){return scenario==1;}
  void logPacket(Connection c){events.add("log");}public abstract void onClientPacket(ByteBufferReader b)throws Exception;
 }
 static class VanillaType extends BaseType { ORIGINAL_TYPE }
 static class PatchedType extends BaseType { PATCHED_TYPE }
 static class NetworkStatistic {
  static final NetworkStatistic INSTANCE=new NetworkStatistic();static NetworkStatistic getInstance(){return INSTANCE;}
  void addIncomePacket(short id,int size,Connection c){events.add("statistics");}
 }
 static class EventManager {static EventManager instance(){return new EventManager();}void report(String s){events.add("inconsistent");}}
 static class DebugOptions {
  static final DebugOptions instance=new DebugOptions();final DebugOptions network=this,client=this,mainLoop=this;
  boolean getValue(){return scenario!=9;}
 }
 enum LogSeverity {Error}
 static class DebugType {static final DebugType Network=new DebugType();void printException(Exception e,String s,LogSeverity l){events.add("caught:"+e.getClass().getSimpleName());}}
 static class ZomboidNetData {BaseType type;ByteBufferReader buffer=new ByteBufferReader();long time=System.currentTimeMillis()-50;}
 static class ZomboidNetDataPool {
  static final ZomboidNetDataPool instance=new ZomboidNetDataPool();
  void discard(ZomboidNetData d){events.add("discard");d.type=null;d.buffer.b.clear();if(scenario==11)throw new IllegalStateException("pool failure");}
 }
 static abstract class GameClient {
  static Connection connection=new Connection();boolean delayPacket;
  static final ArrayList<ZomboidNetData>DelayedCoopNetData=new ArrayList<>();
  INTERNAL
  abstract void run(ZomboidNetData d);
 }
 static class VanillaClient extends GameClient { ORIGINAL_CLIENT void run(ZomboidNetData d){mainLoopDealWithNetData(d);} }
 static class PatchedClient extends GameClient { PATCHED_CLIENT void run(ZomboidNetData d){mainLoopDealWithNetData(d);} }
 static String execute(boolean patched) {
  events.clear();GameClient.DelayedCoopNetData.clear();GameClient.connection.packet=new Packet();events.clear();
  BaseType type=patched?new PatchedType():new VanillaType();
  if(scenario==7)type.handler=null;if(scenario==8)type.handlingType=0;
  if(scenario==12){type.handler=null;type.clientHandler=(b,id)->{events.add("delay");current.delayPacket=true;b.b.get();};}
  ZomboidNetData data=new ZomboidNetData();data.type=scenario==13?null:type;
  GameClient client=patched?new PatchedClient():new VanillaClient();current=client;
  String thrown="";try{client.run(data);}catch(Throwable e){thrown=e.getClass().getSimpleName()+":"+e.getMessage();}
  return events+"/"+data.buffer.position()+"/"+(data.type==null)+"/"+client.delayPacket+"/"+GameClient.DelayedCoopNetData.size()+"/"+thrown;
 }
 static GameClient current;
 public static void main(String[]args)throws Exception {
  for(scenario=0;scenario<14;scenario++) {
   String expected=execute(false);ApocBRClientTelemetryLite.beginFrame(true);
   String actual=execute(true);ApocBRClientTelemetryLite.endFrame(1,2);
   if(!expected.equals(actual))throw new AssertionError(scenario+" "+expected+" != "+actual);
  }
  ApocBRClientTelemetryLite.shutdown();
  System.out.println("Actual client packet stages/dispatch preserve cache lookup, reflection, logging, delay/retry, errors, buffer rewind and pool cleanup");
 }
}
'''
        source = source.replace('INTERNAL', method(client['decompiled'], 'private void mainLoopHandlePacketInternal(').replace('private void','void',1))
        for token, kind in [('ORIGINAL','decompiled'), ('PATCHED','src')]:
            source = source.replace(token+'_TYPE', method(types[kind], 'public void onClientPacket('))
            source = source.replace(token+'_CLIENT', method(client[kind], 'private void mainLoopDealWithNetData('))
        with tempfile.TemporaryDirectory(prefix='apocbr-net-') as temp:
            work=pathlib.Path(temp);harness=work/'NetworkDispatchHarness.java';harness.write_text(source)
            subprocess.run([str(JDK/'javac.exe'),'--release','25','-d',str(work),str(ROOT/'src/zombie/ApocBRClientTelemetryLite.java'),str(harness)],check=True)
            for enabled in ('false','true'):
                output=work/f'{enabled}.ndjson'
                subprocess.run([str(JDK/'java.exe'),f'-Dapocbr.clientTelemetry.enabled={enabled}',
                    '-Dapocbr.clientTelemetry.slowFrameMs=1',f'-Dapocbr.clientTelemetry.path={output}',
                    '-cp',str(work),'NetworkDispatchHarness'],check=True)
                if enabled=='false':
                    self.assertFalse(output.exists());continue
                windows=[json.loads(line) for line in output.read_text().splitlines()]
                self.assertEqual(len(windows),1)
                types=windows[0]['network']['types'];packet=types['Unit"Packet']
                self.assertEqual(packet['dispatch']['count'],13)
                self.assertEqual(packet['payloadBytes'],13*16)
                self.assertEqual(packet['delayed'],1)
                self.assertEqual(packet['errors'],4)
                self.assertEqual(packet['freshInstances'],2)
                self.assertGreater(packet['stages']['FACTORY']['count'],0)
                self.assertGreater(packet['stages']['PARSE']['count'],0)
                self.assertEqual(packet['stages']['POSTPONE']['count'],1)
                self.assertEqual(types['<unknown>']['dispatch']['count'],1)


if __name__=='__main__':unittest.main()
