"""Review37 online-target ownership fixture; native capability manager is not executed."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_player_damage_port import fixture_files

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    files = fixture_files()
    del files["DamageTest.java"]
    files["zombie/UsedFromLua.java"] = "package zombie;public @interface UsedFromLua{}"
    files["zombie/characters/Capability.java"] = "package zombie.characters;public enum Capability {LoginOnServer,CanModifyBodyStats}"
    files["zombie/characters/CharacterStat.java"] = "package zombie.characters;public enum CharacterStat {ONE,TWO,THREE;public static final CharacterStat[] ORDERED_STATS=values();}"
    player = files["zombie/characters/IsoPlayer.java"].rstrip()[:-1]
    player += """public static class Stats {public float[] values=new float[3];public float nutrition;public void parse(ByteBuffer b,byte i){values[i]=b.getFloat();}public void write(ByteBuffer b,byte i){b.putFloat(values[i]);}public void load(ByteBuffer b){nutrition=b.getFloat();}public void save(ByteBuffer b){b.putFloat(nutrition);}}
      public Stats stats=new Stats(),nutrition=new Stats();public Stats getStats(){return stats;}public Stats getNutrition(){return nutrition;}}"""
    files["zombie/characters/IsoPlayer.java"] = player
    files["zombie/network/GameServer.java"] = files["zombie/network/GameServer.java"].replace("public static boolean server=true;", "public static boolean server=true;public static java.util.Map<Short,IsoPlayer> IDToPlayerMap=new java.util.HashMap<>();")
    files["zombie/debug/DebugType.java"] = files["zombie/debug/DebugType.java"].replace("public void error", "public void warn(String s){}public void printStackTrace(){}public void error")
    files["zombie/network/packets/INetworkPacket.java"] = files["zombie/network/packets/INetworkPacket.java"].replace("boolean isConsistent(zombie.network.IConnection c);", "default boolean isConsistent(zombie.network.IConnection c){return true;}")
    files["SyncTest.java"] = r'''
import java.nio.*;import zombie.characters.*;import zombie.network.*;import zombie.core.network.*;import zombie.network.packets.SyncPlayerStatsPacket;
class SyncTest {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static class Connection implements IConnection {IsoPlayer[] players=new IsoPlayer[4];boolean claims=true;public IsoPlayer getPlayerAt(int n){return players[n];}public boolean hasPlayer(short id){for(var p:players)if(p!=null&&p.id==id)return claims;return false;}public String getUserName(){return "fixture";}}
 static ByteBuffer stats(int id){return ByteBuffer.allocate(14).putShort((short)id).putInt(5).putFloat(1.25f).putFloat(3.5f).flip();}
 static ByteBuffer nutrition(int id){return ByteBuffer.allocate(10).putShort((short)id).putInt(-1).putFloat(2.5f).flip();}
 public static void main(String[] args){
  var c=new Connection();var p=new IsoPlayer();p.id=50;p.index=2;c.players[2]=p;GameServer.IDToPlayerMap.put(p.id,p);var packet=new SyncPlayerStatsPacket();
  var b=stats(50);packet.parse(new ByteBufferReader(b),c);check(p.stats.values[0]==1.25f);check(p.stats.values[1]==0);check(p.stats.values[2]==3.5f);check(b.position()==14);
  packet.parse(new ByteBufferReader(nutrition(50)),c);check(p.nutrition.nutrition==2.5f);check(SyncPlayerStatsPacket.getBitMaskForStat(CharacterStat.THREE)==4);
  var other=new IsoPlayer();other.id=51;GameServer.IDToPlayerMap.put(other.id,other);
  b=stats(51);packet.parse(new ByteBufferReader(b),c);check(other.stats.values[0]==0);check(b.position()==6); // Even a capability-authorized admin would still face this owner guard.
  for(int id:new int[]{-1,999,1024}){b=stats(id);packet.parse(new ByteBufferReader(b),c);check(b.position()==6);}
  p.stats.values[0]=0;c.claims=false;b=stats(50);packet.parse(new ByteBufferReader(b),c);check(p.stats.values[0]==0&&b.position()==6);c.claims=true;
  b=stats(50);packet.parse(new ByteBufferReader(b),null);check(p.stats.values[0]==0&&b.position()==6);
  GameServer.server=false;GameClient.client=true;GameClient.IDToPlayerMap.put(other.id,other);b=stats(51);packet.parse(new ByteBufferReader(b),null);check(other.stats.values[0]==1.25f&&b.position()==14);
  b=stats(999);packet.parse(new ByteBufferReader(b),null);check(b.position()==6); // Native missing-player branch retained.
  packet.setData(other,5);b=ByteBuffer.allocate(14);packet.write(new ByteBufferWriter(b));b.flip();check(b.getShort()==51);check(b.getInt()==5);check(b.getFloat()==1.25f);check(b.getFloat()==3.5f);
  packet.setData(other,-1);other.nutrition.nutrition=2.5f;b=ByteBuffer.allocate(10);packet.write(new ByteBufferWriter(b));b.flip();check(b.getShort()==51&&b.getInt()==-1&&b.getFloat()==2.5f);
  System.out.println("SyncPlayerStats online-target/ownership: "+checks+" assertions passed");
 }
}
'''
    with tempfile.TemporaryDirectory(prefix="pz-sync-stats-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/network/packets/SyncPlayerStatsPacket.java"
        p.parent.mkdir(parents=True)
        p.write_bytes((V / "decompiled/zombie/network/packets/SyncPlayerStatsPacket.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-37-sync-player-stats.diff")], cwd=work, check=True)
        applied = V / "src/zombie/network/packets/SyncPlayerStatsPacket.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == p.read_text(encoding="utf-8-sig")
        sources = [str(p), str(V / "src/zombie/network/ApocBRPacketOwnershipGuard.java")]
        sources += [str(V / "decompiled/zombie/network/fields/character" / name) for name in ["Player.java", "OnlinePlayer.java"]]
        for relative, content in files.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(str(path))
        subprocess.run(["javac", "-d", str(work), *sources], check=True)
        subprocess.run(["java", "-cp", str(work), "SyncTest"], check=True)


if __name__ == "__main__":
    main()
