package cloud.apocalipse.pz.agent.features.optimization.los;

import zombie.characters.IsoZombie;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.RandomAccess;
import java.util.function.UnaryOperator;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.Spliterator;

/** Retains ArrayList mutation/iteration behavior, including backed sublists. */
final class TrackedZombieList extends ArrayList<IsoZombie> {
    private long replacements;

    TrackedZombieList(Collection<IsoZombie> original) {
        super(original);
    }

    long version() { return ((long)this.modCount << 32) ^ this.replacements; }

    @Override public IsoZombie set(int index, IsoZombie value) {
        IsoZombie old = super.set(index, value);
        this.replacements++;
        return old;
    }

    @Override public void replaceAll(UnaryOperator<IsoZombie> operator) {
        try { super.replaceAll(operator); } finally { this.replacements++; }
    }

    @Override public void sort(Comparator<? super IsoZombie> comparator) {
        try { super.sort(comparator); } finally { this.replacements++; }
    }

    @Override public List<IsoZombie> subList(int from, int to) {
        return new TrackedView(super.subList(from, to));
    }

    private final class TrackedView extends AbstractList<IsoZombie> implements RandomAccess {
        private final List<IsoZombie> delegate;
        TrackedView(List<IsoZombie> delegate) { this.delegate = delegate; }
        @Override public int size() { return this.delegate.size(); }
        @Override public IsoZombie get(int index) { return this.delegate.get(index); }
        @Override public IsoZombie set(int index, IsoZombie value) {
            IsoZombie old = this.delegate.set(index, value);
            TrackedZombieList.this.replacements++;
            return old;
        }
        @Override public void add(int index, IsoZombie value) { this.delegate.add(index, value); this.modCount++; }
        @Override public boolean addAll(int index, Collection<? extends IsoZombie> values) {
            boolean changed = this.delegate.addAll(index, values);
            if (changed) this.modCount++;
            return changed;
        }
        @Override public IsoZombie remove(int index) { IsoZombie old = this.delegate.remove(index); this.modCount++; return old; }
        @Override protected void removeRange(int from, int to) { this.delegate.subList(from, to).clear(); this.modCount++; }
        @Override public List<IsoZombie> subList(int from, int to) { return new TrackedView(this.delegate.subList(from, to)); }
        @Override public Iterator<IsoZombie> iterator() { return this.listIterator(0); }
        @Override public ListIterator<IsoZombie> listIterator(int index) {
            ListIterator<IsoZombie> iterator = this.delegate.listIterator(index);
            return new ListIterator<>() {
                public boolean hasNext() { return iterator.hasNext(); }
                public IsoZombie next() { return iterator.next(); }
                public boolean hasPrevious() { return iterator.hasPrevious(); }
                public IsoZombie previous() { return iterator.previous(); }
                public int nextIndex() { return iterator.nextIndex(); }
                public int previousIndex() { return iterator.previousIndex(); }
                public void remove() { iterator.remove(); }
                public void add(IsoZombie value) { iterator.add(value); }
                public void set(IsoZombie value) { iterator.set(value); TrackedZombieList.this.replacements++; }
            };
        }
        @Override public void replaceAll(UnaryOperator<IsoZombie> operator) {
            try { this.delegate.replaceAll(operator); } finally { TrackedZombieList.this.replacements++; }
        }
        @Override public void sort(Comparator<? super IsoZombie> comparator) {
            try { this.delegate.sort(comparator); } finally { TrackedZombieList.this.replacements++; }
        }
        @Override public boolean removeIf(Predicate<? super IsoZombie> predicate) { return this.delegate.removeIf(predicate); }
        @Override public boolean removeAll(Collection<?> values) { return this.delegate.removeAll(values); }
        @Override public boolean retainAll(Collection<?> values) { return this.delegate.retainAll(values); }
        @Override public Object[] toArray() { return this.delegate.toArray(); }
        @Override public <T> T[] toArray(T[] target) { return this.delegate.toArray(target); }
        @Override public Spliterator<IsoZombie> spliterator() { return this.delegate.spliterator(); }
        @Override public void forEach(Consumer<? super IsoZombie> action) { this.delegate.forEach(action); }
    }
}
