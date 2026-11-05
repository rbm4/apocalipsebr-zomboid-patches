package zombie.apocalipse.humans;

import java.util.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.groups.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec;

/** Server-owned composition/geometry capture; pure coordinators consume detached member facts. */
final class ServerHumanGroups {
    private static final int COMBAT_SUPPORT_RADIUS=6;
    private record Entry(ServerHuman human,GroupPatrolModule module) { }
    private record Sample(long at,GroupPatrolCoordinator.Member member,boolean inside) { }
    private final Map<String,Entry> entries=new HashMap<>();
    private final Map<String,GroupPatrolCoordinator> groups=new HashMap<>();
    private final Map<String,GroupCohesionCoordinator> cohesion=new HashMap<>();
    private final Map<String,SquadCombatCoordinator> combatGroups=new HashMap<>();
    private Map<String,Sample> samples=Map.of();
    private final java.util.function.LongSupplier dwellMillis;
    ServerHumanGroups(){this(GroupPatrolCoordinator::randomDwellMillis);}
    ServerHumanGroups(java.util.function.LongSupplier dwellMillis){this.dwellMillis=java.util.Objects.requireNonNull(dwellMillis);}
    void register(ServerHuman human){
        var membership=human.definition.group();
        var module=human.activity.behavior() instanceof HumanModuleRuntime runtime?runtime.module(GroupPatrolModule.class):null;
        if(module==null){if(membership.present())throw new IllegalArgumentException("Group membership requires group-patrol module");return;}
        if(!membership.present())throw new IllegalArgumentException("Group patrol requires durable membership");
        if(entries.values().stream().anyMatch(e->e.human.definition.group().groupId().equals(membership.groupId())&&e.human.definition.group().slot()==membership.slot()))
            throw new IllegalArgumentException("Duplicate live group slot");
        var pattern=human.profile.walkingPatterns().stream().filter(p->p.slot().equals("background")).findFirst().orElseThrow();
        var existing=groups.get(membership.groupId());
        if(existing!=null&&!existing.route().equals(pattern.points()))throw new IllegalArgumentException("Group members must share the same patrol route");
        groups.computeIfAbsent(membership.groupId(),id->new GroupPatrolCoordinator(pattern.points(),2,2,dwellMillis));
        cohesion.computeIfAbsent(membership.groupId(),id->new GroupCohesionCoordinator());
        entries.put(human.definition.key(),new Entry(human,module));
        module.bind(()->observe(human),membership.slot());
        var engagement=((HumanModuleRuntime)human.activity.behavior()).module(EngageZombiesModule.class);
        if(engagement!=null){
            human.squadAssist=new ServerHumanSquadAssist(human);engagement.bindAssistance(human.squadAssist);
            combatGroups.computeIfAbsent(membership.groupId(),id->new SquadCombatCoordinator());
        }
    }
    void remove(ServerHuman human){
        entries.remove(human.definition.key());String group=human.definition.group().groupId();
        if(human.squadAssist!=null)human.squadAssist.close();
        if(entries.values().stream().noneMatch(e->e.human.definition.group().groupId().equals(group))){groups.remove(group);combatGroups.remove(group);cohesion.remove(group);}
    }
    /** Invoked only on the registered world thread while holding the factory monitor. */
    void capture(long now){
        var detached=new HashMap<String,Sample>();
        for(var e:entries.values()){
            var human=e.human;if(human.state.snapshot().dead())continue;
            String selected=((HumanModuleRuntime)human.activity.behavior()).selectedModule();
            var retaliation=ServerHumanRetaliation.module(human);
            boolean participating=human.activity.mode()==HumanActivityController.Mode.AUTONOMOUS&&(selected==null||selected.equals("group-patrol"))
                &&(retaliation==null||retaliation.aggressor()==null);
            detached.put(human.definition.key(),new Sample(now,new GroupPatrolCoordinator.Member(human.definition.key(),human.incarnation,
                human.definition.group().slot(),human.player.getX(),human.player.getY(),human.player.getZi(),participating),
                human.territory.contains(human.player.getX(),human.player.getY(),human.player.getZ())));
        }
        samples=Map.copyOf(detached);
        captureCombat(now);
        captureStrength(now);
    }
    /** Local combat capacity is independent of assist assignments and survival phase.
     * Otherwise the first retreat erases support and cascades through the squad. */
    private void captureStrength(long now){
        for(var entry:entries.values()){
            var h=entry.human;if(h.squadAssist==null)continue;
            int allies=0;
            if(combatReady(h))for(var other:entries.values()){
                var ally=other.human;
                if(ally==h||!ally.definition.group().groupId().equals(h.definition.group().groupId())
                    ||ally.player.getZi()!=h.player.getZi()||!combatReady(ally))continue;
                double dx=ally.player.getX()-h.player.getX(),dy=ally.player.getY()-h.player.getY();
                if(dx*dx+dy*dy<=COMBAT_SUPPORT_RADIUS*COMBAT_SUPPORT_RADIUS&&h.perception.visible(ally.player))allies++;
            }
            h.squadAssist.publishStrength(now,allies);
        }
    }
    private boolean combatReady(ServerHuman h){
        var state=h.state.snapshot();var injuries=state.injuries();var vitals=state.vitals();
        var runtime=(HumanModuleRuntime)h.activity.behavior();var selected=runtime.selectedModule();
        var survival=runtime.module(zombie.apocalipse.humans.survival.SurvivalModule.class);
        var limits=survival==null?zombie.apocalipse.humans.survival.SurvivalSettings.DEFAULT:survival.settings();
        var retaliation=ServerHumanRetaliation.module(h);
        return h.squadAssist!=null&&!state.dead()&&!h.player.isDead()
            &&h.activity.mode()==HumanActivityController.Mode.AUTONOMOUS
            &&(selected==null||selected.equals("group-patrol")||selected.equals("engage-zombies")||selected.equals("survival"))
            &&(retaliation==null||retaliation.aggressor()==null)
            &&!injuries.immobile()&&!injuries.downed()&&injuries.bleedingPerSecond()==0
            &&vitals.health()>=limits.reengageHealth()
            &&vitals.endurance()>=limits.reengageEndurance()
            &&h.movement.available()&&!h.movement.traversing();
    }
    /** The board sees detached facts. Native target membership/identity is checked here on the world thread. */
    private void captureCombat(long now){
        for(var group:combatGroups.entrySet()){
            var members=new ArrayList<SquadCombatCoordinator.Member>();
            for(var entry:entries.values()){
                var h=entry.human;if(!h.definition.group().groupId().equals(group.getKey()))continue;
                if(h.state.snapshot().dead()||h.player.isDead()){if(h.squadAssist!=null)h.squadAssist.publish(now,null);continue;}
                var survival=((HumanModuleRuntime)h.activity.behavior()).module(zombie.apocalipse.humans.survival.SurvivalModule.class);
                boolean distress=survival!=null&&survival.phase()!=zombie.apocalipse.humans.survival.SurvivalModule.Phase.SAFE;
                var frame=distress?h.perception.awareness():h.perception.snapshot();SquadCombatCoordinator.Threat threat=null;
                if(frame.sampled()&&now>=frame.sampledAtNanos()&&now-frame.sampledAtNanos()<=500_000_000L){
                    var candidate=frame.contacts().stream().filter(c->(distress||c.visible())&&c.identity().kind()==HumanSenses.Kind.ZOMBIE
                        &&c.distanceSquared()<=(distress?144:16)&&now>=c.lastSeenNanos()&&now-c.lastSeenNanos()<=500_000_000L)
                        .sorted(java.util.Comparator.comparingDouble(HumanSenses.Contact::distanceSquared))
                        .filter(c->(distress?h.perception.trackedZombie(c.identity()):h.perception.resolveZombie(c.identity()))!=null).findFirst().orElse(null);
                    if(candidate!=null)threat=new SquadCombatCoordinator.Threat(candidate.identity(),candidate.x(),candidate.y(),(int)Math.floor(candidate.z()),candidate.lastSeenNanos());
                }
                var injury=h.state.snapshot().injuries();
                boolean helper=h.squadAssist!=null&&h.activity.mode()==HumanActivityController.Mode.AUTONOMOUS&&!injury.immobile()&&!injury.downed()
                    &&(survival==null||survival.phase()==zombie.apocalipse.humans.survival.SurvivalModule.Phase.SAFE);
                members.add(new SquadCombatCoordinator.Member(h.definition.key(),h.incarnation,h.player.getX(),h.player.getY(),h.player.getZi(),helper,threat,distress));
            }
            var invalid=new HashSet<SquadCombatCoordinator.Report>();
            for(var report:group.getValue().reports()){
                var source=entries.get(report.ally());
                if(source==null||!source.human.incarnation.equals(report.incarnation())
                    ||source.human.perception.trackedZombie(report.threat().identity())==null)invalid.add(report);
            }
            var orders=group.getValue().update(now,members,invalid);
            for(var member:members){var h=entries.get(member.key()).human;if(h.squadAssist==null)continue;
                var report=orders.get(member.key());h.squadAssist.publish(now,report);
                if(report!=null){
                    var source=entries.get(report.ally());var target=source==null?null:source.human.perception.trackedZombie(report.threat().identity());
                    if(target!=null)h.perception.hintZombie(target);
                }
            }
            // Only live, loaded helpers with a renewed execution lease can attract
            // a retreat. Assignments alone (including BLOCKED/search backoff) cannot.
            for(var member:members){
                var h=entries.get(member.key()).human;if(h.squadAssist==null)continue;
                var support=new ArrayList<zombie.apocalipse.humans.survival.RetreatPlanner.Rendezvous>();
                for(var helper:members)if(helper.helper()&&!helper.key().equals(member.key())&&helper.z()==member.z()){
                    var actor=entries.get(helper.key()).human;
                    if(actor.squadAssist!=null&&actor.squadAssist.pursuing(member.key(),member.incarnation(),now)&&actor.movement.available())
                        support.add(new zombie.apocalipse.humans.survival.RetreatPlanner.Rendezvous(helper.x(),helper.y()));
                }
                h.squadAssist.publishSupport(now,support);
            }
        }
    }
    /** Server-owned bounded rally validation followed by detached group policy. */
    void update(long now){
        for(var entry:groups.entrySet()){
            var members=new ArrayList<GroupPatrolCoordinator.Member>();
            boolean complete=true;
            for(var actor:entries.values())if(actor.human.definition.group().groupId().equals(entry.getKey())){
                var s=samples.get(actor.human.definition.key());
                if(s!=null&&now>=s.at&&now-s.at<=500_000_000L&&s.member.incarnation().equals(actor.human.incarnation))members.add(s.member);
                else complete=false;
            }
            if(!complete){entry.getValue().pauseDwell();continue;}
            var state=cohesion.get(entry.getKey());
            var roster=members.stream().map(m->new GroupCohesionCoordinator.Member(m,samples.get(m.key()).inside())).toList();
            var representative=entries.get(members.getFirst().key()).human;
            var candidate=members.stream().allMatch(GroupPatrolCoordinator.Member::participating)
                ?rally(representative,state,roster,entry.getValue()):null;
            long revision=state.revision();state.update(roster,candidate);
            if(revision!=state.revision())HumanLog.log(HumanLog.Level.DEBUG,()->"group cohesion id="+entry.getKey()
                +"; mode="+state.mode()+"; revision="+state.revision()+"; rally="+state.rally()+"; waypoint="+entry.getValue().waypoint());
            if(state.mode()!=GroupCohesionCoordinator.Mode.PATROL){
                if(state.mode()==GroupCohesionCoordinator.Mode.RETURN||state.mode()==GroupCohesionCoordinator.Mode.REGROUP)entry.getValue().displaced();
                else entry.getValue().pauseDwell();
                continue;
            }
            var before=entry.getValue().phase();entry.getValue().update(now,members);
            if(before!=entry.getValue().phase())HumanLog.log(HumanLog.Level.DEBUG,()->"group patrol id="+entry.getKey()+"; intent=PATROL; phase="+entry.getValue().phase()+"; waypoint="+entry.getValue().waypoint());
        }
    }
    private GroupPatrolPort.Observation observe(ServerHuman human){
        var sample=samples.get(human.definition.key());var group=groups.get(human.definition.group().groupId());
        var entry=entries.get(human.definition.key());
        boolean valid=entry!=null&&entry.human==human&&sample!=null&&sample.member.incarnation().equals(human.incarnation)&&group!=null;
        var goal=group==null?new GroupPatrolCoordinator.Goal(-1,new WalkingPatternSpec.Point(0,0,0),new WalkingPatternSpec.Point(0,0,0),false):group.goal(human.definition.group().slot(),valid);
        var state=cohesion.get(human.definition.group().groupId());
        boolean regroup=state!=null&&(state.mode()==GroupCohesionCoordinator.Mode.RETURN||state.mode()==GroupCohesionCoordinator.Mode.REGROUP);
        boolean hold=state!=null&&state.mode()==GroupCohesionCoordinator.Mode.HOLD;
        if(regroup){
            var center=state.rally();
            if(center==null)goal=new GroupPatrolCoordinator.Goal(goal.waypoint(),goal.center(),goal.destination(),false,false);
            else{
                int dx=goal.destination().x()-goal.center().x(),dy=goal.destination().y()-goal.center().y();
                var offset=new WalkingPatternSpec.Point(Math.max(0,center.x()+dx),Math.max(0,center.y()+dy),center.z());
                var area=human.profile.walkingPatterns().stream().filter(p->p.slot().equals("background")).findFirst().orElseThrow().area();
                if(area!=null)offset=new WalkingPatternSpec.Point(Math.clamp(offset.x(),area.x1(),area.x2()),Math.clamp(offset.y(),area.y1(),area.y2()),offset.z());
                var scope=human.walking.scope("background");
                if(scope==null||!scope.walkable(new zombie.apocalipse.humans.movement.MovementGrid.Tile(offset.x(),offset.y())))offset=center;
                goal=new GroupPatrolCoordinator.Goal(goal.waypoint(),center,offset,valid,false);
            }
        }
        var companions=new ArrayList<WalkingPatternSpec.Point>();
        for(var other:entries.values())if(other.human!=human&&other.human.definition.group().groupId().equals(human.definition.group().groupId())){
            var peer=samples.get(other.human.definition.key());
            if(peer!=null&&sample!=null&&peer.at()==sample.at()&&peer.member.z()==sample.member.z())
                companions.add(new WalkingPatternSpec.Point(Math.max(0,(int)Math.floor(peer.member.x())),Math.max(0,(int)Math.floor(peer.member.y())),peer.member.z()));
        }
        return new GroupPatrolPort.Observation(valid?sample.at:0,goal,
            valid?sample.member.x():0,valid?sample.member.y():0,valid?sample.member.z():0,valid&&sample.inside,
            state==null?0:state.revision(),regroup,hold,companions);
    }
    private WalkingPatternSpec.Point rally(ServerHuman reference,GroupCohesionCoordinator state,
            List<GroupCohesionCoordinator.Member> roster,GroupPatrolCoordinator patrol){
        var scope=reference.walking.scope("background");if(scope==null)return null;
        if(state.rally()!=null&&scope.walkable(new zombie.apocalipse.humans.movement.MovementGrid.Tile(state.rally().x(),state.rally().y())))return state.rally();
        boolean outside=roster.stream().anyMatch(m->!m.inside());double spread=0;
        for(var a:roster)for(var b:roster)spread=Math.max(spread,Math.hypot(a.pose().x()-b.pose().x(),a.pose().y()-b.pose().y()));
        int maxSlot=roster.stream().mapToInt(m->m.pose().slot()).max().orElse(0);
        if(!outside&&spread<=GroupCohesionCoordinator.separationLimit(maxSlot)&&state.mode()==GroupCohesionCoordinator.Mode.PATROL)return null;
        int x=(int)Math.floor(roster.stream().mapToDouble(m->m.pose().x()).average().orElseThrow());
        int y=(int)Math.floor(roster.stream().mapToDouble(m->m.pose().y()).average().orElseThrow());
        var pattern=reference.profile.walkingPatterns().stream().filter(p->p.slot().equals("background")).findFirst().orElseThrow();
        var area=pattern.area();
        if(area!=null){x=Math.clamp(x,area.x1(),area.x2());y=Math.clamp(y,area.y1(),area.y2());}
        else{x=patrol.route().get(patrol.waypoint()).x();y=patrol.route().get(patrol.waypoint()).y();}
        // One shared candidate, bounded to 49 static queries. No actor-local home fallback.
        for(int radius=0;radius<=3;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++){
            if(Math.max(Math.abs(dx),Math.abs(dy))!=radius)continue;
            long tx=(long)x+dx,ty=(long)y+dy;if(tx<0||ty<0||tx>=Integer.MAX_VALUE||ty>=Integer.MAX_VALUE)continue;
            var tile=new zombie.apocalipse.humans.movement.MovementGrid.Tile((int)tx,(int)ty);
            if(scope.walkable(tile))return new WalkingPatternSpec.Point(tile.x(),tile.y(),patrol.route().get(patrol.waypoint()).z());
        }
        return null;
    }
    /** Authenticated aggression only. Identity/visibility/PvP is revalidated by each member's existing combat adapter. */
    void attacked(ServerHuman victim,HumanObservation.Identity attacker,long now){
        String group=victim.definition.group().groupId();
        for(var entry:entries.values())if(entry.human!=victim&&!group.isEmpty()&&entry.human.definition.group().groupId().equals(group)&&!entry.human.state.snapshot().dead()){
            var retaliation=ServerHumanRetaliation.module(entry.human);if(retaliation!=null)retaliation.attacked(attacker,now);
        }
    }
}
