package zombie.util.list;

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
public final class MutationTrackedArrayList<E> extends ArrayList<E> {
    private long replacements;

    public long mutationVersion() { return ((long)this.modCount << 32) ^ this.replacements; }

    @Override public E set(int index, E value) {
        E old = super.set(index, value);
        this.replacements++;
        return old;
    }

    @Override public void replaceAll(UnaryOperator<E> operator) {
        try { super.replaceAll(operator); } finally { this.replacements++; }
    }

    @Override public void sort(Comparator<? super E> comparator) {
        try { super.sort(comparator); } finally { this.replacements++; }
    }

    @Override public List<E> subList(int from, int to) {
        return new TrackedView(super.subList(from, to));
    }

    private final class TrackedView extends AbstractList<E> implements RandomAccess {
        private final List<E> delegate;
        TrackedView(List<E> delegate) { this.delegate = delegate; }
        @Override public int size() { return this.delegate.size(); }
        @Override public E get(int index) { return this.delegate.get(index); }
        @Override public E set(int index, E value) {
            E old = this.delegate.set(index, value);
            MutationTrackedArrayList.this.replacements++;
            return old;
        }
        @Override public void add(int index, E value) { this.delegate.add(index, value); this.modCount++; }
        @Override public boolean addAll(int index, Collection<? extends E> values) {
            boolean changed = this.delegate.addAll(index, values);
            if (changed) this.modCount++;
            return changed;
        }
        @Override public E remove(int index) { E old = this.delegate.remove(index); this.modCount++; return old; }
        @Override protected void removeRange(int from, int to) { this.delegate.subList(from, to).clear(); this.modCount++; }
        @Override public List<E> subList(int from, int to) { return new TrackedView(this.delegate.subList(from, to)); }
        @Override public Iterator<E> iterator() { return this.listIterator(0); }
        @Override public ListIterator<E> listIterator(int index) {
            ListIterator<E> iterator = this.delegate.listIterator(index);
            return new ListIterator<>() {
                public boolean hasNext() { return iterator.hasNext(); }
                public E next() { return iterator.next(); }
                public boolean hasPrevious() { return iterator.hasPrevious(); }
                public E previous() { return iterator.previous(); }
                public int nextIndex() { return iterator.nextIndex(); }
                public int previousIndex() { return iterator.previousIndex(); }
                public void remove() { iterator.remove(); }
                public void add(E value) { iterator.add(value); }
                public void set(E value) { iterator.set(value); MutationTrackedArrayList.this.replacements++; }
            };
        }
        @Override public void replaceAll(UnaryOperator<E> operator) {
            try { this.delegate.replaceAll(operator); } finally { MutationTrackedArrayList.this.replacements++; }
        }
        @Override public void sort(Comparator<? super E> comparator) {
            try { this.delegate.sort(comparator); } finally { MutationTrackedArrayList.this.replacements++; }
        }
        @Override public boolean removeIf(Predicate<? super E> predicate) { return this.delegate.removeIf(predicate); }
        @Override public boolean removeAll(Collection<?> values) { return this.delegate.removeAll(values); }
        @Override public boolean retainAll(Collection<?> values) { return this.delegate.retainAll(values); }
        @Override public Object[] toArray() { return this.delegate.toArray(); }
        @Override public <T> T[] toArray(T[] target) { return this.delegate.toArray(target); }
        @Override public Spliterator<E> spliterator() { return this.delegate.spliterator(); }
        @Override public void forEach(Consumer<? super E> action) { this.delegate.forEach(action); }
    }
}
