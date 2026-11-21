package cloud.apocalipse.pz.agent.features.npc;

import java.util.*;

/** Wraps the original set: every successful removal/readdition gets a distinct membership token. */
public final class NpcMembershipSet<E> extends AbstractSet<E> {
    private final Set<E> delegate;
    private final Map<E,Object> tokens=new HashMap<>();
    public NpcMembershipSet(Set<E> delegate){this.delegate=Objects.requireNonNull(delegate);for(E e:delegate)tokens.put(e,new Object());}
    public static Set<?> wrap(Set<?> set){return set instanceof NpcMembershipSet?set:new NpcMembershipSet<>(set);}
    public Object member(Object object){return tokens.get(object);}
    @Override public int size(){return delegate.size();}
    @Override public boolean contains(Object object){return delegate.contains(object);}
    @Override public boolean add(E object){if(!delegate.add(object))return false;tokens.put(object,new Object());return true;}
    @Override public boolean remove(Object object){
        if(!delegate.remove(object))return false;
        tokens.remove(object);return true;
    }
    @Override public Iterator<E> iterator(){
        var iterator=delegate.iterator();
        return new Iterator<>(){
            E current;
            public boolean hasNext(){return iterator.hasNext();}
            public E next(){return current=iterator.next();}
            public void remove(){iterator.remove();tokens.remove(current);}
        };
    }
}
