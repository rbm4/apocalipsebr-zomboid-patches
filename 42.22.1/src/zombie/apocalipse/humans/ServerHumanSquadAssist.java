package zombie.apocalipse.humans;

import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.groups.*;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Factory-monitor-owned adapter. Detached orders are captured on the world thread;
 * navigation/combat reuse the existing serialized simulation adapters in sync/async mode. */
final class ServerHumanSquadAssist implements SquadAssistPort {
    private final ServerHuman human;
    private final long x1,y1,x2,y2;
    private final int z;
    private Order order;
    private boolean closed;
    private HumanObservation.Support strength=HumanObservation.Support.NONE;
    void publishStrength(long now,int allies){strength=new HumanObservation.Support(now,allies);}
    HumanObservation.Support strength(){return closed?HumanObservation.Support.NONE:strength;}
    private float routeX,routeY;
    private long nextRepath;
    private String problem="none";
    private record Pursuit(long at,String ally,String incarnation) { }
    private Pursuit pursuit;
    private long supportAt;
    private java.util.List<zombie.apocalipse.humans.survival.RetreatPlanner.Rendezvous> support=java.util.List.of();
    public void pursuit(Order value,long now,boolean active){
        pursuit=active&&!closed&&value!=null&&value.equals(order)?new Pursuit(now,value.report().ally(),value.report().incarnation()):null;
    }
    boolean pursuing(String ally,String incarnation,long now){
        return !closed&&fresh(now)&&pursuit!=null&&now>=pursuit.at()&&now-pursuit.at()<=500_000_000L
            &&pursuit.ally().equals(ally)&&pursuit.incarnation().equals(incarnation)
            &&order.report().ally().equals(ally)&&order.report().incarnation().equals(incarnation);
    }
    void publishSupport(long now,java.util.List<zombie.apocalipse.humans.survival.RetreatPlanner.Rendezvous> value){
        if(value.size()>7)throw new IllegalArgumentException("Too many retreat helpers");
        supportAt=now;support=java.util.List.copyOf(value);
    }
    java.util.List<zombie.apocalipse.humans.survival.RetreatPlanner.Rendezvous> retreatSupport(long now){
        return !closed&&now>=supportAt&&now-supportAt<=500_000_000L?support:java.util.List.of();
    }
    ServerHumanSquadAssist(ServerHuman human){
        this.human=human;
        var pattern=human.profile.walkingPatterns().stream().filter(p->p.slot().equals("background")).findFirst().orElseThrow();
        var area=pattern.area();var points=pattern.points();
        x1=Math.max(0,(area==null?points.stream().mapToInt(p->p.x()).min().orElseThrow():area.x1())-20);
        y1=Math.max(0,(area==null?points.stream().mapToInt(p->p.y()).min().orElseThrow():area.y1())-20);
        x2=(long)(area==null?points.stream().mapToInt(p->p.x()).max().orElseThrow():area.x2())+20;
        y2=(long)(area==null?points.stream().mapToInt(p->p.y()).max().orElseThrow():area.y2())+20;
        z=human.definition.z();
    }
    public Order observe(){return closed?null:order;}
    void publish(long now,SquadCombatCoordinator.Report report){
        order=report!=null&&contains(report.threat().x(),report.threat().y(),report.threat().z(),report.distress())
            &&contains(human.player.getX(),human.player.getY(),human.player.getZi(),report.distress())?new Order(now,report):null;
    }
    void close(){closed=true;order=null;pursuit=null;support=java.util.List.of();}
    private boolean fresh(long now){return !closed&&order!=null&&now>=order.sampledAt()&&now-order.sampledAt()<=500_000_000L
        &&now>=order.report().threat().observedAt()&&now-order.report().threat().observedAt()<=SquadCombatCoordinator.MEMORY_NANOS;}
    boolean contains(float x,float y,int floor){return contains(x,y,floor,order!=null&&order.report().distress());}
    private boolean contains(float x,float y,int floor,boolean distress){
        long extra=distress?SquadCombatCoordinator.DISTRESS_RANGE-20:0;
        return Float.isFinite(x)&&Float.isFinite(y)&&floor==z&&x>=Math.max(0,x1-extra)&&x<x2+extra+1.0
            &&y>=Math.max(0,y1-extra)&&y<y2+extra+1.0;
    }
    boolean permits(HumanSenses.Identity target,long now){
        if(!fresh(now)||target.kind()!=HumanSenses.Kind.ZOMBIE)return false;
        var shared=order.report().threat().identity();
        var zombie=human.perception.resolveZombie(target);
        return zombie!=null&&contains(human.player.getX(),human.player.getY(),human.player.getZi())
            &&contains(zombie.getX(),zombie.getY(),zombie.getZi())
            &&Math.hypot(zombie.getX()-human.player.getX(),zombie.getY()-human.player.getY())<=
                (target.onlineId()==shared.onlineId()&&target.instanceId()==shared.instanceId()?SquadCombatCoordinator.ASSIST_RANGE:1.5);
    }
    public boolean approach(Order requested,long now){
        if(!fresh(now)||!requested.equals(order)){problem="stale_order";return false;}
        if(!human.movement.available()){problem="origin_unavailable";return false;}
        var threat=order.report().threat();
        human.walking.stop();
        var start=new Tile(human.player.getXi(),human.player.getYi());
        var grid=new MovementGrid(){
            public boolean available(){return human.movement.available();}
            public boolean walkable(Tile tile){
                var square=human.movement.square(tile.x(),tile.y());
                return contains(tile.x()+.5F,tile.y()+.5F,z)&&human.movement.walkable(tile)&&square!=null
                    &&(tile.equals(start)||human.movement.freeOfBlockingBodies(square));
            }
            public boolean connected(Tile from,Tile to){return walkable(from)&&walkable(to)&&human.movement.connected(from,to);}
            public int traversalCost(Tile from,Tile to){return connected(from,to)?human.movement.traversalCost(from,to):0;}
        };
        // A* makes progress across the full assistance range; a breadth-first combat
        // placement search can exhaust its 256-node budget before reaching a 20-tile lead.
        // Occupied target centers remain blocked. A partial leg is never attack permission.
        var route=DestinationRoutePlanner.planLeg(grid,start,new Tile((int)Math.floor(threat.x()),(int)Math.floor(threat.y())));
        routeX=threat.x();routeY=threat.y();nextRepath=now+700_000_000L;
        boolean started=!route.isEmpty()&&human.movement.start(route,grid,MovementModifiers.Pace.RUN,now);
        problem=started?"none":"no_loaded_route";return started;
    }
    public HumanActuator.MotionStatus updateApproach(long now){
        if(!fresh(now)){stopApproach();return HumanActuator.MotionStatus.FAILED;}
        var threat=order.report().threat();
        if(now>=nextRepath&&Math.hypot(threat.x()-routeX,threat.y()-routeY)>1.5
            &&!human.movement.traversing()&&!approach(order,now))return HumanActuator.MotionStatus.FAILED;
        return human.movement.update(now);
    }
    public void stopApproach(){human.movement.stop();}
    public void transition(String phase,Order value){
        if(phase.equals("APPROACHING")||phase.equals("ENGAGING"))
            human.speech.state(zombie.apocalipse.humans.speech.SpeechEvent.ALLY_ASSIST,System.nanoTime());
        HumanLog.log(HumanLog.Level.DEBUG,()->"squad assist key="+human.definition.key()
        +"; phase="+phase+"; ally="+value.report().ally()+"; distress="+value.report().distress()
        +"; target="+value.report().threat().identity()+"; reason="+problem+"; x="+human.player.getX()+"; y="+human.player.getY());}
}
