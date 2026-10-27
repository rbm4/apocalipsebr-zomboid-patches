// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.characters;

public class MedicalRequests extends Requests {
    private static final MedicalRequests INSTANCE = new MedicalRequests();

    private MedicalRequests() {
    }

    public static MedicalRequests getInstance() {
        return INSTANCE;
    }

    public static boolean isCheckForbidden(IsoPlayer sender, IsoPlayer destination) {
        float maxDistanceSquares = 2.0F;
        float maxHeightLevels = 1.0F;
        return Math.abs(destination.getX() - sender.getX()) > 2.0F
            || Math.abs(destination.getY() - sender.getY()) > 2.0F
            || Math.abs(destination.getZ() - sender.getZ()) >= 1.0F;
    }
}
