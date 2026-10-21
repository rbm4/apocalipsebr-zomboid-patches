package zombie.apocalipse.humans;

import java.util.*;
import zombie.ServerMovingObjectSet;
import zombie.apocalipse.humans.brain.*;
import static zombie.apocalipse.humans.brain.HumanSenses.*;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.LosUtil;
import zombie.network.GameServer;

/** Simulation-thread world adapter: discover -> see -> retain. No target or hostility policy. */
final class ServerHumanPerception {
    static final int PLAYER_BUDGET=128, TILE_BUDGET=128, BODY_BUDGET=256;
    static final int ZOMBIE_CANDIDATES=48, PLAYER_RAYS=32, ZOMBIE_RAYS=16;
    private record Offset(int x,int y) { }
    private record Candidate(IsoGameCharacter body,double distance) { }
    private record Binding(IsoGameCharacter body,Object membership,Identity identity) { }
    private static final class Cursor { int tile,body; }
    private record Scan(int tiles,int bodies,boolean complete,boolean loaded) { }
    private final ServerHuman human;
    private final HumanContactMemory memory=new HumanContactMemory();
    private final IdentityHashMap<IsoGameCharacter,Binding> bindings=new IdentityHashMap<>();
    private final List<Offset> offsets;
    private final Cursor near=new Cursor(),far=new Cursor();
    private int playerCursor,zombieRayCursor,playerRayCursor;
    private long generation,nextSample;
    private boolean sampled;
    private Frame frame=Frame.EMPTY;

    ServerHumanPerception(ServerHuman human){
        this.human=human;
        int radius=(int)Math.ceil(human.profile.perceptionRadius());
        var offsets=new ArrayList<Offset>();
        for(int x=-radius;x<=radius;x++)for(int y=-radius;y<=radius;y++)offsets.add(new Offset(x,y));
        offsets.sort(Comparator.comparingInt((Offset o)->Math.max(Math.abs(o.x()),Math.abs(o.y())))
            .thenComparingInt(o->o.x()*o.x()+o.y()*o.y()).thenComparingInt(Offset::x).thenComparingInt(Offset::y));
        this.offsets=List.copyOf(offsets);far.tile=9;
    }

    Frame snapshot(){return frame;}
    HumanObservation observe(long now){
        update(now);
        var visitors=frame.visible(Kind.PLAYER).stream().map(c->new HumanObservation.Visitor(
            new HumanObservation.Identity(c.identity().onlineId(),c.identity().instanceId()),c.x(),c.y(),c.z(),c.distanceSquared()))
            .sorted(Comparator.comparingDouble(HumanObservation.Visitor::distanceSquared)).toList();
        boolean viewed=false;
        for(var viewer:human.viewers)if(human.isRelevant(viewer)){viewed=true;break;}
        return new HumanObservation(now,visitors,viewed&&human.roaming.available(),human.state.snapshot(),frame);
    }

    void update(long suppliedNow){
        if(sampled&&suppliedNow<nextSample)return;
        long now=sampled?Math.max(suppliedNow,frame.sampledAtNanos()):suppliedNow;
        sampled=true;nextSample=now+human.profile.perceptionIntervalMs()*1_000_000L;
        var previous=frame;
        var invalid=new HashSet<Identity>();
        for(var b:bindings.values())if(!valid(b))invalid.add(b.identity());
        var players=new ArrayList<Candidate>();
        int size=GameServer.Players.size(),checked=Math.min(size,PLAYER_BUDGET);
        for(int n=0;n<checked;n++){
            var player=GameServer.Players.get(Math.floorMod(playerCursor++,size));
            if(eligible(player)&&distance(player)<=radiusSquared())players.add(new Candidate(player,distance(player)));
        }
        var zombies=new ArrayList<Candidate>();
        // Refresh known in-range bodies even when discovery is visiting another ring.
        for(var b:bindings.values())if(b.identity().kind()==Kind.ZOMBIE&&!invalid.contains(b.identity())&&distance(b.body())<=radiusSquared())
            zombies.add(new Candidate(b.body(),distance(b.body())));
        Scan close=scan(near,0,9,9,64,zombies);
        Scan outer=scan(far,9,offsets.size(),TILE_BUDGET-9,BODY_BUDGET-64,zombies);
        boolean truncated=zombies.size()>ZOMBIE_CANDIDATES;
        // Stable distance sorting must not let retained bodies monopolize equal-distance slots.
        if(!zombies.isEmpty())Collections.rotate(zombies,Math.floorMod(zombieRayCursor,zombies.size()));
        zombies.sort(Comparator.comparingDouble(Candidate::distance));
        if(truncated)zombies.subList(ZOMBIE_CANDIDATES,zombies.size()).clear();
        players.sort(Comparator.comparingDouble(Candidate::distance));
        var sightings=new ArrayList<Sighting>();
        int playerChecks=see(players,PLAYER_RAYS,playerRayCursor,sightings);
        int zombieChecks=see(zombies,ZOMBIE_RAYS,zombieRayCursor,sightings);
        playerRayCursor+=PLAYER_RAYS;zombieRayCursor+=ZOMBIE_RAYS;
        Coverage pc=size>PLAYER_BUDGET||players.size()>PLAYER_RAYS?Coverage.PARTIAL:Coverage.COMPLETE;
        boolean originLoaded=human.player.getCell().getGridSquare(human.player.getXi(),human.player.getYi(),human.player.getZi())!=null;
        boolean indexed=human.player.getCell().getObjectList() instanceof ServerMovingObjectSet;
        Coverage zc=!originLoaded||!indexed?Coverage.UNAVAILABLE
            :close.complete()&&outer.complete()&&close.loaded()&&outer.loaded()&&!truncated&&zombies.size()<=ZOMBIE_RAYS?Coverage.COMPLETE:Coverage.PARTIAL;
        frame=memory.update(now,sightings,invalid,originLoaded?pc:Coverage.UNAVAILABLE,zc,truncated,
            new Work(checked,close.tiles()+outer.tiles(),close.bodies()+outer.bodies(),zombies.size(),playerChecks+zombieChecks));
        var kept=new HashSet<Identity>();for(var c:frame.contacts())kept.add(c.identity());
        bindings.values().removeIf(b->!kept.contains(b.identity()));
        transitions(previous,frame);
    }

    private Scan scan(Cursor cursor,int start,int end,int tilesLimit,int bodiesLimit,List<Candidate> candidates){
        if(start>=end)return new Scan(0,0,true,true);
        if(cursor.tile<start||cursor.tile>=end){cursor.tile=start;cursor.body=0;}
        int initial=cursor.tile,tiles=0,bodies=0;boolean loaded=true,complete=false,resumed=cursor.body!=0;
        while(tiles<tilesLimit&&bodies<bodiesLimit){
            var offset=offsets.get(cursor.tile);tiles++;
            var square=human.player.getCell().getGridSquare(human.player.getXi()+offset.x(),human.player.getYi()+offset.y(),human.player.getZi());
            if(square==null)loaded=false;
            var objects=square==null?null:square.getMovingObjects();
            while(objects!=null&&cursor.body<objects.size()&&bodies<bodiesLimit){
                var object=objects.get(cursor.body++);bodies++;
                if(object instanceof IsoZombie zombie&&zombie.getCurrentSquare()==square&&eligible(zombie)){
                    double distance=distance(zombie);
                    if(distance<=radiusSquared()&&candidates.stream().noneMatch(c->c.body()==zombie))candidates.add(new Candidate(zombie,distance));
                }
            }
            if(objects!=null&&cursor.body<objects.size())break;
            cursor.body=0;cursor.tile++;if(cursor.tile==end)cursor.tile=start;
            if(cursor.tile==initial){complete=!resumed;break;}
        }
        return new Scan(tiles,bodies,complete,loaded);
    }

    private int see(List<Candidate> candidates,int budget,int rotation,List<Sighting> sightings){
        int checks=Math.min(budget,candidates.size());
        // Reserve half the zombie budget for closest contacts; rotate the rest for fairness.
        var chosen=new LinkedHashSet<Integer>();
        int nearest=budget==ZOMBIE_RAYS?Math.min(checks,budget/2):0;
        for(int n=0;n<nearest;n++)chosen.add(n);
        for(int n=0;chosen.size()<checks;n++)chosen.add(Math.floorMod((long)rotation+n,candidates.size()));
        for(int index:chosen){
            var candidate=candidates.get(index);var body=candidate.body();
            if(!visible(body))continue;
            Object membership=body instanceof IsoZombie?membership(body):null;
            var binding=bindings.get(body);
            short online=online(body);
            if(binding==null||binding.membership()!=membership||binding.identity().onlineId()!=online||binding.identity().instanceId()!=body.getID()){
                binding=new Binding(body,membership,new Identity(body instanceof IsoZombie?Kind.ZOMBIE:Kind.PLAYER,online,body.getID(),++generation));
                bindings.put(body,binding);
            }
            sightings.add(new Sighting(binding.identity(),body.getX(),body.getY(),body.getZ(),candidate.distance()));
        }
        return checks;
    }
    private Object membership(IsoGameCharacter body){
        return human.player.getCell().getObjectList() instanceof ServerMovingObjectSet set?set.member(body):null;
    }
    private boolean valid(Binding b){
        return b.body().getID()==b.identity().instanceId()&&online(b.body())==b.identity().onlineId()
            &&(b.body() instanceof IsoPlayer p?eligible(p):eligible((IsoZombie)b.body())&&membership(b.body())==b.membership());
    }
    private static short online(IsoGameCharacter body){return body instanceof IsoPlayer p?p.getOnlineID():((IsoZombie)body).getOnlineID();}
    boolean eligible(IsoPlayer player){
        if(player==null||player==human.player||player.isDead()||player.isInvisible()
            ||player.getZi()!=human.player.getZi()||GameServer.IDToPlayerMap.get(player.getOnlineID())!=player)return false;
        var connection=GameServer.getConnectionFromPlayer(player);
        return connection!=null&&connection.isFullyConnected()&&!GameServer.isDelayedDisconnect(player);
    }
    private boolean eligible(IsoZombie zombie){
        return !zombie.isDead()&&zombie.getHealth()>0&&!zombie.isReanimatedForGrappleOnly()
            &&Float.isFinite(zombie.getX())&&Float.isFinite(zombie.getY())&&Float.isFinite(zombie.getZ())
            &&zombie.getZi()==human.player.getZi()&&zombie.getCurrentSquare()!=null
            &&human.player.getCell().getGridSquare(zombie.getXi(),zombie.getYi(),zombie.getZi())==zombie.getCurrentSquare()&&membership(zombie)!=null;
    }
    private double distance(IsoGameCharacter body){double dx=body.getX()-human.player.getX(),dy=body.getY()-human.player.getY();return dx*dx+dy*dy;}
    private double radiusSquared(){return human.profile.perceptionRadius()*human.profile.perceptionRadius();}
    boolean visible(IsoGameCharacter body){
        if(!human.profile.requireLos())return true;
        if(body.getCurrentSquare()==null)return false;
        var cell=human.player.getCell();int z=human.player.getZi();
        if(cell.getGridSquare(human.player.getXi(),human.player.getYi(),z)==null)return false;
        // Native lineClear skips absent squares. Fail closed before asking its vision rules.
        int dx=body.getXi()-human.player.getXi(),dy=body.getYi()-human.player.getYi(),steps=Math.max(Math.abs(dx),Math.abs(dy));
        for(int n=0;n<=steps;n++){
            int x=human.player.getXi()+(steps==0?0:Math.round(dx*(float)n/steps));
            int y=human.player.getYi()+(steps==0?0:Math.round(dy*(float)n/steps));
            if(cell.getGridSquare(x,y,z)==null)return false;
        }
        var result=LosUtil.lineClear(cell,human.player.getXi(),human.player.getYi(),z,body.getXi(),body.getYi(),body.getZi(),false);
        return result!=LosUtil.TestResults.Blocked&&result!=LosUtil.TestResults.ClearThroughClosedDoor;
    }
    private void transitions(Frame previous,Frame next){
        if(!HumanLog.enabled(HumanLog.Level.DEBUG))return;
        var old=new HashMap<Identity,Contact>();for(var c:previous.contacts())old.put(c.identity(),c);
        for(var c:next.contacts()){
            var before=old.remove(c.identity());
            if(before==null||before.visible()!=c.visible())HumanLog.log(HumanLog.Level.DEBUG,
                ()->"perception key="+human.definition.key()+"; contact="+c.identity()+"; state="+(c.visible()?"VISIBLE":"REMEMBERED"));
        }
        for(var id:old.keySet())HumanLog.log(HumanLog.Level.DEBUG,()->"perception key="+human.definition.key()+"; contact="+id+"; state=FORGOTTEN");
    }
    void clear(){memory.clear();bindings.clear();frame=Frame.EMPTY;sampled=false;near.tile=near.body=far.body=0;far.tile=9;}
}
