package zombie.apocalipse.humans;

import java.util.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.movement.MovementGrid;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.planning.NpcDecisionPlanner;

/** Server-owned bridge for one incarnation; the planner receives only detached values. */
final class ServerHumanPlanning implements HumanEngagement.Planning, AutoCloseable {
    private final NpcDecisionPlanner planner;
    private final NpcDecisionPlanner.Slot selections, routes;
    private long selectionTicket=-1, selectionAt, cachedAt, routeTicket=-1;
    private HumanSenses.Identity selectionHeld;
    private HumanEngagement.Selection cached;
    ServerHumanPlanning(NpcDecisionPlanner planner) {
        this.planner=planner; selections=planner.open();
        try {routes=planner.open();} catch(RuntimeException failure) {selections.close();throw failure;}
    }
    public HumanEngagement.Selection preview(HumanObservation observation,HumanSenses.Contact held,HumanEngagement.Settings settings) {
        planner.requireOwner();long now=observation.nowNanos();
        var identity=held==null?null:held.identity();
        if(selectionTicket!=-1) {
            var result=selections.take(selectionTicket);
            if(result!=null) {
                if(Objects.equals(identity,selectionHeld)) {
                    cached=((NpcDecisionPlanner.Selected)result.answer()).selection();cachedAt=result.submittedAt();
                }
                selectionTicket=-1;
            } else if(now-selectionAt>500_000_000L || !Objects.equals(identity,selectionHeld)) {
                selections.cancel();selectionTicket=-1;
            }
        }
        if(selectionTicket==-1) {
            selectionHeld=identity;selectionAt=now;
            selectionTicket=selections.submit(new NpcDecisionPlanner.Select(observation,held,settings),now);
            // Synchronous startup uses the exact same job/result protocol.
            var immediate=selections.take(selectionTicket);
            if(immediate!=null) {cached=((NpcDecisionPlanner.Selected)immediate.answer()).selection();cachedAt=now;selectionTicket=-1;}
        }
        if(cached==null || now<cachedAt || now-cachedAt>500_000_000L) return HumanEngagement.Selection.NONE;
        if(cached.target()==null) return HumanEngagement.Selection.NONE;
        var contact=observation.senses().contacts().stream().filter(c->c.identity().equals(cached.target().identity())).findFirst().orElse(null);
        // A plan is advisory. Revalidate current lifetime/territory/visibility before execution.
        if(contact==null) {
            if(held!=null&&held.identity().equals(cached.target().identity())
                &&observation.senses().zombies()!=HumanSenses.Coverage.COMPLETE
                &&now>=held.lastSeenNanos()&&now-held.lastSeenNanos()<settings.lostGraceNanos()
                &&HumanEngagement.GUARD_ZOMBIES.evaluate(observation,held,settings)!=HumanEngagement.Choice.IGNORE)
                return new HumanEngagement.Selection(held,HumanEngagement.Choice.WATCH);
            return HumanEngagement.Selection.NONE;
        }
        var choice=HumanEngagement.GUARD_ZOMBIES.evaluate(observation,contact,settings);
        return choice==HumanEngagement.Choice.IGNORE?HumanEngagement.Selection.NONE:new HumanEngagement.Selection(contact,choice);
    }
    public void cancel() {planner.requireOwner();selections.cancel();selectionTicket=-1;cached=null;}
    boolean route(MovementGrid live,Tile start,Tile goal,boolean partial,long now) {
        planner.requireOwner();if(!planner.admitCapture())return false;
        // Bounded topology capture. No live MovementGrid or lambda enters the worker.
        var known=new LinkedHashMap<Tile,Boolean>();
        var frontier=new PriorityQueue<Tile>(Comparator.comparingLong(t->Math.abs((long)t.x()-goal.x())+Math.abs((long)t.y()-goal.y())));
        var queued=new HashSet<Tile>();
        var edges=new ArrayList<NpcDecisionPlanner.Edge>();
        known.put(start,live.available()&&live.walkable(start));if(known.get(start)){frontier.add(start);queued.add(start);}
        int[][] directions={{1,0},{0,1},{-1,0},{0,-1}};
        while(!frontier.isEmpty()) {
            var from=frontier.remove();
            for(var direction:directions) {
                var to=new Tile(from.x()+direction[0],from.y()+direction[1]);
                if(!known.containsKey(to)){
                    if(known.size()>=256)continue;
                    known.put(to,live.walkable(to));
                }
                if(!known.get(to))continue;
                int cost=live.traversalCost(from,to);
                if(cost>0){
                    edges.add(new NpcDecisionPlanner.Edge(from,to,cost));
                    if(queued.add(to))frontier.add(to);
                }
            }
        }
        var tiles=new HashSet<Tile>();known.forEach((tile,walkable)->{if(walkable)tiles.add(tile);});
        routeTicket=routes.submit(new NpcDecisionPlanner.Route(new NpcDecisionPlanner.Grid(tiles,edges),start,goal,partial),now);
        return routeTicket!=-1;
    }
    List<Tile> routeResult(long now) {
        planner.requireOwner();var result=routes.take(routeTicket);
        return result==null?null:now>=result.submittedAt()&&now-result.submittedAt()<=2_000_000_000L
            ?((NpcDecisionPlanner.Planned)result.answer()).route():List.of();
    }
    void cancelRoute() {planner.requireOwner();routes.cancel();routeTicket=-1;}
    public void close() {planner.requireOwner();selections.close();routes.close();}
}
