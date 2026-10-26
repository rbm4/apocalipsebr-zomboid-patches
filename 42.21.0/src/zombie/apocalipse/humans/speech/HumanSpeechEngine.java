package zombie.apocalipse.humans.speech;

import java.util.*;
import java.util.function.*;

/** Per-actor policy. No packets, world references, JDBC, movement or combat side effects. */
public final class HumanSpeechEngine {
    public enum Reaction { NONE, ALERT, DEFEND, PLEAD, PAIN, RETREAT, SURRENDER, CONFIRM_KILL }
    public enum Status { READY, COOLDOWN, CHANCE, NO_CONTENT, DEAD, MEMORY_FULL }
    public record Decision(SpeechEvent event,Reaction reaction,Status status,String messageId,String text,
                           boolean hey,int priority) {
        public boolean speaks(){return status==Status.READY;}
    }
    private final String npc,locale;
    private final HumanSpeechMemory memory;
    private final Supplier<HumanSpeechCatalog> catalog;
    private final DoubleSupplier random;
    private final EnumMap<SpeechEvent,Long> cooldowns=new EnumMap<>(SpeechEvent.class);
    private long nextSpeech;
    private int lastPriority;
    public HumanSpeechEngine(String npc,String locale,HumanSpeechMemory memory,Supplier<HumanSpeechCatalog> catalog,DoubleSupplier random){
        new HumanSpeechMemory.Key(npc,"npc","_");
        if(!Set.of("EN","PTBR").contains(locale))throw new IllegalArgumentException("Invalid speech locale");
        this.npc=npc;this.locale=locale;this.memory=Objects.requireNonNull(memory);this.catalog=Objects.requireNonNull(catalog);this.random=Objects.requireNonNull(random);
    }
    private HumanSpeechMemory.Key speaker(){return new HumanSpeechMemory.Key(npc,"npc","_");}
    private double roll(){double n=random.getAsDouble();if(!Double.isFinite(n))throw new IllegalStateException("Invalid random source");return Math.clamp(n,0,Math.nextDown(1.0));}
    public Decision onAttackedByPlayer(SpeechContext c,long now,long wallMs){
        if(c.player()==null)throw new IllegalArgumentException("Player attack requires identity");
        if(c.healthRatio()==0)return silent(SpeechEvent.ATTACKED_BY_PLAYER,Reaction.NONE,Status.DEAD);
        var attack=memory.attacked(new HumanSpeechMemory.Key(npc,c.player().kind(),c.player().account()),wallMs);
        var event=attack.repeated()?SpeechEvent.REPEATED_HIT:SpeechEvent.FIRST_HIT;
        Reaction reaction;
        if(c.npcStartedFight())reaction=c.healthRatio()<=.35F
            ?(c.temperament()==SpeechContext.Temperament.VETERAN||c.temperament()==SpeechContext.Temperament.PREDATOR?Reaction.RETREAT:Reaction.SURRENDER):Reaction.PAIN;
        else reaction=c.temperament()==SpeechContext.Temperament.FRESH&&!attack.repeated()&&roll()<.5?Reaction.PLEAD:Reaction.DEFEND;
        if(!attack.accepted())return silent(event,reaction,Status.MEMORY_FULL);
        // A fighting initiator must not claim the other player started the fight.
        return evaluate(c.npcStartedFight()?SpeechEvent.DAMAGE_TAKEN:event,c,now,reaction,!attack.repeated()&&!c.npcStartedFight());
    }
    public Decision onAttackPlayer(SpeechContext c,long now){return onEvent(SpeechEvent.ATTACK_PLAYER,c,now);}
    /** Call only for an authoritative death attributed to this NPC, once per victim. */
    public Decision onZombieKill(SpeechContext c,long now){return onEvent(SpeechEvent.ZOMBIE_KILL,c,now);}
    public Decision onDamageTaken(SpeechContext c,long now){return onEvent(SpeechEvent.DAMAGE_TAKEN,c,now);}
    /** Extension point for chase, reload, allies, injury and future situations. Missing pools are explicit no-ops. */
    public Decision onEvent(SpeechEvent event,SpeechContext c,long now){
        Objects.requireNonNull(event);Objects.requireNonNull(c);
        var reaction=switch(event){
            case FIRST_HIT, NEW_AGGRESSOR, MULTIPLE_AGGRESSORS -> Reaction.ALERT;
            case REPEATED_HIT, ATTACKED_BY_PLAYER, ATTACK_PLAYER, CHASE_STARTED, CHASING -> Reaction.DEFEND;
            case DAMAGE_TAKEN, BLEEDING, LOW_HEALTH -> Reaction.PAIN;
            case RETREAT -> Reaction.RETREAT;case SURRENDER -> Reaction.SURRENDER;
            case ZOMBIE_KILL -> Reaction.CONFIRM_KILL;default -> Reaction.NONE;
        };
        if(event==SpeechEvent.DAMAGE_TAKEN&&c.damage()<=0)return silent(event,reaction,Status.NO_CONTENT);
        return evaluate(event,c,now,reaction,false);
    }
    private Decision evaluate(SpeechEvent event,SpeechContext c,long now,Reaction reaction,boolean hey){
        if(c.healthRatio()==0)return silent(event,reaction,Status.DEAD);
        var content=Objects.requireNonNull(catalog.get());var rule=content.rule(event);var family=event.pool();
        var eligible=content.lines(event,locale).stream().filter(line->line.fits(c)).toList();
        if(eligible.isEmpty())return silent(event,reaction,Status.NO_CONTENT);
        int priority=hey?100:rule.priority();
        if(now<nextSpeech&&priority<=lastPriority||now<cooldowns.getOrDefault(family,Long.MIN_VALUE))return silent(event,reaction,Status.COOLDOWN);
        // One chance roll per event, even when the caller subsequently has no viewer.
        cooldowns.put(family,now+Math.max(1000,rule.cooldownMs())*1_000_000L);
        if(!hey&&roll()*100>=rule.chance())return silent(event,reaction,Status.CHANCE);
        var old=memory.get(speaker());var recent=old==null?List.<String>of():old.recent();
        var fresh=eligible.stream().filter(line->!recent.contains(line.id())).toList();
        if(fresh.isEmpty()){
            int oldest=eligible.stream().mapToInt(line->recent.indexOf(line.id())).min().orElse(-1);
            fresh=eligible.stream().filter(line->recent.indexOf(line.id())==oldest).toList();
        }
        double needle=roll()*fresh.stream().mapToLong(HumanSpeechCatalog.Line::weight).sum();
        var chosen=fresh.getLast();for(var line:fresh){needle-=line.weight();if(needle<0){chosen=line;break;}}
        String text=chosen.text().replace("{username}",c.safeUsername());
        if(text.length()>256)return silent(event,reaction,Status.NO_CONTENT);
        nextSpeech=now+6_000_000_000L;
        lastPriority=priority;
        return new Decision(event,reaction,Status.READY,chosen.id(),text,hey,priority);
    }
    private static Decision silent(SpeechEvent event,Reaction reaction,Status reason){return new Decision(event,reaction,reason,"","",false,0);}
    /** Only delivered text enters repeat avoidance. No cooldown or active execution is durable. */
    public boolean delivered(Decision decision){return decision.speaks()&&memory.remember(speaker(),decision.messageId());}
    public void cancel(){nextSpeech=0;lastPriority=0;cooldowns.clear();}
}
