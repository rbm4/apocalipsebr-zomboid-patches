// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.util.flags;

public interface OrdinalByteFlag extends ByteFlag {
    @Override
    default byte flag() {
        return (byte)(1 << this.ordinal());
    }

    int ordinal();

    default void assertOrdinalValid() throws IllegalStateException {
        if (this.ordinal() >= 8) {
            throw new IllegalStateException("Too many Flags to fit inside a single 8-bit byte.");
        }
    }
}
