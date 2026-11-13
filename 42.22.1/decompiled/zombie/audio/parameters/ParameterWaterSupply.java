// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.audio.parameters;

import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.audio.FMODGlobalParameter;

public final class ParameterWaterSupply extends FMODGlobalParameter {
    public ParameterWaterSupply() {
        super("Water");
    }

    @Override
    public float calculateCurrentValue() {
        return GameTime.getInstance().getWorldAgeDaysSinceBegin() < SandboxOptions.instance.getWaterShutModifier() ? 1.0F : 0.0F;
    }
}
