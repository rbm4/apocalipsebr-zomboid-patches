// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import java.util.ArrayList;
import zombie.Lua.LuaEventManager;
import zombie.characterTextures.BloodBodyPartType;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.WornItems.WornItem;
import zombie.characters.animals.IsoAnimal;
import zombie.core.ImmutableColor;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.types.Clothing;
import zombie.network.GameClient;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.ServerGUI;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ResourceLocation;
import zombie.util.Type;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class SyncClothingPacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    private final ArrayList<SyncClothingPacket.ItemDescription> items = new ArrayList<>();

    @Override
    public void setData(Object... values) {
        if (values.length == 1 && values[0] instanceof IsoPlayer) {
            this.set((IsoPlayer)values[0]);
        } else {
            DebugType.Multiplayer.warn(this.getClass().getSimpleName() + ".set get invalid arguments");
        }
    }

    public void set(IsoPlayer isoPlayer) {
        if (isoPlayer instanceof IsoAnimal) {
            DebugType.General.printStackTrace("SyncClothingPacket.set receives IsoAnimal");
        }

        this.player.set(isoPlayer);
        this.items.clear();
        this.player.getPlayer().getWornItems().forEach(item -> {
            if (item != null && item.getItem() != null) {
                this.items.add(new SyncClothingPacket.ItemDescription(item));
            }
        });
    }

    void parseClothing(ByteBufferReader b, int itemId) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer != null) {
            Clothing clothing = Type.tryCastTo(isoPlayer.getInventory().getItemWithID(itemId), Clothing.class);
            if (clothing != null) {
                clothing.removeAllPatches();
            }

            byte patchesNum = b.getByte();

            for (byte j = 0; j < patchesNum; j++) {
                byte bloodBodyPartTypeIdx = b.getByte();
                byte tailorLvl = b.getByte();
                byte fabricType = b.getByte();
                boolean hasHole = b.getBoolean();
                if (clothing != null) {
                    ItemVisual bloodBodyPartType = clothing.getVisual();
                    if (bloodBodyPartType instanceof ItemVisual) {
                        bloodBodyPartType.removeHole(bloodBodyPartTypeIdx);
                        BloodBodyPartType bloodBodyPartTypex = BloodBodyPartType.FromIndex(bloodBodyPartTypeIdx);
                        switch (Clothing.ClothingPatchFabricType.fromIndex(fabricType)) {
                            case null:
                                break;
                            case Cotton:
                                bloodBodyPartType.setBasicPatch(bloodBodyPartTypex);
                                break;
                            case Denim:
                                bloodBodyPartType.setDenimPatch(bloodBodyPartTypex);
                                break;
                            case Leather:
                                bloodBodyPartType.setLeatherPatch(bloodBodyPartTypex);
                                break;
                            default:
                                throw new MatchException(null, null);
                        }
                    }

                    clothing.addPatchForSync(bloodBodyPartTypeIdx, tailorLvl, fabricType, hasHole);
                }
            }
        }
    }

    void writeClothing(ByteBufferWriter b, int itemId) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer == null) {
            b.putByte(0);
        } else {
            Clothing clothing = Type.tryCastTo(isoPlayer.getInventory().getItemWithID(itemId), Clothing.class);
            if (clothing == null) {
                b.putByte(0);
            } else {
                b.putByte(clothing.getPatchesNumber());

                for (int i = 0; i < BloodBodyPartType.MAX.index(); i++) {
                    Clothing.ClothingPatch patch = clothing.getPatchType(BloodBodyPartType.FromIndex(i));
                    if (patch != null) {
                        b.putByte(i);
                        b.putByte(patch.tailorLvl);
                        b.putByte(patch.fabricType);
                        b.putBoolean(patch.hasHole);
                    }
                }
            }
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer != null) {
            this.items.clear();
            byte size = b.getByte();

            for (int i = 0; i < size; i++) {
                SyncClothingPacket.ItemDescription item = new SyncClothingPacket.ItemDescription();
                item.parse(b, connection);
                this.items.add(item);
                this.parseClothing(b, item.itemId);
            }
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        b.putByte(this.items.size());

        for (SyncClothingPacket.ItemDescription item : this.items) {
            item.write(b);
            this.writeClothing(b, item.itemId);
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.getPlayer() != null;
    }

    private boolean isItemsContains(int itemId, ItemBodyLocation location) {
        for (SyncClothingPacket.ItemDescription item : this.items) {
            if (item.itemId == itemId && item.location.equals(location)) {
                return true;
            }
        }

        return false;
    }

    private void process() {
        if (this.player.getPlayer().remote) {
            this.player.getPlayer().getItemVisuals().clear();
        }

        ArrayList<InventoryItem> itemsForDelete = new ArrayList<>();
        this.player.getPlayer().getWornItems().forEach(itemx -> {
            if (!this.isItemsContains(itemx.getItem().getID(), itemx.getLocation())) {
                itemsForDelete.add(itemx.getItem());
            }
        });

        for (InventoryItem item : itemsForDelete) {
            this.player.getPlayer().getWornItems().remove(item);
        }

        for (SyncClothingPacket.ItemDescription item : this.items) {
            Clothing wornItem = Type.tryCastTo(this.player.getPlayer().getWornItems().getItemById(item.itemId), Clothing.class);
            if (wornItem == null || !item.location.equals(wornItem.getBodyLocation())) {
                InventoryItem itemForAdd = this.player.getPlayer().getInventory().getItemWithID(item.itemId);
                if (itemForAdd == null) {
                    itemForAdd = InventoryItemFactory.CreateItem(item.itemType);
                    if (itemForAdd != null) {
                        itemForAdd.setID(item.itemId);
                    }
                }

                if (itemForAdd != null) {
                    this.player.getPlayer().getWornItems().setItem(item.location, itemForAdd);
                    if (this.player.getPlayer().remote && itemForAdd.getVisual() != null) {
                        ItemVisual itemVisual = itemForAdd.getVisual();
                        itemVisual.setTint(item.tint);
                        itemVisual.setBaseTexture(item.baseTexture);
                        itemVisual.setTextureChoice(item.textureChoice);
                        this.player.getPlayer().getItemVisuals().add(itemVisual);
                    }
                }
            } else if (this.player.getPlayer().remote && wornItem.getVisual() != null) {
                this.player.getPlayer().getItemVisuals().add(wornItem.getVisual());
            }
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        if (GameClient.client) {
            this.process();
            this.player.getPlayer().onWornItemsChanged();
        }

        this.player.getPlayer().resetModelNextFrame();
        LuaEventManager.triggerEvent("OnClothingUpdated", this.player.getPlayer());
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        this.process();
        if (ServerGUI.isCreated()) {
            this.player.getPlayer().resetModelNextFrame();
        }

        this.sendToClients(PacketTypes.PacketType.SyncClothing, null);
    }

    static class ItemDescription implements INetworkPacket {
        @JSONField
        int itemId;
        @JSONField
        String itemType;
        @JSONField
        ItemBodyLocation location;
        @JSONField
        ImmutableColor tint;
        @JSONField
        int textureChoice;
        @JSONField
        int baseTexture;

        public ItemDescription() {
        }

        public ItemDescription(WornItem item) {
            this.itemId = item.getItem().getID();
            this.itemType = item.getItem().getFullType();
            this.location = item.getLocation();
            ItemVisual itemVisual = item.getItem().getVisual();
            if (itemVisual != null) {
                this.baseTexture = itemVisual.getBaseTexture();
                this.textureChoice = itemVisual.getTextureChoice();
                this.tint = itemVisual.getTint();
            } else {
                this.baseTexture = -1;
                this.textureChoice = -1;
            }

            if (this.tint == null) {
                this.tint = ImmutableColor.white;
            }
        }

        @Override
        public void write(ByteBufferWriter b) {
            b.putInt(this.itemId);
            b.putUTF(this.itemType);
            b.putUTF(this.location.toString());
            b.putInt(this.textureChoice);
            b.putInt(this.baseTexture);
            b.putFloat(this.tint.r);
            b.putFloat(this.tint.g);
            b.putFloat(this.tint.b);
            b.putFloat(this.tint.a);
        }

        @Override
        public void parse(ByteBufferReader b, IConnection connection) {
            this.itemId = b.getInt();
            this.itemType = b.getUTF();
            this.location = ItemBodyLocation.get(ResourceLocation.of(b.getUTF()));
            this.textureChoice = b.getInt();
            this.baseTexture = b.getInt();
            this.tint = new ImmutableColor(b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat());
        }
    }
}
