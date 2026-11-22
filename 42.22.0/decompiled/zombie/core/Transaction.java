// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.core;

import java.util.ArrayList;
import java.util.List;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Clothing;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.network.GameServer;
import zombie.network.JSONField;
import zombie.network.PacketTypes;
import zombie.network.fields.ContainerID;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.packets.IDescriptor;
import zombie.network.packets.INetworkPacket;
import zombie.scripting.objects.CharacterTrait;
import zombie.util.StringUtils;

public abstract class Transaction implements IDescriptor {
    protected static byte lastId;
    @JSONField
    protected byte id;
    @JSONField
    protected Transaction.TransactionState state;
    @JSONField
    protected final List<Transaction.TransactionEntry> entries = new ArrayList<>();
    @JSONField
    protected final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    protected String extra;
    @JSONField
    protected IsoDirections direction;
    protected float xoff;
    protected float yoff;
    protected float zoff;
    @JSONField
    protected long startTime;
    @JSONField
    protected long endTime;
    public IsoGridSquare square;

    public void set(
        IsoPlayer player,
        List<InventoryItem> items,
        ItemContainer source,
        ItemContainer destination,
        String extra,
        IsoDirections direction,
        float posX,
        float posY,
        float posZ
    ) {
        this.state = GameServer.server ? Transaction.TransactionState.Accept : Transaction.TransactionState.Request;
        this.player.set(player);
        this.entries.clear();

        for (InventoryItem item : items) {
            Transaction.TransactionEntry entry = new Transaction.TransactionEntry(TransactionManager.getItemId(item, source));
            if (source.getType().equals("object")) {
                entry.sourceId.set(source, source.getParent());
            } else if (source.getType().equals("floor")) {
                if (item.getWorldItem() != null) {
                    entry.sourceId.set(source, item.getWorldItem());
                } else {
                    entry.sourceId.set(source, item.deadBodyObject);
                }
            } else {
                entry.sourceId.set(source);
            }

            if (destination == null) {
                entry.destinationId.set(null);
            } else if (destination.getType().equals("object")) {
                if (source != null && destination.getParent() == source.getParent()) {
                    entry.destinationId.set(destination, destination.getParent());
                } else {
                    entry.destinationId.setObject(destination, destination.getParent(), destination.sourceGrid);
                }

                this.direction = direction;
                this.xoff = posX;
                this.yoff = posY;
                this.zoff = posZ;
            } else if (destination.getType().equals("floor")) {
                entry.destinationId.setFloor(destination, player.getCurrentSquare());
            } else {
                entry.destinationId.set(destination);
            }

            this.entries.add(entry);
        }

        this.extra = extra;
        this.startTime = GameTime.getServerTimeMills();
        this.endTime = GameServer.server ? this.startTime + (long)this.getDuration() : this.startTime;
        if (lastId == 0) {
            lastId++;
        }

        this.id = lastId++;
    }

    public void setTimeData() {
        this.startTime = GameTime.getServerTimeMills();
        this.endTime = this.startTime + (long)this.getDuration();
    }

    public boolean update() {
        for (Transaction.TransactionEntry entry : this.entries) {
            if (!this.updateItem(entry)) {
                return false;
            }
        }

        return true;
    }

    private boolean updateItem(Transaction.TransactionEntry entry) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        entry.sourceId.findObject();
        entry.destinationId.findObject();
        if (entry.sourceId.containerType == ContainerID.ContainerType.IsoObject) {
            if (entry.destinationId.containerType == ContainerID.ContainerType.Undefined) {
                LuaEventManager.triggerEvent("OnProcessTransaction", "scrapMoveable", isoPlayer, null, entry.sourceId, entry.destinationId, null);
                return true;
            } else if (entry.sourceId.hashCode() != entry.destinationId.hashCode()) {
                LuaEventManager.triggerEvent("OnProcessTransaction", "pickUpMoveable", isoPlayer, null, entry.sourceId, entry.destinationId, null);
                return true;
            } else {
                KahluaTable tbl = LuaManager.platform.newTable();
                tbl.rawset("direction", this.direction.name());
                LuaEventManager.triggerEvent("OnProcessTransaction", "rotateMoveable", isoPlayer, null, entry.sourceId, entry.destinationId, tbl);
                return true;
            }
        } else {
            InventoryItem item;
            if (entry.itemId == -1 && entry.sourceId.containerType == ContainerID.ContainerType.DeadBody) {
                item = ((IsoDeadBody)entry.sourceId.getObject()).getItem();
            } else {
                ItemContainer newItem = entry.sourceId.getContainer();
                if (!(newItem instanceof ItemContainer)) {
                    return false;
                }

                item = newItem.getItemWithID(entry.itemId);
                newItem.setExplored(true);
                newItem.setHasBeenLooted(true);
            }

            if (item != null) {
                boolean isReplacedOnCooked = false;
                if (item instanceof Food food) {
                    food.setChef(isoPlayer.getUsername());
                    isReplacedOnCooked = food.getReplaceOnCooked() != null;
                }

                if (item instanceof DrainableComboItem drainableComboItem) {
                    isReplacedOnCooked = drainableComboItem.getReplaceOnCooked() != null;
                }

                if (!isReplacedOnCooked) {
                    item.update();
                }

                if (item instanceof Clothing clothing) {
                    clothing.updateWetness();
                }
            }

            if (entry.destinationId.containerType == ContainerID.ContainerType.IsoObject) {
                KahluaTable tbl = LuaManager.platform.newTable();
                tbl.rawset("direction", this.direction.name());
                LuaEventManager.triggerEvent("OnProcessTransaction", "placeMoveable", isoPlayer, item, entry.sourceId, entry.destinationId, tbl);
                return true;
            } else if (ContainerID.ContainerType.Floor == entry.destinationId.containerType
                && ContainerID.ContainerType.PlayerInventory == entry.sourceId.containerType) {
                isoPlayer.removeAttachedItem(item);
                if (isoPlayer.isEquipped(item)) {
                    isoPlayer.removeFromHands(item);
                    isoPlayer.removeWornItem(item, false);
                    LuaEventManager.triggerEvent("OnClothingUpdated", isoPlayer);
                    isoPlayer.updateHandEquips();
                }

                KahluaTable tbl = LuaManager.platform.newTable();
                tbl.rawset("square", this.square);
                LuaEventManager.triggerEvent("OnProcessTransaction", "dropOnFloor", isoPlayer, item, entry.sourceId, entry.destinationId, tbl);
                return true;
            } else if (!StringUtils.isNullOrEmpty(this.extra)) {
                int newItemId = entry.itemId;
                if (item != null
                    && entry.sourceId.containerType == ContainerID.ContainerType.PlayerInventory
                    && entry.sourceId.getContainer() == entry.destinationId.getContainer()) {
                    DebugType.Objects.noise("Extra option for \"%s\"", this.extra);
                    if (isoPlayer != null) {
                        isoPlayer.removeFromHands(item);
                        isoPlayer.removeWornItem(item, false);
                        isoPlayer.getInventory().Remove(item);
                        InventoryItem newItem = InventoryItemFactory.CreateItem(item, this.extra);
                        isoPlayer.getInventory().AddItem(newItem);
                        if (newItem instanceof InventoryContainer && newItem.canBeEquipped() != null) {
                            isoPlayer.setWornItem(newItem.canBeEquipped(), newItem);
                        } else if (newItem.IsClothing()) {
                            isoPlayer.setWornItem(newItem.getBodyLocation(), newItem);
                        }

                        newItemId = newItem.id;
                        GameServer.sendRemoveItemFromContainer(entry.sourceId.getContainer(), item);
                        GameServer.sendAddItemToContainer(entry.destinationId.getContainer(), newItem);
                        isoPlayer.updateHandEquips();
                        GameServer.sendSyncClothing(isoPlayer, newItem.getBodyLocation(), newItem);
                    }
                }

                return entry.destinationId.getContainer().getItemWithID(newItemId) != null && entry.sourceId.getContainer().getItemWithID(entry.itemId) == null;
            } else {
                if (entry.sourceId.containerType == ContainerID.ContainerType.WorldObject && entry.itemId == -1) {
                    if (entry.sourceId.getObject() == null) {
                        return false;
                    }

                    item = ((IsoWorldInventoryObject)entry.sourceId.getObject()).getItem();
                }

                if (entry.destinationId.getContainer() == null) {
                    return false;
                } else if (entry.sourceId.containerType == ContainerID.ContainerType.Vehicle
                    && IsoUtils.DistanceTo(isoPlayer.getX(), isoPlayer.getY(), entry.sourceId.getVehicle().getX(), entry.sourceId.getVehicle().getY()) > 5.0) {
                    return false;
                } else if (entry.destinationId.containerType == ContainerID.ContainerType.Vehicle
                    && IsoUtils.DistanceTo(isoPlayer.getX(), isoPlayer.getY(), entry.destinationId.getVehicle().getX(), entry.destinationId.getVehicle().getY())
                        > 5.0) {
                    return false;
                } else if (item != null && !ItemContainer.chainContainsContainingItem(entry.destinationId.getContainer(), item.getID())) {
                    if (entry.sourceId.containerType == ContainerID.ContainerType.WorldObject && entry.itemId == -1) {
                        if (item instanceof Clothing clothing) {
                            clothing.flushWetness();
                        }

                        GameServer.RemoveItemFromMap(entry.sourceId.getObject());
                    } else if (entry.itemId == -1 && entry.sourceId.containerType == ContainerID.ContainerType.DeadBody) {
                        INetworkPacket.sendToAll(PacketTypes.PacketType.RemoveCorpseFromMap, entry.sourceId.getObject());
                        entry.sourceId.getObject().getSquare().removeCorpse((IsoDeadBody)entry.sourceId.getObject(), true);
                    } else {
                        isoPlayer.removeAttachedItem(item);
                        if (isoPlayer.isEquipped(item)) {
                            isoPlayer.removeFromHands(item);
                            isoPlayer.removeWornItem(item, false);
                            LuaEventManager.triggerEvent("OnClothingUpdated", isoPlayer);
                            isoPlayer.updateHandEquips();
                            GameServer.sendSyncClothing(isoPlayer, null, item);
                        }

                        if (entry.sourceId.getContainer().getCharacter() instanceof IsoPlayer) {
                            INetworkPacket.send(
                                (IsoPlayer)entry.sourceId.getContainer().getCharacter(),
                                PacketTypes.PacketType.RemoveInventoryItemFromContainer,
                                entry.sourceId.getContainer(),
                                item
                            );
                        } else {
                            INetworkPacket.sendToRelative(
                                PacketTypes.PacketType.RemoveInventoryItemFromContainer,
                                entry.sourceId.x,
                                entry.sourceId.y,
                                entry.sourceId.getContainer(),
                                item
                            );
                        }
                    }

                    entry.sourceId.getContainer().Remove(item);
                    if (!TransactionManager.getLightweightData(isoPlayer).lastItemFullType.equals(item.getFullType())) {
                        TransactionManager.getLightweightData(isoPlayer).itemsCount = 0;
                    }

                    TransactionManager.getLightweightData(isoPlayer).lastItemFullType = item.getFullType() == null ? "" : item.getFullType();
                    TransactionManager.getLightweightData(isoPlayer).itemsLastTransactionTime = System.currentTimeMillis();
                    entry.destinationId.getContainer().addItem(item);
                    if (entry.destinationId.containerType == ContainerID.ContainerType.Floor) {
                        IsoWorldInventoryObject worldInvObj = (IsoWorldInventoryObject)entry.destinationId.getObject();
                        worldInvObj.square.AddWorldInventoryItem(item, worldInvObj.xoff, worldInvObj.yoff, worldInvObj.zoff, true);
                    } else {
                        if (entry.sourceId.containerType == ContainerID.ContainerType.WorldObject
                            || entry.sourceId.containerType == ContainerID.ContainerType.Floor) {
                            item.setWorldItem(null);
                            entry.sourceId.set(null);
                        }

                        if (entry.destinationId.getContainer().getCharacter() instanceof IsoPlayer) {
                            INetworkPacket.send(
                                (IsoPlayer)entry.destinationId.getContainer().getCharacter(),
                                PacketTypes.PacketType.AddInventoryItemToContainer,
                                entry.destinationId.getContainer(),
                                item
                            );
                        } else {
                            INetworkPacket.sendToRelative(
                                PacketTypes.PacketType.AddInventoryItemToContainer,
                                entry.destinationId.x,
                                entry.destinationId.y,
                                entry.destinationId.getContainer(),
                                item
                            );
                        }
                    }

                    if (entry.sourceId.containerType == ContainerID.ContainerType.DeadBody && entry.itemId == -1) {
                        if (entry.destinationId.containerType == ContainerID.ContainerType.PlayerInventory) {
                            isoPlayer.setPrimaryHandItem(item);
                            isoPlayer.setSecondaryHandItem(item);
                            isoPlayer.updateHandEquips();
                        } else {
                            DebugType.Objects.error("A player " + this.player.getDescription() + " sent invalid transaction " + this.getDuration());
                        }
                    }

                    return entry.sourceId.containerType != ContainerID.ContainerType.Floor
                        || !(IsoUtils.DistanceTo(entry.sourceId.x, entry.sourceId.y, isoPlayer.getX(), isoPlayer.getY()) > 1.1);
                } else {
                    return false;
                }
            }
        }
    }

    private float getDuration() {
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer != null && isoPlayer.isTimedActionInstant()) {
            return 0.0F;
        } else {
            float totalMaxTime = 0.0F;

            for (Transaction.TransactionEntry entry : this.entries) {
                ItemContainer source = entry.sourceId.getContainer();
                ItemContainer destination = entry.destinationId.getContainer();
                float maxTime;
                if (source != null && destination != null && isoPlayer != null) {
                    InventoryItem item = entry.sourceId.getContainer().getItemWithID(entry.itemId);
                    if (!destination.getType().equals("TradeUI") && !source.getType().equals("TradeUI")) {
                        maxTime = 120.0F;
                        float destCapacityDelta = 1.0F;
                        if (source == isoPlayer.getInventory()) {
                            if (destination.isInCharacterInventory(isoPlayer)) {
                                destCapacityDelta = destination.getCapacityWeight() / destination.getMaxWeight();
                            } else {
                                maxTime = 50.0F;
                            }
                        } else if (!source.isInCharacterInventory(isoPlayer) && destination.isInCharacterInventory(isoPlayer)) {
                            maxTime = 50.0F;
                        }

                        if (destCapacityDelta < 0.4F) {
                            destCapacityDelta = 0.4F;
                        }

                        if (item != null) {
                            float w = item.getActualWeight();
                            if (w > 3.0F) {
                                w = 3.0F;
                            }

                            maxTime = maxTime * w * destCapacityDelta;
                        }

                        if (destination.getType().equals("floor")) {
                            if (source == isoPlayer.getInventory()) {
                                maxTime *= 0.1F;
                            } else if (!source.isInCharacterInventory(isoPlayer)) {
                                maxTime *= 0.2F;
                            }
                        }

                        if (isoPlayer.hasTrait(CharacterTrait.DEXTROUS)) {
                            maxTime *= 0.5F;
                        }

                        if (isoPlayer.hasTrait(CharacterTrait.ALL_THUMBS) || isoPlayer.isWearingAwkwardGloves()) {
                            maxTime *= 2.0F;
                        }
                    } else {
                        maxTime = 0.0F;
                    }
                } else {
                    maxTime = 50.0F;
                }

                InventoryItem item = null;
                if (entry.sourceId.containerType == ContainerID.ContainerType.WorldObject && entry.itemId == -1 && entry.sourceId.getObject() != null) {
                    item = ((IsoWorldInventoryObject)entry.sourceId.getObject()).getItem();
                }

                if (item != null
                    && item.getWeight() <= 0.1F
                    && (
                        TransactionManager.getLightweightData(isoPlayer).itemsLastTransactionTime == 0L
                            || System.currentTimeMillis() - TransactionManager.getLightweightData(isoPlayer).itemsLastTransactionTime < 2000L
                    )) {
                    if (TransactionManager.getLightweightData(isoPlayer).itemsCount < 19
                        && TransactionManager.getLightweightData(isoPlayer).lastItemFullType.equals(item.getFullType())) {
                        maxTime = 0.0F;
                        TransactionManager.getLightweightData(isoPlayer).itemsCount++;
                    } else {
                        TransactionManager.getLightweightData(isoPlayer).itemsCount = 0;
                        TransactionManager.getLightweightData(isoPlayer).lastItemFullType = "";
                    }
                }

                if (entry.sourceId.getContainer() == entry.destinationId.getContainer()
                    && ContainerID.ContainerType.PlayerInventory == entry.sourceId.containerType
                    && entry.itemId != -1
                    && !StringUtils.isNullOrEmpty(this.extra)) {
                    maxTime = 0.0F;
                }

                totalMaxTime = Math.max(maxTime, totalMaxTime);
            }

            return totalMaxTime * 20.0F;
        }
    }

    public void setState(Transaction.TransactionState state) {
        this.state = state;
        DebugType.Objects.noise(this);
    }

    public void setDuration(long duration) {
        this.endTime = this.startTime + duration;
    }

    @Override
    public String toString() {
        return this.getDescription();
    }

    protected static class TransactionEntry {
        public final Integer itemId;
        public final ContainerID sourceId = new ContainerID();
        public final ContainerID destinationId = new ContainerID();

        public TransactionEntry(Integer itemId) {
            this.itemId = itemId;
        }
    }

    public static enum TransactionState {
        Reject,
        Request,
        Accept,
        Done;
    }
}
