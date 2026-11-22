"""Target effects packet with native field resolution and actual ownership guard."""
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
    assert player.endswith("}")
    player = player[:-1]
    for name in ["SleepingTabletEffect", "SleepingTabletDelta", "BetaEffect", "BetaDelta", "DepressEffect", "DepressDelta", "PainEffect", "PainDelta"]:
        player += f"public float {name};public float get{name}(){{return {name};}}public void set{name}(float v){{{name}=v;}}"
    player += "public int pills;public int getSleepingPillsTaken(){return pills;}public void setSleepingPillsTaken(int n){pills=n;}}"
    files["zombie/characters/IsoPlayer.java"] = player
    files["zombie/GameTime.java"] = "package zombie;public class GameTime {public static final GameTime instance=new GameTime();public java.util.Calendar calender=java.util.Calendar.getInstance();}"
    files["EffectsTest.java"] = r'''
import java.nio.*;import zombie.characters.*;import zombie.network.*;import zombie.core.network.*;import zombie.network.packets.character.PlayerEffectsPacket;
class EffectsTest {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static class Connection implements IConnection {IsoPlayer[] players=new IsoPlayer[4];boolean claims=true;public IsoPlayer getPlayerAt(int n){return players[n];}public boolean hasPlayer(short id){for(var p:players)if(p!=null&&p.id==id)return claims;return false;}public String getUserName(){return "fixture";}}
 static ByteBuffer payload(int id){return ByteBuffer.allocate(38).putShort((short)id).putFloat(.1f).putFloat(.2f).putInt(3).putFloat(.4f).putFloat(.5f).putFloat(.6f).putFloat(.7f).putFloat(.8f).putFloat(.9f).flip();}
 static void values(IsoPlayer p){check(p.SleepingTabletEffect==.1f);check(p.SleepingTabletDelta==.2f);check(p.pills==3);check(p.BetaEffect==.4f);check(p.BetaDelta==.5f);check(p.DepressEffect==.6f);check(p.DepressDelta==.7f);check(p.PainEffect==.8f);check(p.PainDelta==.9f);}
 public static void main(String[] args){
  Connection connection=new Connection();PlayerEffectsPacket packet=new PlayerEffectsPacket();
  for(int slot=0;slot<4;slot++){var p=new IsoPlayer();p.id=(short)(50+slot);p.index=slot;connection.players[slot]=p;var b=payload(slot);packet.parse(new ByteBufferReader(b),connection);values(p);check(b.position()==38);}
  var p=connection.players[2];p.pills=0;
  for(int slot:new int[]{-1,4,50,1024}){var b=payload(slot);packet.parse(new ByteBufferReader(b),connection);check(p.pills==0&&b.position()==2);}
  connection.claims=false;var b=payload(2);packet.parse(new ByteBufferReader(b),connection);check(p.pills==0&&b.position()==2);connection.claims=true;
  b=payload(2);packet.parse(new ByteBufferReader(b),null);check(p.pills==0&&b.position()==2);
  zombie.GameTime.instance.calender=null;b=payload(2);packet.parse(new ByteBufferReader(b),connection);check(p.pills==0&&b.position()==2);zombie.GameTime.instance.calender=java.util.Calendar.getInstance();
  GameServer.server=false;GameClient.client=true;GameClient.IDToPlayerMap.put(p.id,p);b=payload(p.id);packet.parse(new ByteBufferReader(b),null);values(p);check(b.position()==38);
  packet.setData(p);b=ByteBuffer.allocate(38);packet.write(new ByteBufferWriter(b));check(b.position()==38);b.flip();check(b.getShort()==2);check(b.getFloat()==.1f);check(b.getFloat()==.2f);check(b.getInt()==3);
  System.out.println("PlayerEffectsPacket native-field/ownership: "+checks+" assertions passed");
 }
}
'''
    with tempfile.TemporaryDirectory(prefix="pz-effects-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/network/packets/character/PlayerEffectsPacket.java"
        p.parent.mkdir(parents=True)
        p.write_bytes((V / "decompiled/zombie/network/packets/character/PlayerEffectsPacket.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-35-player-effects-packet.diff")], cwd=work, check=True)
        applied = V / "src/zombie/network/packets/character/PlayerEffectsPacket.java"
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
        subprocess.run(["java", "-cp", str(work), "EffectsTest"], check=True)


if __name__ == "__main__":
    main()
