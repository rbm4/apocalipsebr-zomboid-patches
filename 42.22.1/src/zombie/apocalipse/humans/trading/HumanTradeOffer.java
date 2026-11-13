package zombie.apocalipse.humans.trading;

import java.util.LinkedHashSet;
import java.util.List;

/** Simulation-thread offer. Every edit invalidates consent, including duplicate adds. */
public final class HumanTradeOffer {
    public static final int LIMIT = 500;
    private final LinkedHashSet<Integer> items = new LinkedHashSet<>();
    private boolean sealed, closed;

    public boolean add(int id) {
        if (closed) return false;
        sealed = false;
        return items.size() < LIMIT && items.add(id);
    }
    public void remove(int id) { if (!closed) { items.remove(id); sealed = false; } }
    public boolean seal() { return sealed = !closed && !items.isEmpty(); }
    public void unseal() { sealed = false; }
    public boolean ready() { return !closed && sealed && !items.isEmpty(); }
    public List<Integer> items() { return List.copyOf(items); }
    public void close() { closed = true; sealed = false; }
}
