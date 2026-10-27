// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.audio.parameters;

import fmod.fmod.FMODManager;
import java.util.List;
import zombie.audio.FMODLocalParameter;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.properties.IsoPropertyType;
import zombie.core.properties.PropertyContainer;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.scripting.objects.CharacterTrait;

public final class ParameterFootstepMaterial extends FMODLocalParameter {
    private final IsoGameCharacter character;

    public ParameterFootstepMaterial(IsoGameCharacter character) {
        super("FootstepMaterial");
        this.character = character;
    }

    @Override
    public float calculateCurrentValue() {
        return this.getMaterial().label;
    }

    private ParameterFootstepMaterial.FootstepMaterial getMaterial() {
        if (FMODManager.instance.getNumListeners() == 1) {
            for (int i = 0; i < IsoPlayer.numPlayers; i++) {
                IsoPlayer player = IsoPlayer.players[i];
                if (player != null && player != this.character && !player.hasTrait(CharacterTrait.DEAF)) {
                    if (PZMath.fastfloor(player.getZ()) < PZMath.fastfloor(this.character.getZ())) {
                        return ParameterFootstepMaterial.FootstepMaterial.Upstairs;
                    }
                    break;
                }
            }
        }

        IsoObject staircase = null;
        PropertyContainer materialProps = null;
        IsoGridSquare square = this.character.getCurrentSquare();
        if (square != null) {
            if (IsoWorld.instance.currentCell.gridSquareIsSnow(square.x, square.y, square.z)) {
                return ParameterFootstepMaterial.FootstepMaterial.Snow;
            }

            List<IsoObject> objects = square.getObjects();
            int size = objects.size();

            for (int ix = 0; ix < size; ix++) {
                IsoObject object = objects.get(ix);
                if (!(object instanceof IsoWorldInventoryObject)) {
                    PropertyContainer props = object.getProperties();
                    if (props != null) {
                        if (object.isStairsObject()) {
                            staircase = object;
                        }

                        if (props.has(IsoPropertyType.FOOTSTEP_MATERIAL)) {
                            materialProps = props;
                        }
                    }
                }
            }
        }

        String material = materialProps == null ? null : materialProps.get(IsoPropertyType.FOOTSTEP_MATERIAL);
        if (material != null) {
            try {
                return ParameterFootstepMaterial.FootstepMaterial.valueOf(material);
            } catch (IllegalArgumentException var9) {
                DebugType.Sound.printException(var9, "Unknown FootstepMaterial: " + material, LogSeverity.Error);
            }
        }

        return staircase != null ? ParameterFootstepMaterial.FootstepMaterial.Wood : ParameterFootstepMaterial.FootstepMaterial.Concrete;
    }

    static enum FootstepMaterial {
        Upstairs(0),
        BrokenGlass(1),
        Concrete(2),
        Grass(3),
        Gravel(4),
        Puddle(5),
        Snow(6),
        Wood(7),
        Carpet(8),
        Dirt(9),
        Sand(10),
        Ceramic(11),
        Metal(12);

        final int label;

        private FootstepMaterial(final int label) {
            this.label = label;
        }
    }
}
