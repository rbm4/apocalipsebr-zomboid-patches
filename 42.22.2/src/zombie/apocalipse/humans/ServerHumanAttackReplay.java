package zombie.apocalipse.humans;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Main-thread, per-victim-incarnation exact-payload filter, not general replay protection. */
final class ServerHumanAttackReplay {
    static final long WINDOW = 500_000_000L;
    static final int CAPACITY = 128;
    private record Entry(Object attacker,Object connection,Object weapon,byte[] digest,long at) { }
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    boolean accept(Object attacker,Object connection,Object weapon,byte[] digest,long now) {
        if(attacker==null||connection==null||weapon==null||digest==null||digest.length!=32)return false;
        entries.removeIf(entry -> now-entry.at()>=WINDOW || now-entry.at()<0);
        for(var entry:entries)if(entry.attacker()==attacker&&entry.connection()==connection
            &&entry.weapon()==weapon&&Arrays.equals(entry.digest(),digest))return false;
        // Fail closed during saturation; never evict an unexpired accepted fingerprint.
        if(entries.size()>=CAPACITY)return false;
        entries.addLast(new Entry(attacker,connection,weapon,digest.clone(),now));
        return true;
    }
}
