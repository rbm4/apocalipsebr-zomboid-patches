"""Target stats packet with real ConnectionPlayer/Player and actual ownership guard."""
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
    player = files["zombie/characters/IsoPlayer.java"].rstrip()
    needle = "public void load(ByteBuffer b,int version)throws IOException{damage=b.getInt();}"
    assert needle in player
    player = player.replace(needle, needle + "public void saveMainFields(ByteBuffer b)throws IOException{b.putInt(damage);}public void loadMainFields(ByteBuffer b,int version)throws IOException{damage=b.getInt();}")
    player = player[:-1] + """
      public static class Stats {public float value;public int version;public void save(ByteBuffer b)throws IOException{b.putFloat(value);}public void load(ByteBuffer b,int v)throws IOException{version=v;value=b.getFloat();}public void load(ByteBuffer b)throws IOException{value=b.getFloat();}}
      public Stats stats=new Stats(),nutrition=new Stats();public float smoke;
      public Stats getStats(){return stats;}public Stats getNutrition(){return nutrition;}public float getTimeSinceLastSmoke(){return smoke;}public void setTimeSinceLastSmoke(float v){smoke=v;}}
    """
    files["zombie/characters/IsoPlayer.java"] = player
    files["StatsTest.java"] = r'''
import java.nio.*;import zombie.characters.*;import zombie.network.*;import zombie.core.network.*;import zombie.network.packets.character.PlayerStatsPacket;
class StatsTest {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static class Connection implements IConnection {IsoPlayer[] players=new IsoPlayer[4];boolean claims=true;public IsoPlayer getPlayerAt(int n){return players[n];}public boolean hasPlayer(short id){for(var p:players)if(p!=null&&p.id==id)return claims;return false;}public String getUserName(){return "fixture";}}
 static ByteBuffer payload(int id){return ByteBuffer.allocate(18).putShort((short)id).putFloat(.1f).putFloat(.2f).putFloat(.3f).putInt(77).flip();}
 static void values(IsoPlayer p){check(p.stats.value==.1f);check(p.nutrition.value==.2f);check(p.smoke==.3f);check(p.damage==77);check(p.stats.version==251);}
 public static void main(String[] args){
  Connection connection=new Connection();PlayerStatsPacket packet=new PlayerStatsPacket();
  for(int slot=0;slot<4;slot++){var p=new IsoPlayer();p.id=(short)(50+slot);p.index=slot;connection.players[slot]=p;var b=payload(slot);packet.parse(new ByteBufferReader(b),connection);values(p);check(b.position()==18);}
  var p=connection.players[2];p.smoke=0;
  for(int slot:new int[]{-1,4,50,1024}){var b=payload(slot);packet.parse(new ByteBufferReader(b),connection);check(p.smoke==0&&b.position()==2);}
  connection.claims=false;var b=payload(2);packet.parse(new ByteBufferReader(b),connection);check(p.smoke==0&&b.position()==2);connection.claims=true;
  b=payload(2);packet.parse(new ByteBufferReader(b),null);check(p.smoke==0&&b.position()==2);
  GameServer.server=false;GameClient.client=true;GameClient.IDToPlayerMap.put(p.id,p);b=payload(p.id);packet.parse(new ByteBufferReader(b),null);values(p);check(b.position()==18);
  packet.setData(p);b=ByteBuffer.allocate(18);packet.write(new ByteBufferWriter(b));check(b.position()==18);b.flip();check(b.getShort()==2);check(b.getFloat()==.1f);check(b.getFloat()==.2f);check(b.getFloat()==.3f);check(b.getInt()==77);
  System.out.println("PlayerStatsPacket native-field/ownership: "+checks+" assertions passed");
 }
}
'''
    with tempfile.TemporaryDirectory(prefix="pz-stats-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/network/packets/character/PlayerStatsPacket.java"
        p.parent.mkdir(parents=True)
        p.write_bytes((V / "decompiled/zombie/network/packets/character/PlayerStatsPacket.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-36-player-stats-packet.diff")], cwd=work, check=True)
        applied = V / "src/zombie/network/packets/character/PlayerStatsPacket.java"
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
        subprocess.run(["java", "-cp", str(work), "StatsTest"], check=True)


if __name__ == "__main__":
    main()
