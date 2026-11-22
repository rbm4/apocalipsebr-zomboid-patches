// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.iso;

import zombie.UsedFromLua;
import zombie.characters.IsoGameCharacter;
import zombie.core.properties.IsoPropertyType;
import zombie.iso.objects.interfaces.GridSquareEdgeElement;

@UsedFromLua
public interface ILockableDoor extends GridSquareEdgeElement {
    boolean isLockedByKey();

    boolean IsOpen();

    void setOpen(boolean var1);

    int getKeyId();

    void setKeyId(int arg0);

    boolean isLocked();

    void setLockedByKey(boolean arg0);

    ICurtain HasCurtains();

    boolean canAddCurtain();

    boolean canClimbOver(IsoGameCharacter arg0);

    boolean couldBeOpen(IsoGameCharacter var1);

    boolean hasProperty(IsoPropertyType var1);

    default boolean isForceLocked() {
        return this.hasProperty(IsoPropertyType.FORCE_LOCKED);
    }

    default boolean isGarageDoor() {
        return this.hasProperty(IsoPropertyType.GARAGE_DOOR);
    }
}
