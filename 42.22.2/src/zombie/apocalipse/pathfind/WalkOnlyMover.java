package zombie.apocalipse.pathfind;

import zombie.ai.astar.Mover;

/** An isolated request identity with no crawling, thumping or fence-climbing capability.
 * Contains no live actor access; polygon worker classification must not unwrap a native body. */
public interface WalkOnlyMover extends Mover {
}
