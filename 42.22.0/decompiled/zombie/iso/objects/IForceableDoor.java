// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.iso.objects;

import zombie.UsedFromLua;
import zombie.ai.states.ForceOpenState;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.ILockableDoor;
import zombie.iso.IsoGridSquare;

@UsedFromLua
public interface IForceableDoor extends ILockableDoor, IForceablePortal {
    @Override
    default int getBaseForceOpenChance(IsoGameCharacter owner) {
        return 0;
    }

    @Override
    default void tryForceOpen(IsoPlayer forcingCharacter) {
        if (this.isGarageDoor()) {
            ForceOpenState.instance().setParams(forcingCharacter, this, ForceOpenState.ForcingType.GARAGE_DOOR);
            forcingCharacter.getActionContext().reportEvent("EventForceOpenGarageDoor");
        } else if (this.doesOpenLeftSide(forcingCharacter)) {
            ForceOpenState.instance().setParams(forcingCharacter, this, ForceOpenState.ForcingType.DOOR_LEFT);
            forcingCharacter.getActionContext().reportEvent("EventForceOpenDoorLeft");
        } else {
            ForceOpenState.instance().setParams(forcingCharacter, this, ForceOpenState.ForcingType.DOOR_RIGHT);
            forcingCharacter.getActionContext().reportEvent("EventForceOpenDoorRight");
        }
    }

    default boolean doesOpenLeftSide(IsoGameCharacter owner) {
        boolean isNorth = this.isNorth();
        IsoGridSquare square = this.getSquare();
        return square != null && (isNorth && square.getY() > owner.getY() || !isNorth && square.getX() < owner.getX());
    }

    @Override
    void forceOpen(IsoGameCharacter var1);

    @Override
    void failForceOpen(IsoGameCharacter var1);

    @Override
    void forceStruggleEffects(IsoGameCharacter var1);

    @Override
    boolean isNotValidForForcing();
}
