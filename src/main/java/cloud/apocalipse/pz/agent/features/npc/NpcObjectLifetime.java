package cloud.apocalipse.pz.agent.features.npc;

import java.lang.ref.*;
import java.util.*;

/** Weak identity keys: recycled native objects cannot restore an earlier furniture token. */
public final class NpcObjectLifetime {
    private static final ReferenceQueue<Object> QUEUE=new ReferenceQueue<>();
    private static final Map<Key,Long> VALUES=new HashMap<>();
    private static final class Key extends WeakReference<Object> {
        final int hash;
        Key(Object object,boolean retain){super(object,retain?QUEUE:null);hash=System.identityHashCode(object);}
        public int hashCode(){return hash;}
        public boolean equals(Object other){return this==other||other instanceof Key key&&get()!=null&&get()==key.get();}
    }
    private static void reap(){for(Reference<?> ref;(ref=QUEUE.poll())!=null;)VALUES.remove(ref);}
    public static synchronized long revision(Object object){reap();return VALUES.getOrDefault(new Key(object,false),0L);}
    public static synchronized void changed(Object object){reap();VALUES.put(new Key(object,true),revision(object)+1);}
}
