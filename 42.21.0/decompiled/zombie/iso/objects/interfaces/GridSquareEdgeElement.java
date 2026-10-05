// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.iso.objects.interfaces;

import zombie.iso.IsoGridSquare;
import zombie.iso.objects.GridSquareEdgeFacingDirection;

public interface GridSquareEdgeElement {
    IsoGridSquare getSquare();

    default IsoGridSquare getOppositeSquare() {
        IsoGridSquare square = this.getSquare();
        return square == null ? null : square.getOppositeSquare(this.getGridSquareEdgeFacingDirection());
    }

    boolean getNorth();

    default GridSquareEdgeFacingDirection getGridSquareEdgeFacingDirection() {
        return this.getNorth() ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST;
    }
}
