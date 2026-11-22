"""Review29 extracted packet population/flag checks; native wire methods preserved."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_unload_simulation_algorithms import method

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    native = (V / "decompiled/zombie/network/packets/character/ZombiePacket.java").read_text(encoding="utf-8-sig")
    with tempfile.TemporaryDirectory(prefix="pz-zombie-packet-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/network/packets/character/ZombiePacket.java"
        p.parent.mkdir(parents=True)
        p.write_text(native, encoding="utf-8", newline="\n")
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-29-zombie-packet.diff")], cwd=work, check=True)
        candidate = p.read_text(encoding="utf-8")
        applied = V / "src/zombie/network/packets/character/ZombiePacket.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate
        for signature in ["public void parse(", "public void write(ByteBufferWriter bb)", "public void copy(ZombiePacket packet)", "public int getPacketSizeBytes()"]:
            assert method(candidate, signature) == method(native, signature)
        assert "public final OnlinePlayer grappledBy = new OnlinePlayer()" in candidate
        java = r'''
import zombie.network.NetworkVariables;
class ZombiePacket {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 int id,outfitId,skinTextureIndex,target,predictionType,z,booleanVariables;byte update;
 NetworkVariables.WalkType walkType;NetworkVariables.ZombieState realState;String sharedGrappleType;
 static class IsoPlayer {}
 static class OnlinePlayer {IsoPlayer player;void set(IsoPlayer p){player=p;}}
 static class PFB {boolean canceled;boolean isCanceled(){return canceled;}}
 static class ObjectID {long id=-1;long getObjectID(){return id;}}
 OnlinePlayer grappledBy=new OnlinePlayer();PFB pfb=new PFB();ObjectID reanimatedBodyId=new ObjectID();
 static class Wrapper {Object player;Object getGrappledBy(){return player;}}
 static class Visual {int getSkinTextureIndex(){return 9;}}
 static class IsoZombie {
  int onlineId=12,mode;boolean thumpSent;Wrapper wrapper=new Wrapper();NetworkZombieAI ai=new NetworkZombieAI(this);
  int getPersistentOutfitID(){return 7;}NetworkZombieAI getNetworkCharacterAI(){return ai;}
  String getWalkType(){return "1";}Wrapper getWrappedGrappleable(){return wrapper;}String getSharedGrappleType(){return "drag";}Visual getHumanVisual(){return new Visual();}
 }
 static class Mind {int calls;void set(ZombiePacket p){calls++;}}
 static class NetworkZombieAI {
  IsoZombie zombie;int books,fields;Mind mindSync=new Mind();NetworkZombieAI(IsoZombie z){zombie=z;}
  void updatePacketBookkeeping(ZombiePacket p){books++;p.predictionType=(zombie.mode&4)==0?0:1;p.z=(zombie.mode&64)==0?0:1;}
  void setPacketFields(ZombiePacket p,String walk){fields++;p.walkType=NetworkVariables.WalkType.fromString(walk);
   p.realState=(zombie.mode&1)==0?NetworkVariables.ZombieState.Idle:NetworkVariables.ZombieState.WalkToward;
   p.target=(zombie.mode&2)==0?-1:5;p.booleanVariables=(zombie.mode&8)==0?0:1;p.pfb.canceled=(zombie.mode&16)==0;p.reanimatedBodyId.id=(zombie.mode&32)==0?-1:1;}
  void set(ZombiePacket p){updatePacketBookkeeping(p);setPacketFields(p,zombie.getWalkType());}
 }
 NATIVE
 SET
 PREPARE
 FIELDS
 public static void main(String[] args){
  for(int mask=0;mask<256;mask++){
   IsoZombie a=new IsoZombie(),b=new IsoZombie();a.mode=b.mode=mask;
   if((mask&128)!=0){a.wrapper.player=new IsoPlayer();b.wrapper.player=new IsoPlayer();}
   ZombiePacket old=new ZombiePacket(),current=new ZombiePacket();old.nativeSet(a);current.set(b);
   check(old.update==current.update);check((current.update&255)==mask);check(current.id==old.id&&current.outfitId==old.outfitId&&current.skinTextureIndex==old.skinTextureIndex);
   check(current.walkType==old.walkType);check(b.ai.books==1&&b.ai.fields==1&&b.ai.mindSync.calls==1&&b.thumpSent);
   b.ai.books=0;b.ai.fields=0;current.prepareFrame(b);current.setPrepared(b);current.setPrepared(b);check(b.ai.books==1&&b.ai.fields==2);
   b.mode=0;b.wrapper.player=null;current.set(b);check(current.update==0&&current.grappledBy.player==null);
  }
  System.out.println("ZombiePacket population/flags: "+checks+" assertions passed (256 flag combinations)");
 }
}
'''
        java = java.replace("NATIVE", method(native, "public void set(IsoZombie chr)").replace("void set(", "void nativeSet(", 1))
        for marker, signature in [("SET", "public void set(IsoZombie chr)"), ("PREPARE", "public void prepareFrame(IsoZombie chr)"), ("FIELDS", "public void setPrepared(IsoZombie chr)")]:
            java = java.replace(marker, method(candidate, signature))
        p = work / "ZombiePacket.java"
        p.write_text(java, encoding="utf-8")
        subprocess.run(["javac", "-d", str(work), str(p), str(V / "src/zombie/network/NetworkVariables.java")], check=True)
        subprocess.run(["java", "-cp", str(work), "ZombiePacket"], check=True)
        print("Native parse/write/copy/size source equality passed; actual wire serialization and native AI not executed")


if __name__ == "__main__":
    main()
