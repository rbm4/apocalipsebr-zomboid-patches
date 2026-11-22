"""Review53: exact native player-field code with engine/network doubles."""
from pathlib import Path
import json
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"
REPORT = V / "docs/reports/2026-10-28-merge-53-npc-transport.json"

HARNESS = r'''
import java.nio.*;
import zombie.characters.IsoPlayer;
import zombie.network.*;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.core.network.*;
public class TransportFieldTest {
 static int checks;
 static void check(boolean ok){if(!ok)throw new AssertionError();checks++;}
 static ByteBuffer wire(ConnectionPlayer field){ByteBuffer b=ByteBuffer.allocate(8);field.write(new ByteBufferWriter(b));check(b.position()==2);b.flip();return b;}
 public static void main(String[] args){
  for(int id=768;id<=1023;id++){
   GameServer.server=true;GameClient.client=false;
   IsoPlayer npc=new IsoPlayer((short)id);ConnectionPlayer send=new ConnectionPlayer();send.set(npc);
   ByteBuffer b=wire(send);check(b.getShort(0)==id);
   GameServer.server=false;GameClient.client=true;GameClient.IDToPlayerMap.put((short)id,npc);
   ConnectionPlayer receive=new ConnectionPlayer();receive.parse(new ByteBufferReader(b),null);
   check(receive.getPlayer()==npc && receive.isConsistent(null));
  }
  // A forged server-bound online NPC ID must not resolve in the global pool.
  GameClient.client=false;GameServer.server=true;
  ConnectionPlayer receive=new ConnectionPlayer();
  receive.parse(new ByteBufferReader(ByteBuffer.allocate(2).putShort((short)768).flip()),index->new IsoPlayer((short)5));
  check(receive.getPlayer()==null && !receive.isConsistent(null));
  IsoPlayer owner=new IsoPlayer((short)42);
  receive.parse(new ByteBufferReader(ByteBuffer.allocate(2).putShort((short)3).flip()),index->index==3?owner:null);
  check(receive.getPlayer()==owner && receive.getId()==42);
  receive.parse(new ByteBufferReader(ByteBuffer.allocate(2).putShort((short)0).flip()),null);
  check(receive.getPlayer()==null && !receive.isConsistent(null));
  GameServer.server=false;GameClient.client=true;
  for(short id:new short[]{-1,1024}){
   GameClient.IDToPlayerMap.put(id,new IsoPlayer(id));
   receive.parse(new ByteBufferReader(ByteBuffer.allocate(2).putShort(id).flip()),null);
   check(receive.getPlayer()==null && !receive.isConsistent(null));
  }
  System.out.println("Native connection-player fields: "+checks+" checks passed (engine/network doubles)");
 }
}'''

STUBS = {
    "zombie/characters/IsoPlayer.java": "package zombie.characters; public class IsoPlayer {final short id;public IsoPlayer(short id){this.id=id;}public short getOnlineID(){return id;}public boolean isLocalPlayer(){return false;}public int getIndex(){return 0;}public float getX(){return 0;}public float getY(){return 0;}public float getZ(){return 0;}}",
    "zombie/network/GameServer.java": "package zombie.network;public class GameServer {public static boolean server;}",
    "zombie/network/GameClient.java": "package zombie.network;import java.util.*;import zombie.characters.IsoPlayer;public class GameClient {public static boolean client;public static Map<Short,IsoPlayer> IDToPlayerMap=new HashMap<>();}",
    "zombie/network/IConnection.java": "package zombie.network;import zombie.characters.IsoPlayer;public interface IConnection {IsoPlayer getPlayerAt(int index);}",
    "zombie/network/JSONField.java": "package zombie.network;public @interface JSONField {}",
    "zombie/debug/DebugType.java": "package zombie.debug;public class DebugType {public static final DebugType Multiplayer=new DebugType();public void error(String s,Object... args){}}",
    "zombie/network/fields/INetworkPacketField.java": "package zombie.network.fields;import zombie.core.network.*;import zombie.network.IConnection;public interface INetworkPacketField {void write(ByteBufferWriter b);void parse(ByteBufferReader b,IConnection c);boolean isConsistent(IConnection c);}",
    "zombie/network/fields/IPositional.java": "package zombie.network.fields;public interface IPositional {float getX();float getY();float getZ();}",
    "zombie/core/network/ByteBufferWriter.java": "package zombie.core.network;import java.nio.*;public class ByteBufferWriter {public final ByteBuffer bb;public ByteBufferWriter(ByteBuffer b){bb=b;}}",
    "zombie/core/network/ByteBufferReader.java": "package zombie.core.network;import java.nio.*;public class ByteBufferReader {final ByteBuffer bb;public ByteBufferReader(ByteBuffer b){bb=b;}public short getShort(){return bb.getShort();}public boolean getBoolean(){return bb.get()!=0;}}",
}


def main():
    record = json.loads(REPORT.read_text())
    with tempfile.TemporaryDirectory(prefix="npc_transport_") as folder:
        tmp = Path(folder)
        target = tmp / "42.22.0/src" / record["path"]
        target.parent.mkdir(parents=True)
        target.write_bytes((V / "src" / record["path"]).read_bytes())
        if not record["source_applied"]:
            subprocess.run(["git", "apply", str(REPORT.with_suffix(".diff"))], cwd=tmp, check=True)
        source = target.read_text()
        assert "packet.player.set(human.player);" in source
        assert "packet.id" not in source and "setPlayerIndex" not in source
        for rel, body in STUBS.items():
            path = tmp / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body)
        for name in ["Player", "ConnectionPlayer"]:
            rel = f"zombie/network/fields/character/{name}.java"
            path = tmp / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((V / "decompiled" / rel).read_bytes())
        (tmp / "TransportFieldTest.java").write_text(HARNESS)
        sources = [str(p) for p in tmp.rglob("*.java") if p != target]
        javac = shutil.which("javac")
        if javac is None:
            raise RuntimeError("javac required")
        subprocess.run([javac, "-d", str(tmp / "classes"), *sources], check=True)
        subprocess.run([str(Path(javac).with_name("java.exe")), "-cp", str(tmp / "classes"), "TransportFieldTest"], check=True)
        print("Candidate field source contract passed; no full packet, RakNet or live client execution")


if __name__ == "__main__":
    main()
