// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.iso.objects.interfaces;

import com.sun.istack.NotNull;
import org.jspecify.annotations.Nullable;
import zombie.UsedFromLua;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.GridSquareEdgeFacingDirection;

@UsedFromLua
public interface GridSquareEdgeElement {
    @Nullable IsoGridSquare getSquare();

    default @Nullable IsoGridSquare getOppositeSquare() {
        IsoGridSquare square = this.getSquare();
        return square == null ? null : square.getOppositeSquare(this.getGridSquareEdgeFacingDirection());
    }

    boolean isNorth();

    @UsedFromLua
    default boolean getNorth() {
        return this.isNorth();
    }

    @NotNull
    default GridSquareEdgeFacingDirection getGridSquareEdgeFacingDirection() {
        return this.isNorth() ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST;
    }

    default @Nullable IsoGridSquare getSquareFacingCharacter(IsoGameCharacter chr) {
        if (chr == null) {
            return null;
        } else {
            IsoGridSquare characterSquare = chr.getCurrentSquare();
            if (characterSquare == null) {
                return null;
            } else {
                IsoGridSquare currentSquare = this.getSquare();
                if (currentSquare == characterSquare) {
                    return currentSquare;
                } else {
                    IsoGridSquare oppositeSquare = this.getOppositeSquare();
                    if (oppositeSquare == characterSquare) {
                        return oppositeSquare;
                    } else if (currentSquare == null && oppositeSquare == null) {
                        return null;
                    } else if (currentSquare != null) {
                        return switch (this.getGridSquareEdgeFacingDirection()) {
                            case NORTH_SOUTH -> characterSquare.isNorthOf(currentSquare) ? oppositeSquare : currentSquare;
                            case EAST_WEST -> characterSquare.isWestOf(currentSquare) ? oppositeSquare : currentSquare;
                        };
                    } else {
                        return switch (this.getGridSquareEdgeFacingDirection()) {
                            case NORTH_SOUTH -> characterSquare.isSouthOf(oppositeSquare) ? oppositeSquare : null;
                            case EAST_WEST -> characterSquare.isEastOf(oppositeSquare) ? oppositeSquare : null;
                        };
                    }
                }
            }
        }
    }

    default @Nullable IsoGridSquare getSquareOppositeFacingCharacter(IsoGameCharacter chr) {
        IsoGridSquare squareFacingCharacter = this.getSquareFacingCharacter(chr);
        if (squareFacingCharacter == null) {
            return null;
        } else {
            IsoGridSquare currentSquare = this.getSquare();
            return squareFacingCharacter == currentSquare ? this.getOppositeSquare() : currentSquare;
        }
    }
}
