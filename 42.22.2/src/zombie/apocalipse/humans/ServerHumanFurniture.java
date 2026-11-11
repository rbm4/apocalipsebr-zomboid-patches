package zombie.apocalipse.humans;

import java.util.*;
import zombie.apocalipse.humans.furniture.*;
import zombie.apocalipse.humans.furniture.FurnitureAffordances.Frame;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;

/** Composition/lifecycle for demanded native sensing; contains no rest or movement policy. */
final class ServerHumanFurniture {
    private final Thread owner=Thread.currentThread();
    private final FurnitureCaptureScheduler scheduler=new FurnitureCaptureScheduler();
    private final ServerHumanFurnitureWorld world;
    private final Map<ServerHuman,Observer> actors=new IdentityHashMap<>();
    private final Set<ServerHuman> retained=Collections.newSetFromMap(new IdentityHashMap<>());
    final FurnitureReservations reservations=new FurnitureReservations();
    void retain(ServerHuman human){check();if(!actors.containsKey(human))throw new IllegalStateException("Unknown furniture actor");retained.add(human);}
    void release(ServerHuman human){check();retained.remove(human);if(scheduler.size()==0&&retained.isEmpty())world.clear();}
    List<IsoPlayer> bodies(){check();return actors.keySet().stream().map(h->h.player).toList();}
    zombie.iso.IsoObject resolve(zombie.apocalipse.humans.furniture.FurnitureAffordances.Token token){check();return world.resolve(token);}
    zombie.apocalipse.humans.furniture.FurnitureAffordances.Candidate candidate(zombie.apocalipse.humans.furniture.FurnitureAffordances.Token token,long now){check();return world.candidate(token,now);}
    zombie.apocalipse.humans.furniture.FurnitureAffordances.Candidate candidateAt(ServerHuman human,int x,int y,int z,String side,long now){
        check();human.requireAuthority();
        if(z!=human.definition.z()||Math.abs((long)x-human.player.getXi())>12||Math.abs((long)y-human.player.getYi())>12)return null;
        world.beginPass(human.player.getCell(),zombie.network.GameServer.Players,bodies());
        var area=human.profile.walkingPatterns().stream().filter(p->p.slot().equals("background")).map(p->p.area()).filter(Objects::nonNull).findFirst().orElse(null);
        var view=world.view(human.player.getCell(),z,area);view.begin(human.player.getXi(),human.player.getYi(),human.player.getZi());
        int size=view.objectCount(x,y,z);if(size<0||size>128)return null;
        for(int i=0;i<size;i++){
            var candidate=view.inspect(x,y,z,i,Set.of(zombie.apocalipse.humans.furniture.FurnitureAffordances.Kind.SIT),now);
            if(candidate!=null&&!candidate.occupied()&&candidate.seat().sides().contains(side))return candidate;
        }
        return null;
    }
    ServerHumanFurniture(){this(new ServerHumanFurnitureWorld());}
    ServerHumanFurniture(ServerHumanFurnitureWorld world){this.world=Objects.requireNonNull(world);}
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Furniture composition requires server owner");}
    Observer register(ServerHuman human){
        check();human.requireAuthority();if(actors.containsKey(human))throw new IllegalStateException("Furniture actor already registered");
        if(actors.size()==64)throw new IllegalStateException("Too many furniture actors");
        var demand=human.activity.behavior().furnitureDemand();
        var observer=demand.kinds().isEmpty()?null:new Observer(human,demand);
        if(observer!=null)scheduler.add(observer);actors.put(human,observer);return observer;
    }
    void remove(ServerHuman human){
        check();var observer=actors.remove(human);
        if(observer!=null){observer.close();scheduler.remove(observer);}
        retained.remove(human);if(scheduler.size()==0&&retained.isEmpty())world.clear();
    }
    void capture(long now,IsoCell cell,List<IsoPlayer> players){
        check();if(scheduler.size()==0&&retained.isEmpty())return;
        var bodies=new ArrayList<IsoPlayer>(actors.size());for(var human:actors.keySet())bodies.add(human.player);
        world.beginPass(cell,players,bodies);scheduler.capture(now);
    }
    final class Observer implements FurnitureCaptureScheduler.Participant {
        private final ServerHuman human;
        private final FurnitureScanner scanner;
        private final ServerHumanFurnitureWorld.View view;
        private Object room;
        private boolean closed,failed;
        Observer(ServerHuman human,FurnitureDemand demand){
            this.human=human;scanner=new FurnitureScanner(demand);
            var area=human.profile.walkingPatterns().stream().filter(p->p.slot().equals("background")).map(p->p.area()).filter(Objects::nonNull).findFirst().orElse(null);
            view=world.view(human.player.getCell(),human.definition.z(),area);
        }
        public void capture(long now,FurnitureScanBudget budget){
            check();human.requireAuthority();if(closed||failed)return;
            if(human.player.isDead()||human.state.snapshot().dead()){scanner.clear();view.clear();room=null;return;}
            try{
                var current=view.begin(human.player.getXi(),human.player.getYi(),human.player.getZi());
                if(current!=room){scanner.clear();room=current;}
                scanner.update(human.player.getXi(),human.player.getYi(),human.player.getZi(),now,view,budget);
            }catch(RuntimeException problem){
                scanner.clear();view.clear();room=null;failed=true;scheduler.remove(this);
                if(scheduler.size()==0&&retained.isEmpty())world.clear();
                HumanLog.log(HumanLog.Level.ERROR,()->"furniture sensing disabled until reactivation key="+human.definition.key()+"; issue="+problem.getClass().getSimpleName());
            }
        }
        Frame snapshot(){check();return closed||failed?Frame.EMPTY:scanner.snapshot();}
        void close(){check();closed=true;scanner.clear();view.clear();room=null;}
    }
}
