"""42.22.1 production LOS methods: detached ally coordinates versus native target membership."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
def block(s,signature):
 start=s.index(signature);at=s.index('{',start);depth=1;end=at+1
 while depth:
  depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]
def main():
 source=(ROOT/'42.22.1/src/zombie/apocalipse/humans/ServerHumanPerception.java').read_text(encoding='utf-8')
 methods='\n'.join(block(source,signature) for signature in ['boolean visible(IsoGameCharacter body)','boolean visibleAlly(IsoGameCharacter body)','private boolean visibleCoordinates(IsoGameCharacter body)'])
 fixture='''public class DetachedLosTest {
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static class Cell {int missing=-999;Object getGridSquare(int x,int y,int z){return x==missing?null:this;}}
 static class IsoGameCharacter {int x,y,z;Object square;final Cell cell=new Cell();int getXi(){return x;}int getYi(){return y;}int getZi(){return z;}Object getCurrentSquare(){return square;}Cell getCell(){return cell;}}
 static class Profile {boolean los=true;boolean requireLos(){return los;}}
 static class Human {final Profile profile=new Profile();final IsoGameCharacter player=new IsoGameCharacter();}
 static class LosUtil {enum TestResults {Clear,Blocked,ClearThroughClosedDoor}static TestResults result=TestResults.Clear;
 static TestResults lineClear(Cell c,int x,int y,int z,int tx,int ty,int tz,boolean flag){return result;}}
 final Human human=new Human();
 '''+methods+'''
 public static void main(String[] args){var perception=new DetachedLosTest();var ally=new IsoGameCharacter();ally.x=4;
 check(perception.visibleAlly(ally),"detached ally supplies LOS despite null native square");
 check(!perception.visible(ally),"ordinary native visibility still requires target membership");
 ally.square=new Object();check(perception.visible(ally),"native target visibility retains its loaded LOS path");ally.square=null;
 perception.human.player.cell.missing=2;check(!perception.visibleAlly(ally),"missing intermediate tile fails closed");
 perception.human.player.cell.missing=-999;LosUtil.result=LosUtil.TestResults.ClearThroughClosedDoor;
 check(!perception.visibleAlly(ally),"closed door excludes support");LosUtil.result=LosUtil.TestResults.Blocked;
 check(!perception.visibleAlly(ally),"wall excludes support");LosUtil.result=LosUtil.TestResults.Clear;
 ally.z=1;check(!perception.visibleAlly(ally),"other floor excludes support");ally.z=0;
 ally.x=33;check(!perception.visibleAlly(ally),"LOS query bounded before long tile scans");
 ally.x=4;perception.human.player.cell.missing=0;check(!perception.visibleAlly(ally),"unloaded observer fails closed");
 System.out.println("Detached squad LOS: "+checks+" checks passed (production 42.22.1 methods)");
 }}'''
 with tempfile.TemporaryDirectory(prefix='npc_detached_los_') as folder:
  work=Path(folder);file=work/'DetachedLosTest.java';file.write_text(fixture,encoding='utf-8')
  subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(work),str(file)],check=True)
  subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work),'DetachedLosTest'],check=True)
if __name__=='__main__':main()
