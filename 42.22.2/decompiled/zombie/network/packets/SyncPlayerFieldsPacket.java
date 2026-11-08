// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import java.util.Set;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.PlayerCraftHistory;
import zombie.characters.traits.CharacterTraits;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.character.ConnectionPlayer;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class SyncPlayerFieldsPacket implements INetworkPacket {
    public static final byte PF_Recipes = 1;
    public static final byte PF_Traits = 2;
    public static final byte PF_AlreadyReadBook = 4;
    public static final byte PF_BodyDamage = 8;
    public static final byte PF_Reading = 16;
    public static final byte PF_Fitness = 32;
    public static final byte PF_CraftHistory = 64;
    public static final byte PF_Count = 7;
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    byte syncParams;

    public void set(IsoPlayer player, byte syncParams) {
        this.player.set(player);
        this.syncParams = syncParams;
    }

    @Override
    public void setData(Object... values) {
        if (values.length == 2 && values[0] instanceof IsoPlayer) {
            this.set((IsoPlayer)values[0], (Byte)values[1]);
        } else {
            DebugType.Multiplayer.warn(this.getClass().getSimpleName() + ".set get invalid arguments");
            DebugType.Multiplayer.printStackTrace();
        }
    }

    void writeParam(byte param, ByteBufferWriter b, IsoPlayer isoPlayer) {
        switch (param) {
            case 1:
                b.putInt(isoPlayer.getKnownRecipes().size());

                for (String recipeStr : isoPlayer.getKnownRecipes()) {
                    b.putUTF(recipeStr);
                }
                break;
            case 2:
                CharacterTraits characterTraits = isoPlayer.getCharacterTraits();
                characterTraits.write(b);
                break;
            case 4:
                b.putInt(isoPlayer.getAlreadyReadBook().size());

                for (String bookStr : isoPlayer.getAlreadyReadBook()) {
                    b.putUTF(bookStr);
                }
                break;
            case 8:
                isoPlayer.getBodyDamage().saveMainFields(b.bb);
                break;
            case 16:
                b.putBoolean(isoPlayer.isReading());
                break;
            case 32:
                isoPlayer.getFitness().save(b.bb);
                break;
            case 64:
                PlayerCraftHistory craftHistory = isoPlayer.getPlayerCraftHistory();
                Set<String> craftTypes = craftHistory.getCraftTypes();
                b.putInt(craftTypes.size());

                for (String craftType : craftTypes) {
                    PlayerCraftHistory.CraftHistoryEntry entry = craftHistory.getCraftHistoryFor(craftType);
                    b.putUTF(craftType);
                    b.putInt(entry.getCraftCount());
                    b.putDouble(entry.getLastCraftTime());
                }
                break;
            default:
                DebugType.Multiplayer.warn(this.getClass().getSimpleName() + ".writeParam get invalid syncParam");
        }
    }

    void parseParam(byte param, ByteBufferReader b, IsoPlayer isoPlayer) {
        switch (param) {
            case 1:
                int recipesSize = b.getInt();

                for (int ix = 0; ix < recipesSize; ix++) {
                    String recipeStr = b.getUTF();
                    if (!isoPlayer.getKnownRecipes().contains(recipeStr)) {
                        isoPlayer.getKnownRecipes().add(recipeStr);
                    }
                }
                break;
            case 2:
                CharacterTraits characterTraits = isoPlayer.getCharacterTraits();
                characterTraits.read(b);
                break;
            case 4:
                int booksSize = b.getInt();

                for (int i = 0; i < booksSize; i++) {
                    String bookStr = b.getUTF();
                    if (!isoPlayer.getAlreadyReadBook().contains(bookStr)) {
                        isoPlayer.getAlreadyReadBook().add(bookStr);
                    }
                }
                break;
            case 8:
                isoPlayer.getBodyDamage().loadMainFields(b.bb, 251);
                break;
            case 16:
                isoPlayer.setReading(b.getBoolean());
                break;
            case 32:
                isoPlayer.getFitness().load(b.bb, 251);
                break;
            case 64:
                PlayerCraftHistory craftHistory = isoPlayer.getPlayerCraftHistory();
                int craftTypesSize = b.getInt();

                for (int ix = 0; ix < craftTypesSize; ix++) {
                    String craftType = b.getUTF();
                    int craftCount = b.getInt();
                    double lastCraftTime = b.getDouble();
                    craftHistory.setCraftHistoryFor(craftType, craftCount, lastCraftTime);
                }
                break;
            default:
                DebugType.Multiplayer.warn(this.getClass().getSimpleName() + ".parseParam get invalid syncParam");
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        b.putByte(this.syncParams);
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer == null) {
            DebugType.Multiplayer.error("player not found");
        } else {
            for (int i = 0; i < 7; i++) {
                if ((this.syncParams & (byte)(1 << i)) != 0) {
                    this.writeParam((byte)(1 << i), b, isoPlayer);
                }
            }
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        this.syncParams = b.getByte();
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer != null) {
            for (int i = 0; i < 7; i++) {
                if ((this.syncParams & (byte)(1 << i)) != 0) {
                    this.parseParam((byte)(1 << i), b, isoPlayer);
                }
            }
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection);
    }
}
