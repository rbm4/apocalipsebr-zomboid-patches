"""Target damage packet + real native ConnectionPlayer/Player + production ownership guard."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def fixture_files():
    files = {
        "zombie/characters/Capability.java": "package zombie.characters;public enum Capability {LoginOnServer}",
        "zombie/characters/IsoPlayer.java": """package zombie.characters;import java.nio.*;import java.io.*;public class IsoPlayer {
          public short id;public int index,weight,damage;public float sickness;public boolean local=true;
          public short getOnlineID(){return id;}public int getIndex(){return index;}public boolean isLocalPlayer(){return local;}
          public float getX(){return 0;}public float getY(){return 0;}public float getZ(){return 0;}
          public int getMaxWeight(){return weight;}public void setMaxWeight(int v){weight=v;}public float getCorpseSicknessRate(){return sickness;}public void setCorpseSicknessRate(float v){sickness=v;}
          public Damage getBodyDamage(){return new Damage();}public class Damage{public void save(ByteBuffer b)throws IOException{b.putInt(damage);}public void load(ByteBuffer b,int version)throws IOException{damage=b.getInt();}}} """,
        "zombie/core/network/ByteBufferReader.java": "package zombie.core.network;import java.nio.*;public class ByteBufferReader {public ByteBuffer bb;public ByteBufferReader(ByteBuffer b){bb=b;}public short getShort(){return bb.getShort();}public int getInt(){return bb.getInt();}public float getFloat(){return bb.getFloat();}public boolean getBoolean(){return bb.get()!=0;}}",
        "zombie/core/network/ByteBufferWriter.java": "package zombie.core.network;import java.nio.*;public class ByteBufferWriter {public ByteBuffer bb;public ByteBufferWriter(ByteBuffer b){bb=b;}public void putShort(short v){bb.putShort(v);}public void putInt(int v){bb.putInt(v);}public void putFloat(float v){bb.putFloat(v);}}",
        "zombie/network/IConnection.java": "package zombie.network;import zombie.characters.IsoPlayer;public interface IConnection{IsoPlayer getPlayerAt(int n);boolean hasPlayer(short id);String getUserName();}",
        "zombie/network/GameServer.java": "package zombie.network;import zombie.characters.IsoPlayer;public class GameServer {public static boolean server=true;public static IsoPlayer getPlayerFromConnection(IConnection c,int n){return c.getPlayerAt(n);}}",
        "zombie/network/GameClient.java": "package zombie.network;public class GameClient{public static boolean client;public static java.util.Map<Short,zombie.characters.IsoPlayer> IDToPlayerMap=new java.util.HashMap<>();}",
        "zombie/network/JSONField.java": "package zombie.network;public @interface JSONField{}",
        "zombie/network/PacketSetting.java": "package zombie.network;public @interface PacketSetting{int ordering();int priority();int reliability();zombie.characters.Capability requiredCapability();int handlingType();}",
        "zombie/network/packets/INetworkPacket.java": "package zombie.network.packets;public interface INetworkPacket {void setData(Object... v);void write(zombie.core.network.ByteBufferWriter b);void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c);boolean isConsistent(zombie.network.IConnection c);}",
        "zombie/network/fields/INetworkPacketField.java": "package zombie.network.fields;public interface INetworkPacketField {default void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){}default void write(zombie.core.network.ByteBufferWriter b){}default boolean isConsistent(zombie.network.IConnection c){return true;}}",
        "zombie/network/fields/IPositional.java": "package zombie.network.fields;public interface IPositional{float getX();float getY();float getZ();}",
        "zombie/debug/LogSeverity.java": "package zombie.debug;public enum LogSeverity {Error}",
        "zombie/debug/DebugType.java": "package zombie.debug;public class DebugType {public static final DebugType Multiplayer=new DebugType();public void error(String s,Object... args){}public void printException(Exception e,String s,LogSeverity l){}}",
        "zombie/debug/DebugLog.java": "package zombie.debug;public class DebugLog {public static void log(DebugType t,String s){}}",
        "zombie/iso/IsoWorld.java": "package zombie.iso;public class IsoWorld {public static int getWorldVersion(){return 251;}}",
        "DamageTest.java": r'''
import java.nio.*;import zombie.characters.*;import zombie.network.*;import zombie.core.network.*;import zombie.network.packets.character.PlayerDamagePacket;
class DamageTest {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static class Connection implements IConnection {IsoPlayer[] players=new IsoPlayer[4];boolean claims=true;public IsoPlayer getPlayerAt(int n){return players[n];}public boolean hasPlayer(short id){for(var p:players)if(p!=null&&p.id==id)return claims;return false;}public String getUserName(){return "fixture";}}
 static ByteBuffer payload(int id){return ByteBuffer.allocate(14).putShort((short)id).putInt(30).putFloat(.25f).putInt(77).flip();}
 public static void main(String[] args){
  var connection=new Connection();var packet=new PlayerDamagePacket();var p=new IsoPlayer();p.id=50;connection.players[2]=p;
  ByteBuffer b=payload(2);packet.parse(new ByteBufferReader(b),connection);check(p.weight==30&&p.sickness==.25f&&p.damage==77);check(b.position()==14);
  for(int index:new int[]{-1,0,1,3,4,50,1024}){p.weight=0;b=payload(index);packet.parse(new ByteBufferReader(b),connection);check(p.weight==0);check(b.position()==2);}
  connection.claims=false;b=payload(2);packet.parse(new ByteBufferReader(b),connection);check(p.weight==0);check(b.position()==2);connection.claims=true;
  b=payload(2);packet.parse(new ByteBufferReader(b),null);check(p.weight==0&&b.position()==2);
  var other=new IsoPlayer();other.id=50;connection.players[2]=other;packet.parse(new ByteBufferReader(payload(2)),connection);check(other.weight==30&&p.weight==0);
  GameServer.server=false;GameClient.client=true;GameClient.IDToPlayerMap.put(p.id,p);b=payload(p.id);packet.parse(new ByteBufferReader(b),null);check(p.weight==30&&p.damage==77);check(b.position()==14);
  p.index=2;packet.setData(p);b=ByteBuffer.allocate(14);packet.write(new ByteBufferWriter(b));b.flip();check(b.getShort()==2);check(b.getInt()==30);check(b.getFloat()==.25f);check(b.getInt()==77);
  System.out.println("PlayerDamagePacket native-field/ownership: "+checks+" assertions passed");
 }
}
''',
    }
    return files


def main():
    files = fixture_files()
    with tempfile.TemporaryDirectory(prefix="pz-damage-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/network/packets/character/PlayerDamagePacket.java"
        p.parent.mkdir(parents=True)
        p.write_bytes((V / "decompiled/zombie/network/packets/character/PlayerDamagePacket.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-34-player-damage-packet.diff")], cwd=work, check=True)
        applied = V / "src/zombie/network/packets/character/PlayerDamagePacket.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == p.read_text(encoding="utf-8-sig")
        sources = [str(p), str(V / "src/zombie/network/ApocBRPacketOwnershipGuard.java")]
        sources += [str(V / "decompiled/zombie/network/fields/character" / name) for name in ["Player.java", "ConnectionPlayer.java"]]
        for relative, content in files.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(str(path))
        subprocess.run(["javac", "-d", str(work), *sources], check=True)
        subprocess.run(["java", "-cp", str(work), "DamageTest"], check=True)


if __name__ == "__main__":
    main()
