// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.util.flags;

public interface OrdinalShortFlag extends ShortFlag {
    @Override
    default short flag() {
        return (short)(1 << this.ordinal());
    }

    int ordinal();

    default void assertOrdinalValid() throws IllegalStateException {
        if (this.ordinal() >= 16) {
            throw new IllegalStateException("Too many Flags to fit inside a single 16-bit short.");
        }
    }
}
