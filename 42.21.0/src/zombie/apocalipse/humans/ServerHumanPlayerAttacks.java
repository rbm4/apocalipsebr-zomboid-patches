package zombie.apocalipse.humans;

import zombie.apocalipse.humans.brain.HumanObservation;
import zombie.apocalipse.humans.encounters.HumanRelationships;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameServer;
import zombie.network.fields.hit.Player;

/** Main-thread validated intake for the stock client's PlayerHitPlayer wire contract. */
public final class ServerHumanPlayerAttacks {
    private ServerHumanPlayerAttacks(){ }
    static void ready(){
        if(Boolean.parseBoolean(System.getProperty("apocbr.npc.playerHitTrace","true")))
            zombie.debug.DebugLog.log("[ApocBR][NPC][PlayerHit] server intake ready; native PlayerHitPlayer damage and PlayerHitSquare diagnostics; trace=true");
    }
    private static long nextReceiveTrace;
    public static void received(){
        if(!GameServer.server||!Boolean.parseBoolean(System.getProperty("apocbr.npc.playerHitTrace","true")))return;
        long now=System.nanoTime();if(nextReceiveTrace!=0&&now<nextReceiveTrace)return;nextReceiveTrace=now+1_000_000_000L;
        zombie.debug.DebugLog.log("[ApocBR][NPC][PlayerHit] received native PlayerHitPlayer payload; parsing");
    }
    public static boolean isNpc(IsoPlayer player){return player!=null&&GameServer.server&&ServerHumanFactory.tradingHuman(player.getOnlineID())!=null
        &&ServerHumanFactory.tradingHuman(player.getOnlineID()).player==player;}
    /** Resolve only the detached target, never the attacker's identity or arbitrary PlayerID traffic. */
    public static void resolve(Player field){
        if(!GameServer.server||field.getPlayer()!=null)return;
        var human=ServerHumanFactory.tradingHuman(field.getID());
        if(human!=null){field.set(human.player,false);trace(human,"parsed native PlayerHitPlayer target");}
    }
    public static void trace(IsoPlayer victim,String reason){if(isNpc(victim))trace(ServerHumanFactory.tradingHuman(victim.getOnlineID()),reason);}
    private static void trace(ServerHuman human,String reason){
        if(!Boolean.parseBoolean(System.getProperty("apocbr.npc.playerHitTrace","true")))return;
        long now=System.nanoTime();if(human.nextPlayerHitTrace==0||now>=human.nextPlayerHitTrace){human.nextPlayerHitTrace=now+1_000_000_000L;human.playerHitTraceLines=0;}
        if(++human.playerHitTraceLines>8)return;
        zombie.debug.DebugLog.log("[ApocBR][NPC][PlayerHit] key="+human.definition.key()+"; "+reason
            +"; profile="+human.profile.id()+"@"+human.profile.revision()+"; god="+human.player.isGodMod()+"; safety="+human.player.getSafety().isEnabled());
    }
    private static long nextMissTrace;
    public static void missed(){
        if(!GameServer.server||!Boolean.parseBoolean(System.getProperty("apocbr.npc.playerHitTrace","true")))return;
        long now=System.nanoTime();if(nextMissTrace!=0&&now<nextMissTrace)return;nextMissTrace=now+1_000_000_000L;
        zombie.debug.DebugLog.log("[ApocBR][NPC][PlayerHit] native PlayerHitSquare; client reported no character hit; no NPC damage or retaliation inferred");
    }
    private static boolean reject(IsoPlayer victim,String reason){trace(victim,"rejected: "+reason);return false;}
    public static boolean receive(IsoPlayer attacker,IsoPlayer victim,HandWeapon weapon,UdpConnection connection,int hits,byte shot){
        if(!isNpc(victim))return false;
        if(victim.isGodMod())return reject(victim,"NPC god mode");
        if(attacker==null||connection==null||!connection.isFullyConnected()
            ||GameServer.IDToPlayerMap.get(attacker.getOnlineID())!=attacker||GameServer.getConnectionFromPlayer(attacker)!=connection)return reject(victim,"attacker connection ownership");
        if(weapon==null||weapon!=attacker.getPrimaryHandItem()&&weapon!=attacker.bareHands)return reject(victim,"equipped weapon");
        if(hits<1||hits>32)return reject(victim,"hit count");
        if(attacker.isDead()||victim.isDead()||attacker.getZi()!=victim.getZi()||attacker==victim)return reject(victim,"life/floor/self");
        var human=ServerHumanFactory.tradingHuman(victim.getOnlineID());
        // Incoming vulnerability belongs to the victim. The attacker's god/invisible
        // flags constrain retaliation, not their ability to inflict an authenticated hit.
        if(human.state.snapshot().dead())return reject(victim,"logical death");
        if(!ServerHumanRetaliation.pvp(attacker,victim,weapon.isRanged()))return reject(victim,"PvP/safety/faction/non-PvP zone");
        if(!Float.isFinite(attacker.getX())||!Float.isFinite(attacker.getY())
            ||Math.hypot(attacker.getX()-victim.getX(),attacker.getY()-victim.getY())>Math.min(32,weapon.getMaxRange(attacker)+1.2))return reject(victim,"weapon range/position");
        if(!ServerHumanAttackLine.clear(human,attacker,victim.getX(),victim.getY(),weapon.isRanged()))return reject(victim,"attack line/bystander");
        long now=System.nanoTime();
        if(human.lastPlayerAttacker==attacker&&human.lastPlayerShot==shot&&human.lastPlayerWeapon==weapon.getID()&&now-human.lastPlayerAttackAt<500_000_000L)return reject(victim,"duplicate shot");
        human.lastPlayerAttacker=attacker;human.lastPlayerShot=shot;human.lastPlayerWeapon=weapon.getID();human.lastPlayerAttackAt=now;
        var module=ServerHumanRetaliation.module(human);
        if(module!=null)module.attacked(new HumanObservation.Identity(attacker.getOnlineID(),attacker.getID()),now);
        trace(human,"accepted; retaliationModule="+(module!=null)+"; attackerGod="+attacker.isGodMod()+"; attackerInvisible="+attacker.isInvisible());
        if(human.profile.encounter().enabled())new ServerHumanEncounters(human).record(new zombie.apocalipse.humans.encounters.EncounterPort.Player(
            new HumanObservation.Identity(attacker.getOnlineID(),attacker.getID()),ServerHumanEncounters.key(human,attacker),0,true,false),HumanRelationships.Outcome.ATTACKED,human.profile.encounter());
        ServerHumanDialogue.close(human,"attacked");ServerHumanTrading.close(human);
        human.speech.attacked(attacker,weapon,now);
        HumanLog.log(HumanLog.Level.INFO,()->"player hit accepted key="+human.definition.key()+"; retaliation="+(module!=null));
        return true;
    }
}
