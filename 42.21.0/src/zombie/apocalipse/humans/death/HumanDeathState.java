package zombie.apocalipse.humans.death;

import java.util.UUID;

/** Epoch timestamps survive restarts; native IDs, actors and corpse references do not. */
public record HumanDeathState(long diedAtMs,long respawnAtMs,String deathId) {
    public static final HumanDeathState NONE=new HumanDeathState(0,0,"");
    public HumanDeathState {
        if(diedAtMs<0||respawnAtMs<0||respawnAtMs!=0&&respawnAtMs<diedAtMs||deathId==null)throw new IllegalArgumentException("Invalid death state");
        if(!deathId.isEmpty())UUID.fromString(deathId);
    }
    public static HumanDeathState died(long now,HumanDeathPolicy policy){return new HumanDeathState(now,policy.enabled()?Math.addExact(now,policy.delayMs()):0,UUID.randomUUID().toString());}
    public boolean due(long now){return respawnAtMs>0&&now>=respawnAtMs;}
}
