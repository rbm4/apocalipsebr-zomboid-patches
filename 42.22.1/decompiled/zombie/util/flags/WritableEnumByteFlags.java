// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.util.flags;

public class WritableEnumByteFlags<EnumType extends Enum<? extends ByteFlag>> extends EnumByteFlags<EnumType> {
    private WritableEnumByteFlags(Class<? extends EnumType> enumClass) {
        super(enumClass);
    }

    public static <EnumType extends Enum<? extends ByteFlag>> WritableEnumByteFlags<EnumType> allocEmpty(Class<? extends EnumType> enumClass) {
        return new WritableEnumByteFlags<>(enumClass);
    }

    public static <EnumType extends Enum<? extends ByteFlag>> WritableEnumByteFlags<EnumType> alloc(EnumType flag, EnumType... flags) {
        WritableEnumByteFlags<EnumType> result = allocEmpty(flag.getDeclaringClass());
        result.set(flag);

        for (EnumType flagVal : flags) {
            result.set(flagVal);
        }

        return result;
    }

    public void setFlagsRaw(byte flagsVal) {
        this.flags.setFlagsRaw(flagsVal);
    }

    public WritableEnumByteFlags<EnumType> set(EnumType flag, boolean set) {
        this.flags.set((ByteFlag)flag, set);
        return this;
    }

    public WritableEnumByteFlags<EnumType> set(EnumType flag) {
        this.flags.set((ByteFlag)flag);
        return this;
    }

    public WritableEnumByteFlags<EnumType> copyFrom(EnumByteFlags<EnumType> rhs) {
        this.flags.copyFrom(rhs.flags);
        return this;
    }

    public WritableEnumByteFlags<EnumType> clear(EnumType flag) {
        this.flags.clear((ByteFlag)flag);
        return this;
    }

    public WritableEnumByteFlags<EnumType> clear() {
        this.flags.clear();
        return this;
    }
}
