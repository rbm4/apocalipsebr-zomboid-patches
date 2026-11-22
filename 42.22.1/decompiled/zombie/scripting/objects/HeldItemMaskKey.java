// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.scripting.objects;

import zombie.UsedFromLua;

@UsedFromLua
public record HeldItemMaskKey() {
    public static final HeldItemMaskKey.Entry HOLDING_BAG = new HeldItemMaskKey.Entry("holdingbagright", "holdingbagleft");
    public static final HeldItemMaskKey.Entry HOLDING_BUCKET = new HeldItemMaskKey.Entry("holdingbagright", "holdingbagleft");
    public static final HeldItemMaskKey.Entry HOLDING_TORCH = new HeldItemMaskKey.Entry("HoldingTorchRight", "HoldingTorchLeft");
    public static final HeldItemMaskKey.Entry HOLDING_ANGLE_TORCH = new HeldItemMaskKey.Entry("HoldingAngleTorchRight", "HoldingAngleTorchLeft");
    public static final HeldItemMaskKey.Entry HOLDING_BOTH_HANDS = new HeldItemMaskKey.Entry("HoldingTorchRight", "HoldingTorchLeft");
    public static final HeldItemMaskKey.Entry HOLDING_HEAVY_BOTH_HANDS = new HeldItemMaskKey.Entry("HoldingTorchRight", "HoldingTorchLeft");
    public static final HeldItemMaskKey.Entry HOLDING_UMBRELLA = new HeldItemMaskKey.Entry("HoldingUmbrellaRight", "HoldingUmbrellaLeft");

    public record Entry(String primary, String secondary) {
    }
}
