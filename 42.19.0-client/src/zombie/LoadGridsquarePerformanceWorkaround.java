// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie;

import zombie.characters.IsoPlayer;
import zombie.inventory.ItemContainer;
import zombie.inventory.ItemPickerJava;
import zombie.iso.ContainerOverlays;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.TileOverlays;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.sprite.IsoSprite;
import zombie.network.GameClient;
import zombie.network.GameServer;

public final class LoadGridsquarePerformanceWorkaround {
    private static final float OVERLAY_DISTANCE_TILES = 24.0F;

    public static void init(int wx, int wy) {
        if (!GameClient.client) {
            LoadGridsquarePerformanceWorkaround.ItemPicker.instance.init();
        }
    }

    private static boolean shouldProcessSquare(IsoGridSquare sq) {
        float thresholdSq = OVERLAY_DISTANCE_TILES * OVERLAY_DISTANCE_TILES;
        int x = sq.getX();
        int y = sq.getY();

        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer player = IsoPlayer.players[i];
            if (player != null) {
                float dx = x - player.getX();
                float dy = y - player.getY();
                if (dx * dx + dy * dy <= thresholdSq) {
                    return true;
                }
            }
        }

        return false;
    }

    public static void LoadGridsquare(IsoGridSquare sq) {
        if (LoadGridsquarePerformanceWorkaround.ItemPicker.instance.begin(sq)) {
            if (!GameClient.client || shouldProcessSquare(sq)) {
                IsoObject[] objects = sq.getObjects().getElements();
                int size = sq.getObjects().size();

                for (int i = 0; i < size; i++) {
                    IsoObject object = objects[i];
                    if (!(object instanceof IsoWorldInventoryObject)) {
                        if (!GameClient.client) {
                            LoadGridsquarePerformanceWorkaround.ItemPicker.instance.checkObject(object);
                        }

                        if (object.sprite != null && object.sprite.name != null && !ContainerOverlays.instance.hasOverlays(object)) {
                            TileOverlays.instance.updateTileOverlaySprite(object);
                        }
                    }
                }
            }
        }

        LoadGridsquarePerformanceWorkaround.ItemPicker.instance.end(sq);
    }

    private static class ItemPicker {
        public static final LoadGridsquarePerformanceWorkaround.ItemPicker instance = new LoadGridsquarePerformanceWorkaround.ItemPicker();
        private IsoGridSquare square;

        public void init() {
        }

        public boolean begin(IsoGridSquare sq) {
            if (sq.isOverlayDone()) {
                this.square = null;
                return false;
            } else {
                this.square = sq;
                return true;
            }
        }

        public void checkObject(IsoObject object) {
            IsoSprite sprite = object.getSprite();
            if (sprite != null && sprite.getName() != null) {
                ItemContainer container = object.getContainer();
                if (container != null && !container.isExplored()) {
                    ItemPickerJava.fillContainer(container, IsoPlayer.getInstance());
                    container.setExplored(true);
                    if (GameServer.server) {
                        GameServer.sendItemsInContainer(object, container);
                    }
                }

                if (container == null || !container.isEmpty()) {
                    ItemPickerJava.updateOverlaySprite(object);
                }
            }
        }

        public void end(IsoGridSquare sq) {
            sq.setOverlayDone(true);
        }
    }
}
