package zombie.apocalipse.humans;

import zombie.apocalipse.humans.brain.HumanObservation;
import zombie.apocalipse.humans.encounters.HumanRelationships;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameServer;
import zombie.network.fields.hit.Player;

/** Main-thread validated packet intake. NPC hits are attempts only until incoming damage is implemented. */
public final class ServerHumanPlayerAttacks {
    private ServerHumanPlayerAttacks(){ }
    public static boolean isNpc(IsoPlayer player){return player!=null&&GameServer.server&&ServerHumanFactory.tradingHuman(player.getOnlineID())!=null
        &&ServerHumanFactory.tradingHuman(player.getOnlineID()).player==player;}
    /** Resolve only the detached target, never the attacker's identity or arbitrary PlayerID traffic. */
    public static void resolve(Player field){
        if(!GameServer.server||field.getPlayer()!=null)return;
        var human=ServerHumanFactory.tradingHuman(field.getID());
        if(human!=null)field.set(human.player,false);
    }
    public static boolean receive(IsoPlayer attacker,IsoPlayer victim,HandWeapon weapon,UdpConnection connection,int hits,byte shot){
        if(!isNpc(victim)||victim.isGodMod()||attacker==null||weapon==null||hits<1||hits>32||connection==null||!connection.isFullyConnected()
            ||GameServer.IDToPlayerMap.get(attacker.getOnlineID())!=attacker||GameServer.getConnectionFromPlayer(attacker)!=connection
            ||attacker.isDead()||attacker.getZi()!=victim.getZi()||attacker==victim
            ||weapon!=attacker.getPrimaryHandItem()&&weapon!=attacker.bareHands)return false;
        var human=ServerHumanFactory.tradingHuman(victim.getOnlineID());
        // PvP, safe zones and live ownership constrain attempts even though NPC damage is deferred.
        if(!ServerHumanRetaliation.pvp(victim,attacker,weapon.isRanged())||!human.perception.eligible(attacker)
            ||Math.hypot(attacker.getX()-victim.getX(),attacker.getY()-victim.getY())>Math.min(32,weapon.getMaxRange(attacker)+1.2)
            ||!ServerHumanAttackLine.clear(human,attacker,victim.getX(),victim.getY(),weapon.isRanged()))return false;
        long now=System.nanoTime();
        if(human.lastPlayerAttacker==attacker&&human.lastPlayerShot==shot&&human.lastPlayerWeapon==weapon.getID()&&now-human.lastPlayerAttackAt<500_000_000L)return false;
        human.lastPlayerAttacker=attacker;human.lastPlayerShot=shot;human.lastPlayerWeapon=weapon.getID();human.lastPlayerAttackAt=now;
        var module=ServerHumanRetaliation.module(human);
        if(module!=null)module.attacked(new HumanObservation.Identity(attacker.getOnlineID(),attacker.getID()),now);
        if(human.profile.encounter().enabled())new ServerHumanEncounters(human).record(new zombie.apocalipse.humans.encounters.EncounterPort.Player(
            new HumanObservation.Identity(attacker.getOnlineID(),attacker.getID()),ServerHumanEncounters.key(human,attacker),0,true,false),HumanRelationships.Outcome.ATTACKED,human.profile.encounter());
        ServerHumanDialogue.close(human,"attacked");ServerHumanTrading.close(human);
        HumanLog.log(HumanLog.Level.DEBUG,()->"player attack attempt key="+human.definition.key()+"; retaliation="+(module!=null)+"; incomingDamage=deferred");
        return true;
    }
}
