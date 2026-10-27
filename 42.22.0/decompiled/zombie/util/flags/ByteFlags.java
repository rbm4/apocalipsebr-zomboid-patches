// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.util.flags;

public final class ByteFlags {
    private byte flags;

    private ByteFlags(byte flags) {
        this.flags = flags;
    }

    public static ByteFlags alloc() {
        return new ByteFlags((byte)0);
    }

    public static ByteFlags toFlags(byte flags) {
        return new ByteFlags(flags);
    }

    public void setFlagsRaw(byte flags) {
        this.flags = flags;
    }

    public void set(ByteFlag flag, boolean set) {
        if (set) {
            this.set(flag);
        } else {
            this.clear(flag);
        }
    }

    public void set(ByteFlag flag) {
        this.flags = (byte)(this.flags | flag.flag());
    }

    public void copyFrom(ByteFlags rhs) {
        this.flags = rhs.flags;
    }

    public void clear(ByteFlag flag) {
        this.flags = (byte)(this.flags & (byte)(~flag.flag()));
    }

    public void clear() {
        this.flags = 0;
    }

    public boolean has(ByteFlag flag) {
        return (this.flags & flag.flag()) != 0;
    }

    public byte asByte() {
        return this.flags;
    }
}
