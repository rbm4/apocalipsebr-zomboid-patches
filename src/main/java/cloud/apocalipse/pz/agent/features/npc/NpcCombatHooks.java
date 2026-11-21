package cloud.apocalipse.pz.agent.features.npc;

import java.util.*;
import java.lang.reflect.*;
import zombie.CombatManager;
import zombie.characters.*;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.fields.character.CharacterID;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.fields.hit.*;
import zombie.network.packets.hit.*;

/** Per-packet extension state, exact native damage once, and envelope authorization. Server owner only. */
public final class NpcCombatHooks {
    private static final Map<Object,Claim> CLAIMS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Claim {
        int start,targetStart;boolean npc,accepted,processed,relay,running;byte[] fingerprint;float health;
    }
    public static void parsing(Object packet,Object input){
        CLAIMS.remove(packet);if(!ServerHumanFactory.ready())return;
        var claim=new Claim();claim.start=((ByteBufferReader)input).position();CLAIMS.put(packet,claim);
    }
    public static void targetStart(Object packet,Object input){var claim=CLAIMS.get(packet);if(claim!=null)claim.targetStart=((ByteBufferReader)input).position();}
    public static void parsed(Object object,Object input,Throwable failure){
        var claim=CLAIMS.get(object);if(claim==null)return;
        if(failure!=null){CLAIMS.remove(object);return;}
        var reader=(ByteBufferReader)input;var packet=(PlayerHitPlayerPacket)object;
        if(reader.bb.get(claim.targetStart+Short.BYTES)!=CharacterID.CharacterType.PLAYER.ordinal())return;
        ServerHumanPlayerAttacks.resolve(packet.target);
        claim.npc=ServerHumanPlayerAttacks.isNpc(packet.target.getPlayer());
        if(claim.npc){ServerHumanPlayerAttacks.received();claim.fingerprint=ServerHumanPlayerAttacks.fingerprint(reader.bb,claim.start,reader.position());}
    }
    public static void resetPacket(Object packet){CLAIMS.remove(packet);}
    public static void filter(Object envelope,Object connection){
        if(!ServerHumanFactory.ready())return;
        var packets=(List<?>)NpcNativeAccess.get(envelope,"packets");
        var wielder=((ConnectionPlayer)NpcNativeAccess.get(envelope,"player")).getPlayer();
        var seen=Collections.newSetFromMap(new IdentityHashMap<IsoPlayer,Boolean>());boolean removed=false;
        for(var iterator=packets.iterator();iterator.hasNext();){
            var entry=iterator.next();var hit=(PlayerHit)NpcNativeAccess.get(entry,"playerHitPacket");
            var claim=CLAIMS.get(hit);if(claim==null||!claim.npc)continue;
            var child=(PlayerHitPlayerPacket)hit;
            var hits=(List<Hit>)NpcNativeAccess.get(child,"hits");
            claim.accepted=!claim.processed&&child.getPlayer()==wielder&&seen.add(child.target.getPlayer())
                &&ServerHumanPlayerDamage.valid(hits,child.getHandWeapon())
                &&ServerHumanPlayerAttacks.receive(child.getPlayer(),child.target.getPlayer(),child.getHandWeapon(),(UdpConnection)connection,child.getHitCount(),claim.fingerprint);
            if(!claim.accepted){iterator.remove();removed=true;}
        }
        if(removed){
            IsoGameCharacter closest=null;float distance=0;
            for(var entry:packets){var hit=(PlayerHit)NpcNativeAccess.get(entry,"playerHitPacket");
                if(hit.getTarget() instanceof IsoGameCharacter target&&(closest==null||hit.getDistance()<distance)){closest=target;distance=hit.getDistance();}}
            NpcNativeAccess.set(envelope,"closestTarget",closest);
        }
    }
    /** Runs at the original HitCharacter default owner, not a nonexistent child override. */
    public static boolean beforeProcess(Object object){
        var claim=CLAIMS.get(object);if(claim==null||!claim.npc)return false;
        claim.running=false;
        if(!claim.accepted||claim.processed)return true;
        claim.processed=true;var packet=(PlayerHitPlayerPacket)object;var victim=packet.target.getPlayer();
        if(!ServerHumanPlayerAttacks.isNpc(victim)||victim.isDead()||victim.isGodMod())return true;
        claim.health=ServerHumanPlayerDamage.health(victim);claim.running=true;return false;
    }
    public static void afterProcess(Object object){
        var claim=CLAIMS.get(object);if(claim==null||!claim.running)return;claim.running=false;
        var packet=(PlayerHitPlayerPacket)object;var victim=packet.target.getPlayer();
        ServerHumanPlayerDamage.importLoss(packet.getPlayer(),victim,packet.getHandWeapon(),claim.health);
        packet.target.set(victim);((Fall)NpcNativeAccess.get(packet,"fall")).set(0,0,(byte)0,0);
        claim.relay=ServerHumanPlayerAttacks.isNpc(victim);
    }
    public static void filterRelay(Object envelope){
        ((List<?>)NpcNativeAccess.get(envelope,"packets")).removeIf(entry->{
            var claim=CLAIMS.get(NpcNativeAccess.get(entry,"playerHitPacket"));return claim!=null&&claim.npc&&!claim.relay;
        });
    }
    public static boolean sanitize(Object object){
        var claim=CLAIMS.get(object);if(claim==null||!claim.npc)return false;
        var packet=(PlayerHitPlayerPacket)object;packet.target.set(packet.target.getPlayer());return true;
    }
    private static final class FallMethod {
        static final Method VALUE=find();
        private static Method find(){try{var m=PlayerHit.class.getDeclaredMethod("processFall",Fall.class,IsoGameCharacter.class);m.setAccessible(true);return m;}
            catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}}
    }
    public static void processFall(Object packet,Object fall,Object target){
        var claim=CLAIMS.get(packet);if(claim!=null&&claim.npc)return;
        try{FallMethod.VALUE.invoke(packet,fall,target);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException r)throw r;if(failure.getCause() instanceof Error e)throw e;throw new IllegalStateException(failure.getCause());}
        catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    public static void rolled(Object hit,Object object,boolean success){
        if(!success||!ServerHumanFactory.ready()||!(object instanceof IsoPlayer victim)||!ServerHumanPlayerAttacks.isNpc(victim))return;
        var data=(CombatManager.HitData)NpcNativeAccess.get(hit,"hitData");if(data==null)return;
        NpcNativeAccess.set(hit,"hitHead",data.hitHead());NpcNativeAccess.set(hit,"isHitLegs",data.hitLegs());
    }
    public static void force(Object hit,Object object){
        if(!ServerHumanFactory.ready()||!(object instanceof IsoPlayer victim)||!ServerHumanPlayerAttacks.isNpc(victim))return;
        NpcNativeAccess.set(hit,"hitForce",victim.getHitForce());NpcNativeAccess.set(hit,"hitDirectionX",victim.getHitDir().x);NpcNativeAccess.set(hit,"hitDirectionY",victim.getHitDir().y);
    }
    public static boolean animalOrNpc(IsoPlayer player){return player.isAnimal()||ServerHumanFactory.ready()&&ServerHumanPlayerAttacks.isNpc(player);}
    public static float damage(Object parent,float amount){return ServerHumanFactory.ready()?ServerHumanPlayerDamage.nativeDamage((IsoGameCharacter)parent,amount):amount;}
    static void reset(){CLAIMS.clear();}
}
