package cloud.apocalipse.pz.agent.features.npc.furniture;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import cloud.apocalipse.pz.agent.features.npc.furniture.FurnitureAffordances.Token;

/** Server-owned bindings for issued tokens, indexed by the area that owns them. */
public final class FurnitureBindings<T> {
    private record Binding<T>(T object, Object revision, long area, Token token) {}

    private final Thread owner = Thread.currentThread();
    private final IdentityHashMap<T, Binding<T>> objects = new IdentityHashMap<>();
    private final LinkedHashMap<Long, Binding<T>> generations = new LinkedHashMap<>();
    private final Map<Long, Set<Binding<T>>> areas = new HashMap<>();
    private final int capacity;
    private long sequence;

    public FurnitureBindings(int capacity) {
        if (capacity < 1 || capacity > 2048) throw new IllegalArgumentException("Invalid binding capacity");
        this.capacity = capacity;
    }

    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Furniture binding requires server owner");
    }

    public Token bind(T object, Object revision, long area, int x, int y, int z, String slot) {
        check();
        Objects.requireNonNull(object);
        Objects.requireNonNull(revision);
        var binding = objects.get(object);
        if (binding != null && binding.area == area && binding.token.x() == x && binding.token.y() == y
                && binding.token.z() == z && binding.revision.equals(revision)) {
            return binding.token.slot().equals(slot) ? binding.token : new Token(binding.token.generation(), x, y, z, slot);
        }

        // Validate the token before evicting an accepted binding or advancing the sequence.
        var token = new Token(Math.addExact(sequence, 1), x, y, z, slot);
        remove(object);
        if (objects.size() == capacity) remove(generations.firstEntry().getValue().object);
        sequence = token.generation();
        binding = new Binding<>(object, revision, area, token);
        objects.put(object, binding);
        generations.put(sequence, binding);
        areas.computeIfAbsent(area, key -> Collections.newSetFromMap(new IdentityHashMap<>())).add(binding);
        return token;
    }

    private Binding<T> find(Token token) {
        check();
        Objects.requireNonNull(token);
        var binding = generations.get(token.generation());
        return binding != null && binding.token.x() == token.x() && binding.token.y() == token.y() && binding.token.z() == token.z()
            ? binding : null;
    }

    /** The native adapter still validates live membership and the furniture descriptor before an effect. */
    public T resolve(Token token) {
        var binding = find(token);
        return binding == null ? null : binding.object;
    }

    public Object revision(Token token) {
        var binding = find(token);
        return binding == null ? null : binding.revision;
    }

    public boolean remove(Object object) {
        check();
        var binding = objects.remove(object);
        if (binding == null) return false;
        generations.remove(binding.token.generation());
        var group = areas.get(binding.area);
        group.remove(binding);
        if (group.isEmpty()) areas.remove(binding.area);
        return true;
    }

    /** Retires only issued bindings in this area, even when the native object is later reused. */
    public int removeArea(long area) {
        check();
        var group = areas.remove(area);
        if (group == null) return 0;
        for (var binding : group) {
            objects.remove(binding.object);
            generations.remove(binding.token.generation());
        }
        return group.size();
    }

    public void clear() {
        check();
        objects.clear();
        generations.clear();
        areas.clear();
    }

    public boolean isEmpty() {
        check();
        return objects.isEmpty();
    }
}
