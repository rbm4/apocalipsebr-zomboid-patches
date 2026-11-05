package zombie.apocalipse.humans;

import java.util.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.groups.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec;

/** Factory-owned composition/adapter. Main thread captures positions; worker policy sees detached values. */
final class ServerHumanGroups {
    private record Entry(ServerHuman human,GroupPatrolModule module) { }
    private record Sample(long at,GroupPatrolCoordinator.Member member,boolean inside) { }
    private final Map<String,Entry> entries=new HashMap<>();
    private final Map<String,GroupPatrolCoordinator> groups=new HashMap<>();
    private Map<String,Sample> samples=Map.of();
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
        groups.computeIfAbsent(membership.groupId(),id->new GroupPatrolCoordinator(pattern.points(),2,2));
        entries.put(human.definition.key(),new Entry(human,module));
        module.bind(()->observe(human),membership.slot());
    }
    void remove(ServerHuman human){
        entries.remove(human.definition.key());String group=human.definition.group().groupId();
        if(entries.values().stream().noneMatch(e->e.human.definition.group().groupId().equals(group)))groups.remove(group);
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
    }
    /** Pure group policy, once per NPC tick; never touches live positions or native navigation. */
    void update(long now){
        for(var entry:groups.entrySet()){
            var members=new ArrayList<GroupPatrolCoordinator.Member>();
            boolean complete=true;
            for(var actor:entries.values())if(actor.human.definition.group().groupId().equals(entry.getKey())){
                var s=samples.get(actor.human.definition.key());
                if(s!=null&&now>=s.at&&now-s.at<=500_000_000L&&s.member.incarnation().equals(actor.human.incarnation))members.add(s.member);
                else complete=false;
            }
            if(!complete)continue;
            var before=entry.getValue().phase();entry.getValue().update(now,members);
            if(before!=entry.getValue().phase())HumanLog.log(HumanLog.Level.DEBUG,()->"group patrol id="+entry.getKey()+"; intent=PATROL; phase="+entry.getValue().phase()+"; waypoint="+entry.getValue().waypoint());
        }
    }
    private GroupPatrolPort.Observation observe(ServerHuman human){
        var sample=samples.get(human.definition.key());var group=groups.get(human.definition.group().groupId());
        var entry=entries.get(human.definition.key());
        boolean valid=entry!=null&&entry.human==human&&sample!=null&&sample.member.incarnation().equals(human.incarnation)&&group!=null;
        var goal=group==null?new GroupPatrolCoordinator.Goal(-1,new WalkingPatternSpec.Point(0,0,0),new WalkingPatternSpec.Point(0,0,0),false):group.goal(human.definition.group().slot(),valid);
        return new GroupPatrolPort.Observation(valid?sample.at:0,goal,
            valid?sample.member.x():0,valid?sample.member.y():0,valid?sample.member.z():0,valid&&sample.inside);
    }
    /** Authenticated aggression only. Identity/visibility/PvP is revalidated by each member's existing combat adapter. */
    void attacked(ServerHuman victim,HumanObservation.Identity attacker,long now){
        String group=victim.definition.group().groupId();
        for(var entry:entries.values())if(entry.human!=victim&&!group.isEmpty()&&entry.human.definition.group().groupId().equals(group)&&!entry.human.state.snapshot().dead()){
            var retaliation=ServerHumanRetaliation.module(entry.human);if(retaliation!=null)retaliation.attacked(attacker,now);
        }
    }
}
