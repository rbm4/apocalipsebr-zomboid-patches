"""Compile the production guard and PlayerStats parser; verify receive direction,
online-ID sentinel, spoof rejection, and actual client stat deserialization."""
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[1]
FILES={
"zombie/characters/IsoPlayer.java": r"""package zombie.characters; import java.nio.ByteBuffer;import java.io.IOException;
public class IsoPlayer {public short id;public float value;public IsoPlayer(short id){this.id=id;}public short getOnlineID(){return id;}public Stats getStats(){return new Stats();}public Stats getNutrition(){return new Stats();}public Damage getBodyDamage(){return new Damage();}public float getTimeSinceLastSmoke(){return 0;}public void setTimeSinceLastSmoke(float v){}public class Stats {public void load(ByteBuffer b,int v)throws IOException{value=b.getFloat();}public void load(ByteBuffer b)throws IOException{b.getFloat();}public void save(ByteBuffer b)throws IOException{b.putFloat(value);}}public class Damage {public void saveMainFields(ByteBuffer b)throws IOException{b.putFloat(0);}public void loadMainFields(ByteBuffer b,int v)throws IOException{b.getFloat();}}} """,
"zombie/characters/Capability.java":"package zombie.characters;public enum Capability {LoginOnServer}",
"zombie/network/IConnection.java":"package zombie.network;import zombie.characters.IsoPlayer;public interface IConnection {boolean hasPlayer(short id);String getUserName();IsoPlayer getPlayerAt(int slot);}",
"zombie/network/GameServer.java":"package zombie.network;import zombie.characters.IsoPlayer;public class GameServer {public static boolean server;public static final java.util.Map<Short,IsoPlayer> players=new java.util.HashMap<>();public static IsoPlayer getPlayerFromConnection(IConnection c,int i){return c.getPlayerAt(i);}}",
"zombie/debug/DebugLog.java":"package zombie.debug;public class DebugLog {public static int rejections;public static void log(DebugType t,String s){rejections++;}}",
"zombie/debug/DebugType.java":"package zombie.debug;public class DebugType {public static final DebugType Multiplayer=new DebugType();public void printException(Exception e,String m,LogSeverity s){throw new AssertionError(e);}}",
"zombie/debug/LogSeverity.java":"package zombie.debug;public enum LogSeverity {Error}",
"zombie/network/PacketSetting.java":"package zombie.network;import zombie.characters.Capability;public @interface PacketSetting {int ordering();int priority();int reliability();Capability requiredCapability();int handlingType();}",
"zombie/iso/IsoWorld.java":"package zombie.iso;public class IsoWorld {public static int getWorldVersion(){return 0;}}",
"zombie/core/network/ByteBufferReader.java":"package zombie.core.network;public class ByteBufferReader {public final java.nio.ByteBuffer bb;public ByteBufferReader(java.nio.ByteBuffer b){bb=b;}public float getFloat(){return bb.getFloat();}}",
"zombie/core/network/ByteBufferWriter.java":"package zombie.core.network;public class ByteBufferWriter {public java.nio.ByteBuffer bb;public void putFloat(float v){bb.putFloat(v);}}",
"zombie/network/packets/INetworkPacket.java":"package zombie.network.packets;import zombie.network.*;import zombie.core.network.*;public interface INetworkPacket {void setData(Object...v);void write(ByteBufferWriter b);void parse(ByteBufferReader b,IConnection c);}",
"zombie/network/fields/character/PlayerID.java":r"""package zombie.network.fields.character;import zombie.network.*;import zombie.characters.IsoPlayer;import zombie.core.network.*;public class PlayerID {IsoPlayer player;short id;byte index;public void set(IsoPlayer p){player=p;id=p.id;index=-1;}public void write(ByteBufferWriter b){b.bb.putShort(id).put(index);}public void parse(ByteBufferReader b,IConnection c){id=b.bb.getShort();index=b.bb.get();player=GameServer.server && c!=null && index!=-1 && index>=0 && index<4?c.getPlayerAt(index):GameServer.players.get(id);}public short getID(){return id;}public byte getPlayerIndex(){return index;}public IsoPlayer getPlayer(){return player;}public boolean isConsistent(IConnection c){return player!=null;}}""",
"GuardTest.java":r"""import zombie.network.*;import zombie.characters.IsoPlayer;import zombie.debug.DebugLog;import zombie.network.packets.character.PlayerStatsPacket;import zombie.core.network.ByteBufferReader;import java.nio.ByteBuffer;
public class GuardTest {
 static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static class Connection implements IConnection {IsoPlayer[] slots=new IsoPlayer[4];boolean claims=true;public IsoPlayer getPlayerAt(int i){return slots[i];}public String getUserName(){return "fixture";}public boolean hasPlayer(short id){if(!claims)return false;for(IsoPlayer p:slots)if(p!=null&&p.id==id)return true;return false;}}
 static boolean validate(Connection c,short id,byte slot,IsoPlayer p){return ApocBRPacketOwnershipGuard.validate("PlayerStats",c,id,slot,p);}
 static void parse(IsoPlayer p,Connection c,byte slot,float value){ByteBuffer b=ByteBuffer.allocate(19);b.putShort(p.id).put(slot).putFloat(value).putFloat(0).putFloat(0).putFloat(0).flip();new PlayerStatsPacket().parse(new ByteBufferReader(b),c);}
 public static void main(String[]a){
  IsoPlayer p=new IsoPlayer((short)10),other=new IsoPlayer((short)11),impostor=new IsoPlayer((short)10);Connection c=new Connection();GameServer.players.put(p.id,p);GameServer.players.put(other.id,other);
  GameServer.server=false;int logs=DebugLog.rejections;parse(p,null,(byte)-1,.75f);check(p.value==.75f,"client applies authoritative online-ID stats");check(DebugLog.rejections==logs,"client receipt not rejected/logged");
  for(int slot=0;slot<4;slot++){for(int j=0;j<4;j++)c.slots[j]=null;c.slots[slot]=p;GameServer.server=true;
   check(validate(c,p.id,(byte)slot,p),"owned explicit slot");check(validate(c,p.id,(byte)-1,p),"owned online-ID sentinel");
   check(!validate(c,p.id,(byte)((slot+1)%4),p),"incorrect explicit slot");check(!validate(c,other.id,(byte)-1,other),"foreign player rejected");check(!validate(c,p.id,(byte)-1,impostor),"same ID wrong instance rejected");check(!validate(c,other.id,(byte)slot,p),"claimed ID mismatch rejected");
   for(byte bad:new byte[]{-128,-2,4,127})check(!validate(c,p.id,bad,p),"invalid index rejected");
   check(!validate(null,p.id,(byte)-1,p),"missing sender rejected");check(!validate(c,p.id,(byte)-1,null),"missing resolved player rejected");c.claims=false;check(!validate(c,p.id,(byte)-1,p),"hasPlayer consistency required");c.claims=true;
  }
  p.value=.75f;GameServer.server=true;parse(other,c,(byte)-1,.9f);check(other.value==0,"spoof cannot mutate stats");
  GameServer.server=false;parse(p,c,(byte)-1,.5f);check(p.value==.5f,"same installation client patch accepts server update");
  System.out.println("Passed "+checks+" ownership and stat-deserialization assertions.");
 }
}"""
}
def main():
 with tempfile.TemporaryDirectory(prefix="apocbr-ownership-tests-") as d:
  work=Path(d);sources=[]
  for relative,content in FILES.items():
   path=work/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content);sources.append(path)
  sources.extend(ROOT/"42.21.0/src/zombie"/s for s in ("network/ApocBRPacketOwnershipGuard.java","network/packets/character/PlayerStatsPacket.java"))
  args=work/"javac.args";args.write_text("\n".join('"'+str(p).replace("\\","/")+'"' for p in sources))
  output=work/"classes";subprocess.run([str(ROOT/"jdk/bin/javac.exe"),"--release","25","-d",str(output),"@"+str(args)],check=True)
  subprocess.run([str(ROOT/"jdk/bin/java.exe"),"-cp",str(output),"GuardTest"],check=True)
if __name__=="__main__":main()
