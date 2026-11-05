package zombie.apocalipse.humans;

import java.util.*;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.encounters.*;
import zombie.apocalipse.humans.dialogue.HumanLanguage;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameServer;
import zombie.savefile.ApocBRNpcStore;

/** Native sensing and shared semantic-memory adapter; simulation-thread callers only. */
final class ServerHumanEncounters implements EncounterPort {
    private static final HumanRelationships STANDALONE=new HumanRelationships();
    private final ServerHuman human;
    ServerHumanEncounters(ServerHuman human){this.human=human;}
    static void bind(ServerHuman human){
        if(human.activity.behavior() instanceof HumanModuleRuntime runtime){
            var module=runtime.module(PlayerEncounterModule.class);if(module!=null)module.bind(new ServerHumanEncounters(human));
        }
    }
    static HumanRelationships memory(){var store=ApocBRNpcStore.current();return store==null?STANDALONE:store.relationships();}
    static HumanRelationships.Key key(ServerHuman human,IsoPlayer player){
        long steam=player.getSteamID();return new HumanRelationships.Key(human.profile.encounter().affiliation(),steam==0?"username":"steam",
            steam==0?player.getUsername():Long.toUnsignedString(steam));
    }
    private static boolean weapon(InventoryItem item){return item instanceof HandWeapon&&!"Base.BareHands".equals(item.getFullType());}
    static boolean armed(IsoPlayer player){return weapon(player.getPrimaryHandItem())||weapon(player.getSecondaryHandItem());}
    static boolean authorized(ServerHuman human,IsoPlayer player){
        if(!human.profile.encounter().enabled())return true;
        var store=ApocBRNpcStore.current();
        if(ApocBRNpcStore.enabled()&&(store==null||!store.healthy()))return false;
        return !armed(player)&&memory().get(key(human,player)).permits(System.currentTimeMillis());
    }
    public List<Player> players(){
        var result=new ArrayList<Player>();
        for(var visitor:human.perception.observe(System.nanoTime()).visitors()){
            if(result.size()>=32)break;
            var p=GameServer.IDToPlayerMap.get(visitor.identity().onlineId());
            if(p==null||p.getID()!=visitor.identity().instanceId()||!human.perception.eligible(p)||!human.perception.visible(p)
                ||p.getZi()!=human.player.getZi())continue;
            double dx=human.player.getX()-p.getX(),dy=human.player.getY()-p.getY(),distance=dx*dx+dy*dy;
            double angle=p.getDirectionAngleRadians();
            boolean aimed=p.isAiming()&&distance>.01&&(dx*Math.cos(angle)+dy*Math.sin(angle))/Math.sqrt(distance)>=Math.cos(Math.toRadians(12));
            result.add(new Player(visitor.identity(),key(human,p),distance,armed(p),aimed));
        }
        return List.copyOf(result);
    }
    public HumanRelationships.State relationship(Player p){return memory().get(p.account());}
    public long wallTimeMs(){return System.currentTimeMillis();}
    public boolean claim(Player p,Object owner,long now){return memory().claim(p.account(),owner,now);}
    public void release(Player p,Object owner){memory().release(p.account(),owner);}
    public boolean record(Player p,HumanRelationships.Outcome outcome,EncounterPolicy policy){
        var store=ApocBRNpcStore.current();
        if(ApocBRNpcStore.enabled()&&(store==null||!store.healthy()))return false;
        boolean accepted=memory().record(p.account(),human.definition.key(),outcome,wallTimeMs(),policy);
        // Standalone memory has no journal producer; retain semantic state without a pending queue.
        if(store==null)for(var event:memory().pending())memory().staged(event.id());
        if(accepted)HumanLog.log(HumanLog.Level.DEBUG,()->"encounter key="+human.definition.key()+"; outcome="+outcome);
        return accepted;
    }
    public String text(String event){return HumanLanguage.text("NPC_Encounter_"+event);}
}
