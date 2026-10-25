package zombie.apocalipse.humans.encounters;

import java.util.*;

/** Bounded semantic account memory. Detached snapshots/events only; no native identities or SQL. */
public final class HumanRelationships {
    public static final int CAPACITY=100000, PENDING_LIMIT=4096;
    public record Key(String affiliation,String kind,String account) {
        public Key {
            if(affiliation==null||!affiliation.matches("[A-Za-z0-9_.-]{1,128}")||!("steam".equals(kind)||"username".equals(kind))
                ||account==null||account.isBlank()||account.length()>128)throw new IllegalArgumentException("Invalid relationship key");
        }
    }
    public enum Outcome { GREETED, COMPLIED, REFUSED, LEFT, ATTACKED }
    public record State(long revision,long meetings,long complied,long refused,long permissionUntilMs,long deniedUntilMs,long nextEncounterMs) {
        public static final State EMPTY=new State(0,0,0,0,0,0,0);
        public State {if(revision<0||meetings<0||complied<0||refused<0||permissionUntilMs<0||deniedUntilMs<0||nextEncounterMs<0)
            throw new IllegalArgumentException("Invalid relationship state");}
        public boolean permits(long now){return now>=deniedUntilMs&&now<permissionUntilMs;}
    }
    public record Event(UUID id,Key key,String npc,Outcome outcome,long atMs,State state) { }
    private final Map<Key,State> states=new HashMap<>();
    private final LinkedHashMap<UUID,Event> pending=new LinkedHashMap<>();
    private final Map<Key,Claim> claims=new HashMap<>();
    private record Claim(Object owner,long until) { }
    public synchronized State get(Key key){return states.getOrDefault(key,State.EMPTY);}
    public synchronized void restore(Map<Key,State> values){
        if(values.size()>CAPACITY||!pending.isEmpty())throw new IllegalStateException("Invalid relationship restore");
        states.clear();states.putAll(values);claims.clear();
    }
    public synchronized boolean claim(Key key,Object owner,long now){
        var held=claims.get(key);
        if(held!=null&&held.owner()!=owner&&now<held.until())return false;
        claims.entrySet().removeIf(e->now>=e.getValue().until());
        if(!claims.containsKey(key)&&claims.size()>=256)return false;
        claims.put(key,new Claim(owner,now+2_000_000_000L));return true;
    }
    public synchronized void release(Key key,Object owner){var held=claims.get(key);if(held!=null&&held.owner()==owner)claims.remove(key);}
    /** Acceptance is in-memory; the worker's journal stage determines crash durability. */
    public synchronized boolean record(Key key,String npc,Outcome outcome,long at,EncounterPolicy policy){
        if(npc==null||npc.isBlank()||npc.length()>128||at<0||pending.size()>=PENDING_LIMIT||!states.containsKey(key)&&states.size()>=CAPACITY)return false;
        var old=get(key);boolean greeted=outcome==Outcome.GREETED,complied=outcome==Outcome.COMPLIED,refused=outcome==Outcome.REFUSED;
        boolean denied=refused||outcome==Outcome.ATTACKED;
        var state=new State(Math.addExact(old.revision(),1),Math.addExact(old.meetings(),greeted?1:0),Math.addExact(old.complied(),complied?1:0),
            Math.addExact(old.refused(),refused?1:0),denied?0:complied?Math.addExact(at,policy.permissionMs()):old.permissionUntilMs(),
            denied?Math.addExact(at,policy.refusalMs()):old.deniedUntilMs(),Math.addExact(at,policy.cooldownMs()));
        var event=new Event(UUID.randomUUID(),key,npc,outcome,at,state);pending.put(event.id(),event);states.put(key,state);return true;
    }
    public synchronized List<Event> pending(){return List.copyOf(pending.values());}
    public synchronized void staged(UUID id){pending.remove(id);}
}
