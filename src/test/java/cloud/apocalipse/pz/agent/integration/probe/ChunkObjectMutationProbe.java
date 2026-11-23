package cloud.apocalipse.pz.agent.integration.probe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/** Drives actual vanilla methods on the streaming fixture's server thread. */
final class ChunkObjectMutationProbe {
    private static IsoObject removed;
    private static IsoObject retained;

    private ChunkObjectMutationProbe() {
    }

    static void add(IsoGridSquare first, IsoGridSquare second) {
        removed = new IsoObject(first.getCell(), first, first.getFloor().getSprite());
        retained = new IsoObject(second.getCell(), second, second.getFloor().getSprite());
        first.AddTileObject(removed);
        second.AddSpecialObject(retained);
        retained.setCustomColor(0.25f, 0.5f, 0.75f, 1f);
        retained.flagForHotSave();
    }

    static void changeAndRemove(Path run) throws IOException {
        IsoGridSquare square = removed.getSquare();
        int index = removed.getObjectIndex();
        if (index < 0 || square.RemoveTileObject(removed) < 0 || removed.getObjectIndex() >= 0) {
            throw new IllegalStateException("Vanilla RemoveTileObject did not remove the test object");
        }
        retained.setCustomColor(0.875f, 0.125f, 0.625f, 1f);
        IsoGridSquare target = retained.getSquare();
        ByteBuffer bytes = ByteBuffer.allocate(65536);
        retained.save(bytes, false);
        // Keep the expected payload for the parent JDBC oracle.
        Files.writeString(run.resolve("chunk-object-mutation-expected.txt"),
            coordinates(square) + "," + index + "\n" + coordinates(target) + "," + retained.getObjectIndex() + "\n"
            + Base64.getEncoder().encodeToString(Arrays.copyOf(bytes.array(), bytes.position())) + "\n");
        removed = null;
        retained = null;
    }

    private static String coordinates(IsoGridSquare square) {
        return square.getX() + "," + square.getY() + "," + square.getZ();
    }
}
