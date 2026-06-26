// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.iso;

import java.util.ArrayList;
import org.joml.Matrix3f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import zombie.debug.DebugLog;
import zombie.iso.SpriteDetails.IsoFlagType;

public final class IsoWaterFlow {
    private static final ArrayList<Vector4f> points = new ArrayList<>();
    private static final ArrayList<Matrix3f> zones = new ArrayList<>();

    public static void addFlow(float x, float y, float flow, float speed) {
        int degrees = (360 - (int)flow - 45) % 360;
        if (degrees < 0) {
            degrees += 360;
        }

        flow = (float)Math.toRadians(degrees);
        points.add(new Vector4f(x, y, flow, speed));
    }

    public static void addZone(float x1, float y1, float x2, float y2, float shore, float waterGround) {
        if (x1 > x2 || y1 > y2 || shore > 1.0) {
            DebugLog.log("ERROR IsoWaterFlow: Invalid waterzone (" + x1 + ", " + y1 + ", " + x2 + ", " + y2 + ")");
        }

        zones.add(new Matrix3f(x1, y1, x2, y2, shore, waterGround, 0.0F, 0.0F, 0.0F));
    }

    public static int getShore(int x, int y) {
        for (int i = 0; i < zones.size(); i++) {
            Matrix3f zone = zones.get(i);
            if (zone.m00 <= x && zone.m02 >= x && zone.m01 <= y && zone.m10 >= y) {
                return (int)zone.m11;
            }
        }

        return 1;
    }

    public static Vector2f getFlow(IsoGridSquare square, int ax, int ay, Vector2f out) {
        Vector4f fpointA = null;
        float fpointAd = Float.MAX_VALUE;
        Vector4f fpointB = null;
        float fpointBd = Float.MAX_VALUE;
        Vector4f fpointC = null;
        float fpointCd = Float.MAX_VALUE;
        if (points.isEmpty()) {
            return out.set(0.0F, 0.0F);
        } else {
            for (int i = 0; i < points.size(); i++) {
                Vector4f point = points.get(i);
                double d = Math.pow(point.x - (square.x + ax), 2.0) + Math.pow(point.y - (square.y + ay), 2.0);
                if (d < fpointAd) {
                    fpointAd = (float)d;
                    fpointA = point;
                }
            }

            for (int ix = 0; ix < points.size(); ix++) {
                Vector4f point = points.get(ix);
                double d = Math.pow(point.x - (square.x + ax), 2.0) + Math.pow(point.y - (square.y + ay), 2.0);
                if (d < fpointBd && point != fpointA) {
                    fpointBd = (float)d;
                    fpointB = point;
                }
            }

            fpointAd = Math.max((float)Math.sqrt(fpointAd), 0.1F);
            fpointBd = Math.max((float)Math.sqrt(fpointBd), 0.1F);
            float flow;
            float speed;
            if (fpointAd > fpointBd * 10.0F) {
                flow = fpointA.z;
                speed = fpointA.w;
            } else {
                for (int ixx = 0; ixx < points.size(); ixx++) {
                    Vector4f point = points.get(ixx);
                    double d = Math.pow(point.x - (square.x + ax), 2.0) + Math.pow(point.y - (square.y + ay), 2.0);
                    if (d < fpointCd && point != fpointA && point != fpointB) {
                        fpointCd = (float)d;
                        fpointC = point;
                    }
                }

                fpointCd = Math.max((float)Math.sqrt(fpointCd), 0.1F);
                float fpointBCz = fpointB.z * (1.0F - fpointBd / (fpointBd + fpointCd)) + fpointC.z * (1.0F - fpointCd / (fpointBd + fpointCd));
                float fpointBCw = fpointB.w * (1.0F - fpointBd / (fpointBd + fpointCd)) + fpointC.w * (1.0F - fpointCd / (fpointBd + fpointCd));
                float fpointBCd = fpointBd * (1.0F - fpointBd / (fpointBd + fpointCd)) + fpointCd * (1.0F - fpointCd / (fpointBd + fpointCd));
                flow = fpointA.z * (1.0F - fpointAd / (fpointAd + fpointBCd)) + fpointBCz * (1.0F - fpointBCd / (fpointAd + fpointBCd));
                speed = fpointA.w * (1.0F - fpointAd / (fpointAd + fpointBCd)) + fpointBCw * (1.0F - fpointBCd / (fpointAd + fpointBCd));
            }

            float s = 1.0F;
            IsoCell cell = square.getCell();

            for (int dx = -5; dx < 5; dx++) {
                for (int dy = -5; dy < 5; dy++) {
                    IsoGridSquare square1 = cell.getGridSquare(square.x + ax + dx, square.y + ay + dy, 0);
                    if (square1 == null || !square1.getProperties().has(IsoFlagType.water)) {
                        s = (float)Math.min((double)s, Math.max(0.0, Math.sqrt(dx * dx + dy * dy)) / 4.0);
                    }
                }
            }

            speed *= s;
            return out.set(flow, speed);
        }
    }

    public static void Reset() {
        points.clear();
        zones.clear();
    }
}
