package zombie.apocalipse.humans;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.speech.*;
import zombie.characters.IsoPlayer;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameServer;
import zombie.savefile.ApocBRNpcStore;

/** Simulation-thread composition/delivery adapter. Speech never owns movement or weapon timing. */
final class ServerHumanSpeech {
    private static final HumanSpeechMemory STANDALONE=new HumanSpeechMemory();
    private final ServerHuman human;
    final HumanSpeechEngine engine;
    private final HumanSpeechMemory memory;
    private HumanActionExecution execution;
    private HumanSpeechEngine.Decision pending;
    private HumanObservation.Identity addressed;
    private long expires;
    private final HumanActuator output=new HumanActuator(){
        public boolean face(HumanObservation.Identity ignored){return false;}
        public boolean startEmote(String ignored){return false;}
        public void stopEmote(){}
        public boolean returnHome(){return false;}
        public boolean say(String text){
            if(pending==null)return false;
            if(pending.hey()&&text.equals(heyText()))ServerHumanTransport.hey(human);
            boolean delivered=ServerHumanTransport.shout(human,text);
            if(delivered&&text.equals(pending.text()))engine.delivered(pending);
            drainStandalone();return delivered;
        }
    };
    ServerHumanSpeech(ServerHuman human){
        this.human=human;var store=ApocBRNpcStore.current();memory=store==null?STANDALONE:store.speechMemory();
        String locale=zombie.apocalipse.humans.dialogue.HumanLanguage.locale();
        engine=new HumanSpeechEngine(human.definition.key(),"EN".equals(locale)?"EN":"PTBR",memory,HumanSpeechCatalog::current,()->ThreadLocalRandom.current().nextDouble());
    }
    private String heyText(){return "EN".equals(zombie.apocalipse.humans.dialogue.HumanLanguage.locale())?"Hey!":"Ei!";}
    SpeechContext context(IsoPlayer player,float damage,HandWeapon weapon){
        SpeechContext.Player identity=null;
        if(player!=null){long steam=player.getSteamID();identity=new SpeechContext.Player(steam==0?"username":"steam",steam==0?player.getUsername():Long.toUnsignedString(steam),player.getUsername());}
        return new SpeechContext(identity,human.state.snapshot().vitals().health(),damage,SpeechContext.Temperament.STEADY,
            weapon==null?SpeechContext.AttackKind.OTHER:weapon.isRanged()?SpeechContext.AttackKind.FIREARM:SpeechContext.AttackKind.MELEE,player==null?0:1,false);
    }
    HumanSpeechEngine.Decision attacked(IsoPlayer player,HandWeapon weapon,long now){
        var result=engine.onAttackedByPlayer(context(player,0,weapon),now,System.currentTimeMillis());
        offer(result,new HumanObservation.Identity(player.getOnlineID(),player.getID()),now);drainStandalone();return result;
    }
    HumanSpeechEngine.Decision damage(IsoPlayer player,float amount,HandWeapon weapon,long now){
        var result=engine.onDamageTaken(context(player,amount,weapon),now);offer(result,null,now);return result;
    }
    HumanSpeechEngine.Decision attack(IsoPlayer player,HandWeapon weapon,long now){
        var result=engine.onAttackPlayer(context(player,0,weapon),now);offer(result,null,now);return result;
    }
    HumanSpeechEngine.Decision zombieKilled(long now){
        var result=engine.onZombieKill(context(null,0,null),now);offer(result,null,now);return result;
    }
    private void offer(HumanSpeechEngine.Decision decision,HumanObservation.Identity target,long now){
        if(!decision.speaks())return;
        if(pending!=null&&pending.priority()>=decision.priority())return;
        cancel();pending=decision;addressed=target;expires=now+3_000_000_000L;
        HumanAction plan=decision.hey()?new HumanAction.Sequence(List.of(new HumanAction.Say(heyText()),new HumanAction.Wait(350_000_000L),new HumanAction.Say(decision.text())))
            :new HumanAction.Say(decision.text());
        execution=new HumanActionExecution(plan);
    }
    void update(long now){
        if(execution==null)return;
        if(now>=expires||human.state.snapshot().dead()||human.state.snapshot().injuries().downed()
            ||human.activity.mode()!=HumanActivityController.Mode.AUTONOMOUS){cancel();return;}
        if(addressed!=null){var p=GameServer.IDToPlayerMap.get(addressed.onlineId());if(p==null||p.getID()!=addressed.instanceId()){cancel();return;}}
        execution.update(now,output);if(execution.terminal()){execution=null;pending=null;addressed=null;}
    }
    void cancel(){if(execution!=null)execution.cancel(output);execution=null;pending=null;addressed=null;}
    private void drainStandalone(){if(ApocBRNpcStore.current()==null)for(var change:memory.pending())memory.staged(change);}
}
