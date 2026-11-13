// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.util.flags;

import java.util.ArrayList;
import java.util.List;
import zombie.util.list.PZArrayUtil;

public class EnumByteFlags<EnumType extends Enum<? extends ByteFlag>> {
    private final Class<? extends EnumType> enumClass;
    protected final ByteFlags flags = ByteFlags.alloc();

    protected EnumByteFlags(Class<? extends EnumType> enumClass) {
        this.enumClass = enumClass;
    }

    public static <EnumType extends Enum<? extends ByteFlag>> EnumByteFlags<EnumType> allocEmpty(Class<? extends EnumType> enumClass) {
        return new EnumByteFlags<>(enumClass);
    }

    public static <EnumType extends Enum<? extends ByteFlag>> EnumByteFlags<EnumType> alloc(EnumType flag, EnumType... flags) {
        EnumByteFlags<EnumType> result = allocEmpty(flag.getDeclaringClass());
        result.flags.set((ByteFlag)flag, true);

        for (EnumType flagVal : flags) {
            result.flags.set((ByteFlag)flagVal, true);
        }

        return result;
    }

    public boolean has(EnumType flag) {
        return this.flags.has((ByteFlag)flag);
    }

    public byte asByte() {
        return this.flags.asByte();
    }

    public EnumType[] getActiveFlags() {
        EnumType[] enumConstants = (EnumType[])this.enumClass.getEnumConstants();
        List<EnumType> result = new ArrayList<>(enumConstants.length);

        for (EnumType e : enumConstants) {
            if (this.has(e)) {
                result.add(e);
            }
        }

        return PZArrayUtil.toArray(result);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + PZArrayUtil.arrayToString((EnumType[])this.getActiveFlags(), "{ ", " }", ", ");
    }
}
