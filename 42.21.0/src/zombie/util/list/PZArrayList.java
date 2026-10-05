// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.util.list;

import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.RandomAccess;
import zombie.UsedFromLua;
import zombie.util.lambda.Invokers;

@UsedFromLua
public final class PZArrayList<E> extends AbstractList<E> implements List<E>, RandomAccess {
    private E[] elements;
    private int numElements;
    private final boolean isoObjectList;
    private static final boolean lookupTelemetry = Boolean.getBoolean("apocbr.telemetry.squareLookups.enabled");
    private boolean elementsExposed;
    private boolean identityIndexDirty = true;
    private IdentityHashMap<Object, Integer> identityPositions;
    private static final ClassValue<Boolean> identityEquals = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try { return type.getMethod("equals", Object.class).getDeclaringClass() == Object.class; }
            catch (ReflectiveOperationException | SecurityException exception) { return false; }
        }
    };
    private static final PZArrayList<Object> instance = new PZArrayList<>(Object.class, 0);

    public PZArrayList(Class<E> elementType, int initialCapacity) {
        this.elements = (E[])((Object[])Array.newInstance(elementType, initialCapacity));
        this.isoObjectList = elementType == zombie.iso.IsoObject.class;
    }

    @Override
    public E get(int index) {
        if (index >= 0 && index < this.numElements) {
            return this.elements[index];
        } else {
            throw new IndexOutOfBoundsException("Index: " + index + " Size: " + this.numElements);
        }
    }

    @Override
    public int size() {
        return this.numElements;
    }

    @Override
    public int indexOf(Object o) {
        if (lookupTelemetry && this.isoObjectList && zombie.network.GameServer.server) {
            String reason = this.elementsExposed ? "rawArrayExposed" : this.numElements < 8 ? "smallList"
                : o != null && !identityEquals.get(o.getClass()) ? "customEquality" : "cacheEligible";
            zombie.ApocBRServerTelemetryLite.count("isoObjects.squareLookup." + reason, 1);
            zombie.ApocBRServerTelemetryLite.count("isoObjects.squareLookup.listEntries", this.numElements);
        }
        if (this.isoObjectList && zombie.network.GameServer.server && !this.elementsExposed
            && this.numElements >= 8 && (o == null || identityEquals.get(o.getClass()))) {
            if (this.identityPositions == null) this.identityPositions = new IdentityHashMap<>();
            if (this.identityIndexDirty) {
                this.identityPositions.clear();
                for (int i = 0; i < this.numElements; i++) this.identityPositions.putIfAbsent(this.elements[i], i);
                this.identityIndexDirty = false;
                zombie.ApocBRServerTelemetryLite.count("isoObjects.squareIndexRebuilt", 1);
                zombie.ApocBRServerTelemetryLite.count("isoObjects.squareIndexEntries", this.numElements);
            }
            Integer index = this.identityPositions.get(o);
            return index == null ? -1 : index;
        }
        return this.indexOf(o, PZArrayList::objectsEqual);
    }

    private void objectIndexChanged() {
        this.identityIndexDirty = true;
        // Release removed lifetimes immediately, even if no subsequent lookup occurs.
        if (this.identityPositions != null) this.identityPositions.clear();
        if (this.isoObjectList && zombie.network.GameServer.server) {
            zombie.entity.ServerIsoEntityRegistry.listChanged(this);
        }
    }

    public boolean apocbrElementsExposed() {
        return this.elementsExposed;
    }

    public <E1> int indexOf(E1 o, Invokers.Params2.Boolean.ICallback<E1, E> comparator) {
        for (int i = 0; i < this.numElements; i++) {
            if (comparator.accept(o, this.elements[i])) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public boolean isEmpty() {
        return this.numElements == 0;
    }

    @Override
    public boolean contains(Object o) {
        return this.contains(o, PZArrayList::objectsEqual);
    }

    public boolean containsReference(E o) {
        return this.contains(o, PZArrayList::referenceEqual);
    }

    public <E1> boolean contains(E1 o, Invokers.Params2.Boolean.ICallback<E1, E> comparator) {
        return this.indexOf(o, comparator) >= 0;
    }

    @Override
    public Iterator<E> iterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<E> listIterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        throw new UnsupportedOperationException();
    }

    public void addUnique(E newItem) {
        this.addUnique(newItem, PZArrayList::objectsEqual);
    }

    public void addUniqueReference(E newItem) {
        this.addUnique(newItem, PZArrayList::referenceEqual);
    }

    public void addUnique(E newItem, Invokers.Params2.Boolean.ICallback<E, E> comparator) {
        if (!this.contains(newItem, comparator)) {
            this.add(newItem);
        }
    }

    @Override
    public boolean add(E e) {
        if (this.numElements == this.elements.length) {
            int capacity = this.elements.length + (this.elements.length >> 1);
            if (capacity < this.numElements + 1) {
                capacity = this.numElements + 1;
            }

            this.elements = Arrays.copyOf(this.elements, capacity);
            this.elementsExposed = false;
        }

        this.elements[this.numElements] = e;
        this.numElements++;
        this.objectIndexChanged();
        return true;
    }

    @Override
    public void add(int index, E e) {
        if (index >= 0 && index <= this.numElements) {
            if (this.numElements == this.elements.length) {
                int capacity = this.elements.length + this.elements.length >> 1;
                if (capacity < this.numElements + 1) {
                    capacity = this.numElements + 1;
                }

                this.elements = Arrays.copyOf(this.elements, capacity);
                this.elementsExposed = false;
            }

            System.arraycopy(this.elements, index, this.elements, index + 1, this.numElements - index);
            this.elements[index] = e;
            this.numElements++;
            this.objectIndexChanged();
        } else {
            throw new IndexOutOfBoundsException("Index: " + index + " Size: " + this.numElements);
        }
    }

    @Override
    public E remove(int index) {
        if (index >= 0 && index < this.numElements) {
            E old = this.elements[index];
            int move = this.numElements - index - 1;
            if (move > 0) {
                System.arraycopy(this.elements, index + 1, this.elements, index, move);
            }

            this.elements[this.numElements - 1] = null;
            this.numElements--;
            this.objectIndexChanged();
            return old;
        } else {
            throw new IndexOutOfBoundsException("Index: " + index + " Size: " + this.numElements);
        }
    }

    @Override
    public boolean remove(Object o) {
        for (int i = 0; i < this.numElements; i++) {
            if (o == null && this.elements[i] == null || o != null && o.equals(this.elements[i])) {
                int move = this.numElements - i - 1;
                if (move > 0) {
                    System.arraycopy(this.elements, i + 1, this.elements, i, move);
                }

                this.elements[this.numElements - 1] = null;
                this.numElements--;
                this.objectIndexChanged();
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;

        for (int i = this.size() - 1; i >= 0; i--) {
            E e = this.get(i);
            if (c.contains(e)) {
                this.remove(i);
                modified = true;
            }
        }

        return modified;
    }

    @Override
    public E set(int index, E e) {
        if (index >= 0 && index < this.numElements) {
            E old = this.elements[index];
            this.elements[index] = e;
            this.objectIndexChanged();
            return old;
        } else {
            throw new IndexOutOfBoundsException("Index: " + index + " Size: " + this.numElements);
        }
    }

    @Override
    public void clear() {
        for (int i = 0; i < this.numElements; i++) {
            this.elements[i] = null;
        }

        this.numElements = 0;
        this.objectIndexChanged();
    }

    @Override
    public String toString() {
        if (this.isEmpty()) {
            return "[]";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append('[');

            for (int i = 0; i < this.numElements; i++) {
                E e = this.elements[i];
                sb.append(e == this ? "(self)" : e.toString());
                if (i == this.numElements - 1) {
                    break;
                }

                sb.append(',');
                sb.append(' ');
            }

            return sb.append(']').toString();
        }
    }

    public E[] getElements() {
        // A retained raw array can be modified without any List method. Never cache it.
        this.elementsExposed = true;
        this.identityPositions = null;
        if (this.isoObjectList && zombie.network.GameServer.server) {
            zombie.entity.ServerIsoEntityRegistry.listExposed(this);
        }
        return this.elements;
    }

    public static <E> AbstractList<E> emptyList() {
        return (AbstractList<E>)instance;
    }

    public void ensureCapacity(int minCapacity) {
        int oldLength = this.elements.length;
        if (minCapacity > oldLength) {
            int minGrowth = minCapacity - oldLength;
            int prefGrowth = oldLength >> 1;
            int prefLength = oldLength + Math.max(minGrowth, prefGrowth);
            this.elements = Arrays.copyOf(this.elements, prefLength);
            this.elementsExposed = false;
            this.objectIndexChanged();
        }
    }

    public static <E1, E2> boolean objectsEqual(E1 a, E2 b) {
        return a == null && b == null || a != null && a.equals(b);
    }

    public static <E1, E2> boolean referenceEqual(E1 a, E2 b) {
        return a == b;
    }
}
