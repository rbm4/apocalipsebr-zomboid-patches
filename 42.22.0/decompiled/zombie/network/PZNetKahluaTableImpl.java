// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import se.krka.kahlua.vm.KahluaUtil;
import se.krka.kahlua.vm.LuaCallFrame;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.ai.State;
import zombie.ai.states.ClimbState;
import zombie.ai.states.ClimbThroughWindowPositioningParams;
import zombie.characterTextures.BloodBodyPartType;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Core;
import zombie.core.network.ByteBufferReader;
import zombie.entity.Component;
import zombie.entity.ComponentType;
import zombie.entity.GameEntity;
import zombie.entity.GameEntityManager;
import zombie.entity.components.crafting.CraftBench;
import zombie.entity.components.fluids.FluidContainer;
import zombie.entity.components.resources.Resource;
import zombie.entity.components.resources.Resources;
import zombie.entity.components.spriteconfig.SpriteConfigManager;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Food;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoHutch;
import zombie.network.fields.ContainerID;
import zombie.network.fields.NetObject;
import zombie.network.fields.StateID;
import zombie.network.fields.Variables;
import zombie.network.fields.character.AnimalID;
import zombie.network.fields.character.CharacterID;
import zombie.network.fields.character.PlayerID;
import zombie.network.fields.character.ZombieID;
import zombie.network.id.ObjectID;
import zombie.network.id.ObjectIDManager;
import zombie.network.id.ObjectIDType;
import zombie.scripting.ScriptManager;
import zombie.scripting.entity.components.crafting.CraftRecipe;
import zombie.scripting.objects.EvolvedRecipe;
import zombie.scripting.objects.Recipe;
import zombie.ui.UIManager;
import zombie.util.Type;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleWindow;

public class PZNetKahluaTableImpl implements KahluaTable {
    @JSONField
    public final Map<Object, Object> delegate;
    private KahluaTable metatable;
    private KahluaTable reloadReplace;
    private static final byte PZNKTI_NO_SAVE = -1;
    private static final byte PZNKTI_INTEGER = 0;
    private static final byte PZNKTI_STRING = 1;
    private static final byte PZNKTI_DOUBLE = 2;
    private static final byte PZNKTI_TABLE = 3;
    private static final byte PZNKTI_PZTABLE = 4;
    private static final byte PZNKTI_BOOLEAN = 5;
    private static final byte PZNKTI_InventoryItem = 6;
    private static final byte PZNKTI_IsoObject = 7;
    private static final byte PZNKTI_IsoPlayer = 8;
    private static final byte PZNKTI_Container = 9;
    private static final byte PZNKTI_BodyPart = 10;
    private static final byte PZNKTI_VehiclePart = 11;
    private static final byte PZNKTI_BaseVehicle = 12;
    private static final byte PZNKTI_IsoGridSquare = 13;
    private static final byte PZNKTI_Recipe = 14;
    private static final byte PZNKTI_BloodBodyPartType = 15;
    private static final byte PZNKTI_DeadBody = 16;
    private static final byte PZNKTI_IsoAnimal = 17;
    private static final byte PZNKTI_ObjectInfo = 18;
    private static final byte PZNKTI_Resource = 19;
    private static final byte PZNKTI_NestBox = 20;
    private static final byte PZNKTI_EvolvedRecipe = 22;
    private static final byte PZNKTI_FluidContainer = 23;
    private static final byte PZNKTI_VehicleWindow = 24;
    private static final byte PZNKTI_CraftRecipe = 25;
    private static final byte PZNKTI_ArrayList = 26;
    private static final byte PZNKTI_Null = 27;
    private static final byte PZNKTI_FLOAT = 28;
    private static final byte PZNKTI_LONG = 29;
    private static final byte PZNKTI_SHORT = 30;
    private static final byte PZNKTI_BYTE = 31;
    private static final byte PZNKTI_IsoDirections = 32;
    private static final byte PZNKTI_State = 33;
    private static final byte PZNKIT_ClimbThroughWindowPositioningParams = 34;
    private static final byte PZNKTI_IsoZombie = 35;
    private static final byte PZNKTI_CraftBench = 36;
    private static final byte PZNKTI_StageStage = 37;
    private static final byte PZNKTI_Variables = 38;
    private static final byte PZNKTI_CLIMB_DIRECTION = 39;
    private static final byte PZNKTI_Enum = 40;

    public PZNetKahluaTableImpl(Map<Object, Object> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void setMetatable(KahluaTable metatable) {
        this.metatable = metatable;
    }

    @Override
    public KahluaTable getMetatable() {
        return this.metatable;
    }

    @Override
    public int size() {
        return this.delegate.size();
    }

    @Override
    public void rawset(Object key, Object value) {
        if (this.reloadReplace != null) {
            this.reloadReplace.rawset(key, value);
        }

        Object lastVal = null;
        if (Core.debug && LuaManager.thread != null && LuaManager.thread.hasDataBreakpoint(this, key)) {
            lastVal = this.rawget(key);
        }

        if (value == null) {
            if (Core.debug && LuaManager.thread != null && LuaManager.thread.hasDataBreakpoint(this, key) && lastVal != null) {
                UIManager.debugBreakpoint(LuaManager.thread.currentfile, LuaManager.thread.lastLine);
            }

            this.delegate.remove(key);
        } else {
            if (Core.debug && LuaManager.thread != null && LuaManager.thread.hasDataBreakpoint(this, key) && !value.equals(lastVal)) {
                int a = LuaManager.GlobalObject.getCurrentCoroutine().currentCallFrame().pc;
                if (a < 0) {
                    a = 0;
                }

                UIManager.debugBreakpoint(
                    LuaManager.thread.currentfile, LuaManager.GlobalObject.getCurrentCoroutine().currentCallFrame().closure.prototype.lines[a] - 1
                );
            }

            this.delegate.put(key, value);
        }
    }

    @Override
    public Object rawget(Object key) {
        if (this.reloadReplace != null) {
            return this.reloadReplace.rawget(key);
        } else if (key == null) {
            return null;
        } else {
            if (Core.debug && LuaManager.thread != null && LuaManager.thread.hasReadDataBreakpoint(this, key)) {
                int a = LuaManager.GlobalObject.getCurrentCoroutine().currentCallFrame().pc;
                if (a < 0) {
                    a = 0;
                }

                UIManager.debugBreakpoint(
                    LuaManager.thread.currentfile, LuaManager.GlobalObject.getCurrentCoroutine().currentCallFrame().closure.prototype.lines[a] - 1
                );
            }

            return !this.delegate.containsKey(key) && this.metatable != null ? this.metatable.rawget(key) : this.delegate.get(key);
        }
    }

    @Override
    public void rawset(int key, Object value) {
        this.rawset(KahluaUtil.toDouble((long)key), value);
    }

    public String rawgetStr(Object key) {
        return (String)this.rawget(key);
    }

    public int rawgetInt(Object key) {
        return this.rawget(key) instanceof Double ? ((Double)this.rawget(key)).intValue() : -1;
    }

    public boolean rawgetBool(Object key) {
        return this.rawget(key) instanceof Boolean ? (Boolean)this.rawget(key) : false;
    }

    public float rawgetFloat(Object key) {
        return this.rawget(key) instanceof Double ? ((Double)this.rawget(key)).floatValue() : -1.0F;
    }

    @Override
    public Object rawget(int key) {
        return this.rawget(KahluaUtil.toDouble((long)key));
    }

    @Override
    public int len() {
        return KahluaUtil.len(this, 0, 2 * this.delegate.size());
    }

    @Override
    public KahluaTableIterator iterator() {
        final Object[] keys = this.delegate.isEmpty() ? null : this.delegate.keySet().toArray();
        return new KahluaTableIterator() {
            private Object curKey;
            private Object curValue;
            private int keyIndex;

            {
                Objects.requireNonNull(PZNetKahluaTableImpl.this);
            }

            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                return this.advance() ? callFrame.push(this.getKey(), this.getValue()) : 0;
            }

            @Override
            public boolean advance() {
                if (keys != null && this.keyIndex < keys.length) {
                    this.curKey = keys[this.keyIndex];
                    this.curValue = PZNetKahluaTableImpl.this.delegate.get(this.curKey);
                    this.keyIndex++;
                    return true;
                } else {
                    this.curKey = null;
                    this.curValue = null;
                    return false;
                }
            }

            @Override
            public Object getKey() {
                return this.curKey;
            }

            @Override
            public Object getValue() {
                return this.curValue;
            }
        };
    }

    @Override
    public boolean isEmpty() {
        return this.delegate.isEmpty();
    }

    @Override
    public void wipe() {
        this.delegate.clear();
    }

    @Override
    public String toString() {
        return "table 0x" + System.identityHashCode(this);
    }

    private void saveInventoryItem(ByteBuffer output, InventoryItem inventoryItem) {
        ContainerID container = new ContainerID();
        ItemContainer itemContainer = inventoryItem.getContainer();
        if (itemContainer == null) {
            for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
                IsoPlayer player = IsoPlayer.players[playerIndex];
                if (player != null
                    && (
                        inventoryItem instanceof Food food && food.getChef() != null && food.getChef().equals(player.getUsername())
                            || player.getInventory().containsID(inventoryItem.getID())
                    )) {
                    itemContainer = player.getInventory();
                    break;
                }
            }
        }

        container.set(itemContainer);
        container.write(output);
        output.putInt(inventoryItem.getID());
    }

    public static void saveIsoObject(ByteBuffer output, IsoObject isoObject) {
        NetObject obj = new NetObject();
        obj.setObject(isoObject);
        obj.write(output);
    }

    public static void saveIsoGameCharacter(ByteBuffer output, IsoGameCharacter character) {
        CharacterID characterID = new CharacterID();
        characterID.set(character);
        characterID.write(output);
    }

    private void saveResource(ByteBuffer output, Resource resource) {
        output.putLong(resource.getGameEntity().getEntityNetID());
        GameWindow.WriteString(output, resource.getId());
    }

    public static void saveComponent(ByteBuffer output, Component component, short componentID) {
        output.putLong(component.getGameEntity().getEntityNetID());
        output.putShort(componentID);
    }

    @Override
    public void save(ByteBuffer output) {
        KahluaTableIterator it = this.iterator();
        int count = 0;

        while (it.advance()) {
            if (canSave(it.getKey(), it.getValue())) {
                count++;
            }
        }

        it = this.iterator();
        output.putInt(count);

        while (it.advance()) {
            byte keyByte = getKeyByte(it.getKey());
            byte valueByte = getValueByte(it.getValue());
            if (keyByte != -1 && valueByte != -1) {
                this.save(output, keyByte, it.getKey());
                this.save(output, valueByte, it.getValue());
            }
        }
    }

    private void save(ByteBuffer output, byte sbyt, Object o) throws RuntimeException {
        output.put(sbyt);
        switch (sbyt) {
            case 0:
                output.putInt((Integer)o);
                break;
            case 1:
                GameWindow.WriteString(output, (String)o);
                break;
            case 2:
                output.putDouble((Double)o);
                break;
            case 3:
                ((KahluaTableImpl)o).save(output);
                break;
            case 4:
                ((PZNetKahluaTableImpl)o).save(output);
                break;
            case 5:
                output.put((byte)((Boolean)o ? 1 : 0));
                break;
            case 6:
                this.saveInventoryItem(output, (InventoryItem)o);
                break;
            case 7:
                saveIsoObject(output, (IsoObject)o);
                break;
            case 8: {
                PlayerID player = new PlayerID();
                player.set((IsoPlayer)o);
                player.write(output);
                break;
            }
            case 9:
                ContainerID container = new ContainerID();
                container.set((ItemContainer)o);
                container.write(output);
                break;
            case 10: {
                PlayerID player = new PlayerID();
                player.set((IsoPlayer)((BodyPart)o).getParentChar());
                player.write(output);
                output.putInt(((BodyPart)o).getIndex());
                break;
            }
            case 11:
                VehiclePart vehiclePart = (VehiclePart)o;
                output.putShort(vehiclePart.getVehicle().vehicleId);
                GameWindow.WriteString(output, vehiclePart.getId());
                break;
            case 12:
                BaseVehicle baseVehicle = (BaseVehicle)o;
                output.putShort(baseVehicle.vehicleId);
                break;
            case 13:
                output.putInt(((IsoGridSquare)o).getX());
                output.putInt(((IsoGridSquare)o).getY());
                output.put((byte)((IsoGridSquare)o).getZ());
                break;
            case 14:
                GameWindow.WriteString(output, ((Recipe)o).getFullType());
                break;
            case 15:
                output.putInt(((BloodBodyPartType)o).index());
                break;
            case 16:
                ((IsoDeadBody)o).getObjectID().save(output);
                break;
            case 17:
                AnimalID animalID = new AnimalID();
                animalID.set((IsoAnimal)o);
                animalID.write(output);
                break;
            case 18:
                GameWindow.WriteString(output, ((SpriteConfigManager.ObjectInfo)o).getName());
                break;
            case 19:
                this.saveResource(output, (Resource)o);
                break;
            case 20:
                output.putInt(((IsoHutch.NestBox)o).getIndex());
                break;
            case 21:
            default:
                throw new RuntimeException("invalid lua table type " + sbyt);
            case 22:
                GameWindow.WriteString(output, ((EvolvedRecipe)o).getScriptObjectFullType());
                break;
            case 23:
                GameEntity gameEntity = ((FluidContainer)o).getGameEntity();
                if (gameEntity instanceof InventoryItem inventoryItem) {
                    output.put((byte)6);
                    this.saveInventoryItem(output, inventoryItem);
                } else if (gameEntity instanceof IsoObject isoObject) {
                    output.put((byte)7);
                    saveIsoObject(output, isoObject);
                }
                break;
            case 24:
                VehiclePart part = ((VehicleWindow)o).getPart();
                output.putShort(part.getVehicle().vehicleId);
                GameWindow.WriteString(output, part.getId());
                break;
            case 25:
                GameWindow.WriteString(output, ((CraftRecipe)o).getScriptObjectFullType());
                break;
            case 26:
                ArrayList<?> list = (ArrayList<?>)o;
                output.putInt(list.size());
                if (!list.isEmpty()) {
                    byte val = getValueByte(list.get(0));
                    if (val == -1) {
                        return;
                    }

                    list.forEach(object -> this.save(output, val, object));
                }
            case 27:
                break;
            case 28:
                output.putFloat((Float)o);
                break;
            case 29:
                output.putLong((Long)o);
                break;
            case 30:
                output.putShort((Short)o);
                break;
            case 31:
                output.put((Byte)o);
                break;
            case 32:
                output.putInt(((IsoDirections)o).ordinal());
                break;
            case 33:
                StateID stateID = new StateID();
                stateID.set((State)o);
                stateID.write(output);
                break;
            case 34:
                ((ClimbThroughWindowPositioningParams)o).write(output);
                break;
            case 35:
                ZombieID zombie = new ZombieID();
                zombie.set((IsoZombie)o);
                zombie.write(output);
                break;
            case 36:
                saveComponent(output, (Component)o, ComponentType.CraftBench.GetID());
                break;
            case 37:
                output.putInt(((State.Stage)o).ordinal());
                break;
            case 38:
                ((Variables)o).write(output);
                break;
            case 39:
                output.putInt(((ClimbState.LadderDirection)o).ordinal());
                break;
            case 40:
                output.putInt(LuaManager.exposer.getExposedEnumClassId(o.getClass()));
                output.putInt(((Enum)o).ordinal());
        }
    }

    @Override
    public void save(DataOutputStream output) throws IOException {
        throw new RuntimeException("The PZNetKahluaTableImpl.save function isn't implemented");
    }

    private InventoryItem loadInventoryItem(ByteBufferReader input, IConnection connection) {
        ContainerID container = new ContainerID();
        container.parse(input, connection);
        int itemId = input.getInt();
        return container.getContainer() != null ? container.getContainer().getItemWithID(itemId) : null;
    }

    public static IsoObject loadIsoObject(ByteBufferReader input, IConnection connection) {
        NetObject obj = new NetObject();
        obj.parse(input, connection);
        return obj.getObject();
    }

    public static IsoGameCharacter loadIsoGameCharacter(ByteBufferReader input, IConnection connection) {
        CharacterID characterID = new CharacterID();
        characterID.parse(input, connection);
        return characterID.getCharacter();
    }

    private Resource loadResource(ByteBufferReader input) {
        long gameEntityNetID = input.getLong();
        String id = input.getUTF();
        GameEntity gameEntity = GameEntityManager.GetEntity(gameEntityNetID);
        Resources resources = gameEntity.getComponent(ComponentType.Resources);
        return resources != null ? resources.getResource(id) : null;
    }

    public static Component loadComponent(ByteBuffer input, IConnection connection) {
        long gameEntityNetID = input.getLong();
        short componentID = input.getShort();
        GameEntity gameEntity = GameEntityManager.GetEntity(gameEntityNetID);
        return gameEntity.getComponent(ComponentType.FromId(componentID));
    }

    @Override
    public void load(ByteBuffer input, int worldVersion) {
        throw new RuntimeException("The PZNetKahluaTableImpl.load function isn't implemented");
    }

    public void load(ByteBufferReader input, IConnection connection) {
        this.load(input, connection, 0);
    }

    public void load(ByteBufferReader input, IConnection connection, int depth) {
        int count = input.getInt();
        this.wipe();

        for (int n = 0; n < count; n++) {
            byte keyByte = input.getByte();
            Object key = this.load(input, connection, keyByte, depth);
            byte valueByte = input.getByte();
            Object value = this.load(input, connection, valueByte, depth);
            this.delegate.put(key, value);
        }
    }

    public Object load(ByteBufferReader input, IConnection connection, byte sbyt, int depth) throws RuntimeException {
        switch (sbyt) {
            case 0:
                return input.getInt();
            case 1:
                String valx = input.getUTF();
                if ("fireplace".equals(valx)) {
                    byte var39 = 4;
                }

                return valx;
            case 2:
                return input.getDouble();
            case 3:
                if (depth + 1 >= 256) {
                    throw new RuntimeException("lua table nesting exceeded");
                }

                KahluaTableImpl v = (KahluaTableImpl)LuaManager.platform.newTable();
                v.load(input.bb, 251, depth + 1);
                return v;
            case 4:
                if (depth + 1 >= 256) {
                    throw new RuntimeException("lua table nesting exceeded");
                }

                PZNetKahluaTableImpl v = new PZNetKahluaTableImpl(new LinkedHashMap<>());
                v.load(input, connection, depth + 1);
                return v;
            case 5:
                return input.getBoolean();
            case 6:
                return this.loadInventoryItem(input, connection);
            case 7:
                return loadIsoObject(input, connection);
            case 8: {
                PlayerID player = new PlayerID();
                player.parse(input, connection);
                return player.getPlayer();
            }
            case 9:
                ContainerID container = new ContainerID();
                container.parse(input, connection);
                return container.getContainer();
            case 10: {
                PlayerID player = new PlayerID();
                player.parse(input, connection);
                int bodyPartIndex = input.getInt();
                return player.getPlayer().getBodyDamage().getBodyParts().get(bodyPartIndex);
            }
            case 11: {
                short vehicleId = input.getShort();
                String partId = input.getUTF();
                BaseVehicle vehicle = VehicleManager.instance.getVehicleByID(vehicleId);
                if (vehicle == null) {
                    return null;
                }

                return vehicle.getPartById(partId);
            }
            case 12: {
                short vehicleId = input.getShort();
                return VehicleManager.instance.getVehicleByID(vehicleId);
            }
            case 13:
                int x = input.getInt();
                int y = input.getInt();
                int z = input.getByte();
                return ServerMap.instance.getGridSquare(x, y, z);
            case 14:
                String recipeType = input.getUTF();
                return ScriptManager.instance.getRecipe(recipeType);
            case 15:
                return BloodBodyPartType.FromIndex(input.getInt());
            case 16:
                ObjectID objectID = ObjectIDManager.createObjectID(ObjectIDType.DeadBody);
                objectID.parse(input, null);
                return objectID.getObject();
            case 17:
                AnimalID animalID = new AnimalID();
                animalID.parse(input, connection);
                return animalID.getAnimal();
            case 18:
                String name = input.getUTF();
                return SpriteConfigManager.GetObjectInfo(name);
            case 19:
                return this.loadResource(input);
            case 20:
                return input.getInt();
            case 21:
            default:
                throw new RuntimeException("invalid lua table type " + sbyt);
            case 22: {
                String recipeName = input.getUTF();
                return ScriptManager.instance.getEvolvedRecipe(recipeName);
            }
            case 23:
                byte type = input.getByte();
                if (type == 6) {
                    InventoryItem inventoryItem = this.loadInventoryItem(input, connection);
                    if (inventoryItem != null) {
                        return inventoryItem.getFluidContainer();
                    }
                } else if (type == 7) {
                    IsoObject isoObject = loadIsoObject(input, connection);
                    if (isoObject != null) {
                        return isoObject.getFluidContainer();
                    }
                }

                return null;
            case 24: {
                short vehicleId = input.getShort();
                String partId = input.getUTF();
                VehiclePart vehiclePart = VehicleManager.instance.getVehicleByID(vehicleId).getPartById(partId);
                return vehiclePart.getWindow();
            }
            case 25: {
                String recipeName = input.getUTF();
                return ScriptManager.instance.getCraftRecipe(recipeName);
            }
            case 26:
                if (depth + 1 >= 256) {
                    throw new RuntimeException("lua table nesting exceeded");
                }

                int size = input.getInt();
                ArrayList<Object> list = new ArrayList<>();
                int i = 0;

                for (; i < size; i++) {
                    byte val = input.getByte();
                    if (val == -1) {
                        break;
                    }

                    Object object = this.load(input, connection, val, depth + 1);
                    if (object != null) {
                        list.add(object);
                    }
                }

                return list;
            case 27:
                return null;
            case 28:
                return input.getFloat();
            case 29:
                return input.getLong();
            case 30:
                return input.getShort();
            case 31:
                return input.getByte();
            case 32:
                return IsoDirections.fromIndex(input.getInt());
            case 33:
                StateID stateID = new StateID();
                stateID.parse(input, connection);
                return stateID.getState();
            case 34:
                ClimbThroughWindowPositioningParams climbParams = ClimbThroughWindowPositioningParams.alloc();
                climbParams.parse(input, connection);
                return climbParams;
            case 35:
                ZombieID zombie = new ZombieID();
                zombie.parse(input, connection);
                return zombie.getZombie();
            case 36:
                return Type.tryCastTo(loadComponent(input.bb, connection), CraftBench.class);
            case 37:
                return State.Stage.values()[input.getInt()];
            case 38:
                Variables variables = new Variables();
                variables.parse(input, connection);
                return variables;
            case 39:
                return ClimbState.LadderDirection.values()[input.getInt()];
            case 40:
                int enumId = input.getInt();
                int enumOrdinal = input.getInt();
                Class<?> enumClass = LuaManager.exposer.getExposedEnumClassFromId(enumId);
                return enumClass.getEnumConstants()[enumOrdinal];
        }
    }

    @Override
    public void load(DataInputStream input, int worldVersion) throws IOException {
        throw new RuntimeException("The PZNetKahluaTableImpl.load function isn't implemented");
    }

    @Override
    public String getString(String string) {
        return (String)this.rawget(string);
    }

    public KahluaTableImpl getRewriteTable() {
        return (KahluaTableImpl)this.reloadReplace;
    }

    public void setRewriteTable(Object value) {
        this.reloadReplace = (KahluaTableImpl)value;
    }

    private static byte getKeyByte(Object o) {
        if (o instanceof String) {
            return 1;
        } else if (o instanceof Double) {
            return 2;
        } else {
            return (byte)(o instanceof Integer ? 0 : -1);
        }
    }

    private static byte getValueByte(Object o) {
        return switch (o) {
            case String var3 -> 1;
            case Double var4 -> 2;
            case Boolean var5 -> 5;
            case KahluaTableImpl var6 -> 3;
            case PZNetKahluaTableImpl var7 -> 4;
            case InventoryItem var8 -> 6;
            case IsoAnimal var9 -> 17;
            case IsoPlayer var10 -> 8;
            case IsoZombie var11 -> 35;
            case BaseVehicle var12 -> 12;
            case IsoDeadBody var13 -> 16;
            case Resource var14 -> 19;
            case SpriteConfigManager.ObjectInfo var15 -> 18;
            case IsoObject var16 -> 7;
            case Integer var17 -> 0;
            case ItemContainer var18 -> 9;
            case BodyPart var19 -> 10;
            case VehiclePart var20 -> 11;
            case IsoGridSquare var21 -> 13;
            case Recipe var22 -> 14;
            case CraftRecipe var23 -> 25;
            case EvolvedRecipe var24 -> 22;
            case BloodBodyPartType var25 -> 15;
            case IsoHutch.NestBox var26 -> 20;
            case FluidContainer var27 -> 23;
            case VehicleWindow var28 -> 24;
            case ArrayList var29 -> 26;
            case PZNetKahluaNull var30 -> 27;
            case Float var31 -> 28;
            case Long var32 -> 29;
            case Short var33 -> 30;
            case Byte var34 -> 31;
            case IsoDirections var35 -> 32;
            case State var36 -> 33;
            case ClimbThroughWindowPositioningParams var37 -> 34;
            case CraftBench var38 -> 36;
            case State.Stage var39 -> 37;
            case Variables var40 -> 38;
            case ClimbState.LadderDirection var41 -> 39;
            case Enum var42 -> 40;
            case null, default -> -1;
        };
    }

    public static boolean canSave(Object key, Object value) {
        return getKeyByte(key) != -1 && getValueByte(value) != -1;
    }
}
