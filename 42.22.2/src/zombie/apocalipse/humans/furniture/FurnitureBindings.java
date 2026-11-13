package zombie.apocalipse.humans.furniture;

import java.util.*;
import zombie.apocalipse.humans.furniture.FurnitureAffordances.Token;

/** Server-private identity bindings; detached tokens never carry their native object. */
public final class FurnitureBindings<T> {
    private record Binding<T>(T object,Object revision,int x,int y,int z,long generation) { }
    private final Thread owner=Thread.currentThread();
    private final IdentityHashMap<T,Binding<T>> objects=new IdentityHashMap<>();
    private final LinkedHashMap<Long,Binding<T>> generations=new LinkedHashMap<>();
    private final int capacity;
    private long sequence;
    public FurnitureBindings(int capacity){if(capacity<1||capacity>2048)throw new IllegalArgumentException("Invalid binding capacity");this.capacity=capacity;}
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Furniture binding requires server owner");}
    public Token bind(T object,Object revision,int x,int y,int z,String slot) {
        check();Objects.requireNonNull(object);Objects.requireNonNull(revision);
        var binding=objects.get(object);
        if(binding==null||binding.x!=x||binding.y!=y||binding.z!=z||!binding.revision.equals(revision)) {
            // Validate before invalidating an accepted binding or consuming a generation.
            new Token(Math.addExact(sequence,1),x,y,z,slot);
            remove(object);
            if(objects.size()==capacity)remove(generations.values().iterator().next().object);
            binding=new Binding<>(object,revision,x,y,z,++sequence);objects.put(object,binding);generations.put(binding.generation,binding);
        }
        return new Token(binding.generation,x,y,z,slot);
    }
    /** The native adapter must still validate live membership/revision before an effect. */
    public T resolve(Token token) {
        check();Objects.requireNonNull(token);var binding=generations.get(token.generation());
        return binding!=null&&binding.x==token.x()&&binding.y==token.y()&&binding.z==token.z()?binding.object:null;
    }
    /** Private-adapter stamp, never an observation or a worker message. */
    public Object revision(Token token){check();var binding=generations.get(token.generation());return resolve(token)==null?null:binding.revision;}
    public void remove(T object){check();var binding=objects.remove(object);if(binding!=null)generations.remove(binding.generation);}
    public void clear(){check();objects.clear();generations.clear();}
    public int size(){check();return objects.size();}
}
