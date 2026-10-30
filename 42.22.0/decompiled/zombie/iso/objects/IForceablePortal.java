// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.iso.objects;

import zombie.UsedFromLua;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.objects.interfaces.GridSquareEdgeElement;

@UsedFromLua
public interface IForceablePortal extends GridSquareEdgeElement {
    int getBaseForceOpenChance(IsoGameCharacter var1);

    void tryForceOpen(IsoPlayer var1);

    void forceOpen(IsoGameCharacter var1);

    void failForceOpen(IsoGameCharacter var1);

    void forceStruggleEffects(IsoGameCharacter var1);

    void forceStruggleDamage(IsoGameCharacter var1);

    boolean isNotValidForForcing();

    boolean isLocked();

    boolean isOpen();
}
