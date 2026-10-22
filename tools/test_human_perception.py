"""Production discovery, native visibility adapter and immutable contact retention."""
from pathlib import Path
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from test_server_humans import FILES, ROOT

HARNESS = r"""
package zombie.apocalipse.humans;
import java.util.*;
import zombie.apocalipse.humans.brain.*;
import static zombie.apocalipse.humans.brain.HumanSenses.*;
import zombie.apocalipse.humans.profiles.*;
import zombie.characters.*;
import zombie.iso.*;
import zombie.network.*;
import zombie.core.raknet.UdpConnection;
public class HumanPerceptionTest {
 static int checks,sequence;static final long STEP=100_000_000L;
 static void check(boolean v,String text){checks++;if(!v)throw new AssertionError(text);}
 static ServerHuman actor(IsoCell cell,String profile){return new ServerHuman(new ServerHumanDefinition("p"+(++sequence),"p"+sequence,"guard",100.5f,200.5f,0,IsoDirections.E,false,"Tourist",profile),(short)(1024+sequence),cell);}
 static IsoZombie zombie(IsoCell cell,float x,float y,int level){
  var z=new IsoZombie();z.x=x;z.y=y;z.z=level;z.online=(short)++sequence;z.instanceId=sequence;
  z.square=cell.getGridSquare(z.getXi(),z.getYi(),level);z.square.moving.add(z);cell.objects.add(z);return z;
 }
 static Contact one(Frame f){return f.contacts().stream().filter(c->c.identity().kind()==Kind.ZOMBIE).findFirst().orElseThrow();}
 static void bounded(Frame f){check(f.work().tiles()<=128&&f.work().bodies()<=256&&f.work().sightChecks()<=48&&f.work().playerCandidates()<=128,"world work stays within independent caps");}
 public static void main(String[] ignored)throws Exception{
  var id=new Identity(Kind.ZOMBIE,(short)1,10,1);var memory=new HumanContactMemory();var work=new Work(0,0,0,0,0);
  var seen=memory.update(0,List.of(new Sighting(id,1,2,0,4)),Set.of(),Coverage.COMPLETE,Coverage.COMPLETE,false,work);
  var remembered=memory.update(STEP,List.of(),Set.of(),Coverage.COMPLETE,Coverage.PARTIAL,false,work);
  check(seen.contacts().getFirst().visible()&&!remembered.contacts().getFirst().visible(),"old immutable sample remains visible while next sample holds memory");
  check(remembered.contacts().getFirst().lastSeenNanos()==0&&remembered.contacts().getFirst().x()==1,"memory never invents an updated position/time");
  check(memory.update(3_000_000_000L,List.of(),Set.of(),Coverage.COMPLETE,Coverage.UNAVAILABLE,false,work).contacts().isEmpty(),"memory expires at bounded age even while sensing unavailable");
  memory.update(0,List.of(new Sighting(id,0,0,0,0)),Set.of(),Coverage.COMPLETE,Coverage.COMPLETE,false,work);
  check(memory.update(STEP,List.of(),Set.of(id),Coverage.COMPLETE,Coverage.COMPLETE,false,work).contacts().isEmpty(),"explicit invalidation removes a contact without waiting for expiry");
  var many=new ArrayList<Sighting>();for(int n=0;n<180;n++)many.add(new Sighting(new Identity(Kind.ZOMBIE,(short)n,n,n),0,0,0,n));
  var limited=memory.update(2*STEP,many,Set.of(),Coverage.COMPLETE,Coverage.PARTIAL,false,work);
  check(limited.contacts().size()==128&&limited.contactsTruncated(),"retention is bounded and truncation explicit");
  try{limited.contacts().clear();throw new AssertionError("mutable contacts");}catch(UnsupportedOperationException expected){checks++;}

  GameServer.Players.clear();GameServer.IDToPlayerMap.clear();GameServer.udpEngine.connections.clear();
  var cell=new IsoCell();var bot=actor(cell,"roaming-greeter");var z=zombie(cell,101.5f,200.5f,0);
  var first=bot.actuator.observe(0);var contact=one(first.senses());
  check(contact.visible()&&first.visitors().isEmpty(),"zombie is a sensor contact, never a player visitor");
  check(first.senses().zombies()==Coverage.PARTIAL,"a partial spatial sweep does not report full coverage");bounded(first.senses());
  check(bot.actuator.observe(STEP/2).senses()==first.senses(),"cache preserves actual sample identity/time");
  check(bot.actuator.observe(STEP/2).nowNanos()==STEP/2&&first.senses().sampledAtNanos()==0,"decision time differs from sampled time");
  var continued=bot.actuator.observe(STEP);
  check(one(continued.senses()).identity().equals(contact.identity())&&one(continued.senses()).visible(),"stable membership retains identity and refreshes known contacts");
  LosUtil.result=LosUtil.TestResults.Blocked;z.square.moving.remove(z);z.x=102.5f;
  z.square=cell.getGridSquare(102,200,0);z.square.moving.add(z);
  var hidden=bot.actuator.observe(2*STEP);
  check(!one(hidden.senses()).visible()&&one(hidden.senses()).x()==101.5f,"occluded zombie retains last seen position, not live hidden position");
  check(hidden.senses().visible(Kind.ZOMBIE).isEmpty(),"remembered contact excluded from current visibility");
  LosUtil.result=LosUtil.TestResults.ClearThroughClosedDoor;
  check(!one(bot.actuator.observe(3*STEP).senses()).visible(),"closed door blocks sensing");
  LosUtil.result=LosUtil.TestResults.ClearThroughWindow;
  check(one(bot.actuator.observe(4*STEP).senses()).visible(),"clear window permits sight without asserting reachability");
  z.dead=true;
  check(bot.actuator.observe(5*STEP).senses().contacts().isEmpty(),"dead contact invalidated immediately on next sample");
  z.dead=false;z.square.moving.remove(z);z.x=101.5f;z.square=cell.getGridSquare(101,200,0);z.square.moving.add(z);
  bot.actuator.observe(6*STEP);var beforeReuse=one(bot.perception.snapshot()).identity();
  cell.objects.remove(z);cell.objects.add(z); // Same Java object and native IDs, new active-world membership.
  var recycled=bot.actuator.observe(7*STEP).senses();
  check(recycled.contacts().size()==1&&!one(recycled).identity().equals(beforeReuse),"pool reuse cannot inherit prior identity even with identical online and instance IDs");
  cell.objects.remove(z);
  check(bot.actuator.observe(8*STEP).senses().contacts().isEmpty(),"world removal invalidates retained target");

  var player=new IsoPlayer(cell,new SurvivorDesc(false),0,0,0);player.x=100.5f;player.y=201.5f;player.id=7;player.instanceId=77;
  GameServer.Players.add(player);GameServer.IDToPlayerMap.put(player.id,player);var connection=new UdpConnection();connection.players[0]=player;
  GameServer.owners.put(player,connection);
  GameServer.udpEngine.connections.add(connection);
  var withPlayer=bot.actuator.observe(9*STEP);
  check(withPlayer.visitors().size()==1&&withPlayer.visitors().getFirst().identity().instanceId()==77,"existing connected-player visitor adapter preserved");
  player.invisible=true;var invisible=bot.actuator.observe(10*STEP);
  check(invisible.visitors().isEmpty()&&invisible.senses().contacts().isEmpty(),"invisible players removed from current and retained contacts");
  player.invisible=false;bot.actuator.observe(11*STEP);
  check(bot.activity.beginDialogue(new HumanObservation.Identity(player.id,77),11*STEP,bot.actuator),"dialogue owns behavior");
  var during=zombie(cell,100.5f,201.5f,0);bot.perception.update(12*STEP);
  check(one(bot.perception.snapshot()).visible(),"sensing can continue while autonomous behavior is suspended");
  bot.activity.finishDialogue(13*STEP,bot.actuator);
  bot.dispose();check(!bot.perception.snapshot().sampled(),"disposal clears transient memory and native bindings");
  check(actor(cell,"roaming-greeter").perception.snapshot().contacts().isEmpty(),"another actor receives fresh contact memory");

  var denseCell=new IsoCell();var dense=actor(denseCell,"roaming-greeter");
  for(int n=0;n<170;n++)zombie(denseCell,100.5f,200.5f,0);
  var allSeen=new HashSet<Identity>();
  for(int n=0;n<50;n++){var f=dense.actuator.observe(n*STEP).senses();bounded(f);allSeen.addAll(f.contacts().stream().map(Contact::identity).toList());check(f.contacts().size()<=128,"dense memory cap");}
  check(allSeen.size()>64,"crowded-tile cursor advances beyond the first body budget");
  check(dense.perception.snapshot().zombies()==Coverage.PARTIAL,"crowd/candidate truncation reported as partial");

  var sparseCell=new IsoCell();sparseCell.strict=true;
  for(int x=98;x<=102;x++)for(int y=198;y<=202;y++)sparseCell.tiles.put(x+","+y+",0",new IsoGridSquare(x,y,0));
  var sparse=actor(sparseCell,"roaming-greeter");var farZombie=zombie(sparseCell,102.5f,200.5f,0);
  sparseCell.tiles.remove("101,200,0");
  for(int n=0;n<5;n++)check(sparse.actuator.observe(n*STEP).senses().visible(Kind.ZOMBIE).isEmpty(),"missing intermediate square fails closed");
  sparseCell.tiles.remove("100,200,0");
  check(sparse.actuator.observe(6*STEP).senses().zombies()==Coverage.UNAVAILABLE,"missing sensor origin unavailable, never confidently clear");

  var registry=ServerHumanProfiles.registry();
  registry.register(new HumanProfile("sense-small",1,"idle",Map.of(),"","none",false,false,List.of(),1,100,true));
  var smallCell=new IsoCell();var small=actor(smallCell,"sense-small");
  check(small.actuator.observe(0).senses().zombies()==Coverage.COMPLETE,"complete small empty scan explicitly differs from partial/unavailable");
  var foreign=zombie(smallCell,100.5f,200.5f,1);var outside=zombie(smallCell,103.5f,200.5f,0);
  check(small.actuator.observe(STEP).senses().visible(Kind.ZOMBIE).isEmpty(),"other floors and out-of-radius bodies excluded");
  foreign.z=0;foreign.square=smallCell.getGridSquare(100,200,0);foreign.square.moving.add(foreign);foreign.grapple=true;
  check(small.actuator.observe(2*STEP).senses().visible(Kind.ZOMBIE).isEmpty(),"grapple-only reanimated corpses excluded");
  var resumedCell=new IsoCell();var resumed=actor(resumedCell,"sense-small");
  for(int n=0;n<145;n++)zombie(resumedCell,100.5f,200.5f,0).dead=true;
  for(int n=0;n<3;n++)check(resumed.actuator.observe(n*STEP).senses().zombies()==Coverage.PARTIAL,
    "resuming midway through a dense tile never claims this sample inspected its prefix");
  var negative=actor(new IsoCell(),"sense-small");
  check(negative.actuator.observe(-5*STEP).senses().sampledAtNanos()==-5*STEP
    &&negative.actuator.observe(-4*STEP).senses().sampledAtNanos()==-4*STEP,"arbitrary negative monotonic origin remains valid");

  IsoWorld.instance.currentCell=new IsoCell();
  var constructor=ServerHumanFactory.class.getDeclaredConstructor();constructor.setAccessible(true);var factory=constructor.newInstance();factory.tick(0);
  factory.spawn(new ServerHumanDefinition("factory-sense","factory-sense","guard",100.5f,200.5f,0,IsoDirections.E,false,"Tourist","stationary"));
  zombie(IsoWorld.instance.currentCell,101.5f,200.5f,0);factory.tick(STEP);
  check(factory.perception("factory-sense").visible(Kind.ZOMBIE).size()==1,"factory samples even an idle behavior that requests no perception");
  var actors=ServerHumanFactory.class.getDeclaredField("humans");actors.setAccessible(true);var owned=(ServerHuman)((Map<?,?>)actors.get(factory)).get("factory-sense");
  check(owned.activity.beginDialogue(new HumanObservation.Identity(player.id,77),STEP,owned.actuator),"factory-owned actor enters exclusive dialogue");
  LosUtil.result=LosUtil.TestResults.Blocked;factory.tick(2*STEP);
  check(factory.perception("factory-sense").sampledAtNanos()==2*STEP
    &&!one(factory.perception("factory-sense")).visible(),"factory continues sampling during exclusive dialogue without enabling combat decisions");
  check(factory.state("factory-sense").revision()==0,"sensor retention does not mutate physical state or dirty its persistence revision");
  factory.deactivate("factory-sense");
  check(factory.perception("factory-sense")==null&&!owned.perception.snapshot().sampled(),"factory deactivation clears sensing state");
  var awareCell=new IsoCell();var aware=actor(awareCell,"roaming-greeter");
  LosUtil.result=LosUtil.TestResults.Blocked;
  for(int n=1;n<=4;n++)zombie(awareCell,100.5f+n,200.5f,0);
  var aroundCorner=aware.actuator.observe(0);
  check(aroundCorner.senses().visible(Kind.ZOMBIE).isEmpty(),"occluded crowd does not authorize visual attacks");
  check(aroundCorner.awareness().contacts().size()==4,"local awareness includes zombies through obstacles");
  check(zombie.apocalipse.humans.survival.ThreatAssessment.assess(aroundCorner,zombie.apocalipse.humans.survival.SurvivalSettings.DEFAULT).pressured(),"hidden crowd triggers survival");
  check(aware.perception.resolveZombie(aroundCorner.awareness().contacts().getFirst().identity())==null,"awareness identity cannot bypass attack line of sight");
  var old=aroundCorner.awareness().contacts().getFirst();aware.player.setX(99.5f);
  var moved=aware.actuator.observe(STEP).awareness().contacts().stream().filter(c->c.identity().equals(old.identity())).findFirst().orElseThrow();
  check(moved.distanceSquared()>old.distanceSquared(),"danger distances follow observer movement");bounded(aroundCorner.awareness());
  aware.dispose();LosUtil.result=LosUtil.TestResults.Clear;
  System.out.println("Passed "+checks+" perception discovery, visibility, retention, identity and work-bound assertions.");
 }
}
"""

def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-perception-tests-") as directory:
        work=Path(directory);fixture=dict(FILES);sources=[]
        fixture['zombie/apocalipse/humans/HumanPerceptionTest.java']=HARNESS
        fixture['zombie/iso/LosUtil.java']=fixture['zombie/iso/LosUtil.java'].replace(
            'Clear,Blocked,ClearThroughClosedDoor','Clear,Blocked,ClearThroughClosedDoor,ClearThroughWindow,ClearThroughOpenDoor')
        for name,content in fixture.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
        sources.extend(sorted((ROOT/'42.21.0/src/zombie/apocalipse/humans').rglob('*.java')))
        args=work/'sources.txt';args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources))
        subprocess.run([str(ROOT/'jdk/bin/javac.exe'),'--release','25','-d',str(work/'classes'),'@'+str(args)],check=True)
        subprocess.run([str(ROOT/'jdk/bin/java.exe'),'-cp',str(work/'classes'),'zombie.apocalipse.humans.HumanPerceptionTest'],check=True)

if __name__=='__main__':main()
