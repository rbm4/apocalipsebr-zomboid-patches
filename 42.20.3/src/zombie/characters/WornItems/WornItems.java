package zombie.characters.WornItems;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import zombie.GameWindow;
import zombie.UsedFromLua;
import zombie.core.Color;
import zombie.core.ImmutableColor;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.core.textures.Texture;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Clothing;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ResourceLocation;

@UsedFromLua
public final class WornItems {
    private final BodyLocationGroup group;
    private final List<WornItem> items = new ArrayList<>();
    // ApocBR: guards `items`. IsoMannequin.save()/saveState() (reachable from
    // ChunkObjectStateRequestPacket's background chunkStatePool, via IsoChunk.saveObjectState())
    // and gameplay-driven mutation (wearItem()/checkClothing()/syncModel(), reachable from the
    // main thread) can both touch the same WornItems instance concurrently. `items` was a plain
    // ArrayList with no synchronization, so a concurrent add()/remove() on the same list from two
    // threads could corrupt it - observed in production as a NullPointerException on a null
    // element read back out of `items` mid-iteration. Every method touching `items` is
    // synchronized on this dedicated lock (not `this`, since the class is @UsedFromLua and we
    // don't want anything outside this class able to contend on/interfere with our monitor).
    private final Object lock = new Object();

    public WornItems(BodyLocationGroup group) {
        this.group = group;
    }

    public WornItems(WornItems other) {
        this.group = other.group;
        this.copyFrom(other);
    }

    public void copyFrom(WornItems other) {
        if (this.group != other.group) {
            throw new RuntimeException("group=" + this.group.getId() + " other.group=" + other.group.getId());
        } else {
            // Snapshot `other` under its own lock, then apply under ours, one at a time -
            // never hold both locks at once, so this can't deadlock against a concurrent
            // other.copyFrom(this) taking the locks in the opposite order.
            List<WornItem> snapshot;
            synchronized (other.lock) {
                snapshot = new ArrayList<>(other.items);
            }

            synchronized (this.lock) {
                this.items.clear();
                this.items.addAll(snapshot);
            }
        }
    }

    public BodyLocationGroup getBodyLocationGroup() {
        return this.group;
    }

    public WornItem get(int index) {
        synchronized (this.lock) {
            return this.items.get(index);
        }
    }

    public void setItem(ItemBodyLocation location, InventoryItem item) {
        synchronized (this.lock) {
            if (!this.group.isMultiItem(location)) {
                int index = this.indexOf(location);
                if (index != -1) {
                    this.items.remove(index);
                }
            }

            for (int i = 0; i < this.items.size(); i++) {
                WornItem wornItem = this.items.get(i);
                if (this.group.isExclusive(location, wornItem.getLocation())) {
                    this.items.remove(i--);
                }
            }

            if (item != null) {
                this.remove(item);
                int insertAt = this.items.size();

                for (int ix = 0; ix < this.items.size(); ix++) {
                    WornItem wornItem1 = this.items.get(ix);
                    if (this.group.indexOf(wornItem1.getLocation()) > this.group.indexOf(location)) {
                        insertAt = ix;
                        break;
                    }
                }

                WornItem wornItem = new WornItem(location, item);
                this.items.add(insertAt, wornItem);
            }
        }
    }

    public InventoryItem getItem(ItemBodyLocation location) {
        synchronized (this.lock) {
            int index = this.indexOf(location);
            return index == -1 ? null : this.items.get(index).getItem();
        }
    }

    public InventoryItem getItemById(int id) {
        synchronized (this.lock) {
            int index = this.indexOf(id);
            return index == -1 ? null : this.items.get(index).getItem();
        }
    }

    public InventoryItem getItemByIndex(int index) {
        synchronized (this.lock) {
            return index >= 0 && index < this.items.size() ? this.items.get(index).getItem() : null;
        }
    }

    public void remove(InventoryItem item) {
        synchronized (this.lock) {
            int index = this.indexOf(item);
            if (index != -1) {
                this.items.remove(index);
            }
        }
    }

    public void clear() {
        synchronized (this.lock) {
            this.items.clear();
        }
    }

    public ItemBodyLocation getLocation(InventoryItem item) {
        synchronized (this.lock) {
            int index = this.indexOf(item);
            return index == -1 ? null : this.items.get(index).getLocation();
        }
    }

    public boolean contains(InventoryItem item) {
        synchronized (this.lock) {
            return this.indexOf(item) != -1;
        }
    }

    public int size() {
        synchronized (this.lock) {
            return this.items.size();
        }
    }

    public boolean isEmpty() {
        synchronized (this.lock) {
            return this.items.isEmpty();
        }
    }

    public void forEach(Consumer<WornItem> c) {
        synchronized (this.lock) {
            for (int i = 0; i < this.items.size(); i++) {
                c.accept(this.items.get(i));
            }
        }
    }

    public void setFromItemVisuals(ItemVisuals itemVisuals) {
        synchronized (this.lock) {
            this.clear();

            for (int i = 0; i < itemVisuals.size(); i++) {
                ItemVisual itemVisual = itemVisuals.get(i);
                String itemType = itemVisual.getItemType();
                InventoryItem item = InventoryItemFactory.CreateItem(itemType);
                if (item != null) {
                    if (item.getVisual() != null) {
                        item.getVisual().copyFrom(itemVisual);
                        item.synchWithVisual();
                    }

                    if (item instanceof Clothing) {
                        this.setItem(item.getBodyLocation(), item);
                    } else {
                        this.setItem(item.canBeEquipped(), item);
                    }
                }
            }
        }
    }

    public void getItemVisuals(ItemVisuals itemVisuals) {
        synchronized (this.lock) {
            itemVisuals.clear();

            for (int i = 0; i < this.items.size(); i++) {
                InventoryItem item = this.items.get(i).getItem();
                ItemVisual itemVisual = item.getVisual();
                if (itemVisual != null) {
                    itemVisual.setInventoryItem(item);
                    itemVisuals.add(itemVisual);
                }
            }
        }
    }

    public void addItemsToItemContainer(ItemContainer container) {
        synchronized (this.lock) {
            for (int i = 0; i < this.items.size(); i++) {
                InventoryItem item = this.items.get(i).getItem();
                int totalHoles = item.getVisual().getHolesNumber();
                item.setConditionNoSound(item.getConditionMax() - totalHoles * 3);
                container.AddItem(item);
            }
        }
    }

    private int indexOf(ItemBodyLocation location) {
        for (int i = 0; i < this.items.size(); i++) {
            WornItem item = this.items.get(i);
            if (item.getLocation().equals(location)) {
                return i;
            }
        }

        return -1;
    }

    private int indexOf(int id) {
        for (int i = 0; i < this.items.size(); i++) {
            WornItem item = this.items.get(i);
            if (item.getItem().id == id) {
                return i;
            }
        }

        return -1;
    }

    private int indexOf(InventoryItem item) {
        for (int i = 0; i < this.items.size(); i++) {
            WornItem wornItem = this.items.get(i);
            if (wornItem.getItem() == item) {
                return i;
            }
        }

        return -1;
    }

    public void save(ByteBuffer output) throws IOException {
        synchronized (this.lock) {
            short size = (short)this.items.size();
            output.putShort(size);

            for (int i = 0; i < size; i++) {
                WornItem wornItem = this.items.get(i);
                GameWindow.WriteString(output, wornItem.getLocation().toString());
                GameWindow.WriteString(output, wornItem.getItem().getType());
                GameWindow.WriteString(output, wornItem.getItem().getTex().getName());
                wornItem.getItem().col.save(output);
                output.putInt(wornItem.getItem().getVisual().getBaseTexture());
                output.putInt(wornItem.getItem().getVisual().getTextureChoice());
                ImmutableColor colorTint = wornItem.getItem().getVisual().getTint();
                output.putFloat(colorTint.r);
                output.putFloat(colorTint.g);
                output.putFloat(colorTint.b);
                output.putFloat(colorTint.a);
            }
        }
    }

    public void load(ByteBuffer input, int worldVersion) throws IOException {
        synchronized (this.lock) {
            short size = input.getShort();
            this.items.clear();

            for (int i = 0; i < size; i++) {
                String location = GameWindow.ReadString(input);
                String type = GameWindow.ReadString(input);
                String tex = GameWindow.ReadString(input);
                Color color = new Color();
                color.load(input, worldVersion);
                int baseTexture = input.getInt();
                int textureChoice = input.getInt();
                ImmutableColor colorTint = new ImmutableColor(input.getFloat(), input.getFloat(), input.getFloat(), input.getFloat());
                InventoryItem item = InventoryItemFactory.CreateItem(type);
                if (item != null) {
                    item.setTexture(Texture.trygetTexture(tex));
                    if (item.getTex() == null) {
                        item.setTexture(Texture.getSharedTexture("media/inventory/Question_On.png"));
                    }

                    String worldTexture = tex.replace("Item_", "media/inventory/world/WItem_");
                    worldTexture = worldTexture + ".png";
                    item.setWorldTexture(worldTexture);
                    item.setColor(color);
                    item.getVisual().tint = new ImmutableColor(color);
                    item.getVisual().setBaseTexture(baseTexture);
                    item.getVisual().setTextureChoice(textureChoice);
                    item.getVisual().setTint(colorTint);
                    this.items.add(new WornItem(ItemBodyLocation.get(ResourceLocation.of(location)), item));
                }
            }
        }
    }

    /**
     * ApocBR: returns the live backing list, matching vanilla semantics exactly (nothing in the
     * vanilla codebase calls this today). Iterating the returned reference outside this class
     * still bypasses `lock` - if a future caller needs to iterate it from a thread other than the
     * one that already owns/serializes access to this WornItems instance, prefer forEach()/a new
     * accessor that copies under the lock instead of holding onto this reference.
     */
    public List<WornItem> getItems() {
        synchronized (this.lock) {
            return this.items;
        }
    }
}
