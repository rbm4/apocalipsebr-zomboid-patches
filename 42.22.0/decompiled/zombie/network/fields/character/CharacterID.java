// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields.character;

import java.nio.ByteBuffer;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.ServerMap;
import zombie.network.fields.IDShort;
import zombie.network.fields.IPositional;
import zombie.popman.animal.AnimalInstanceManager;

public class CharacterID extends IDShort implements IPositional {
    @JSONField
    private CharacterID.CharacterType type = CharacterID.CharacterType.UNKNOWN;
    private IsoGameCharacter character;

    public void set(short id, CharacterID.CharacterType type) {
        this.setID(id);
        this.type = type;
        this.character = null;
    }

    public void set(IsoGameCharacter character) {
        switch (character) {
            case IsoAnimal isoAnimal:
                this.set(isoAnimal.getOnlineID(), CharacterID.CharacterType.ANIMAL);
                break;
            case IsoPlayer isoPlayer:
                this.set(isoPlayer.getOnlineID(), CharacterID.CharacterType.PLAYER);
                break;
            case IsoZombie isoZombie:
                this.set(isoZombie.getOnlineID(), CharacterID.CharacterType.ZOMBIE);
                break;
            case null:
            default:
                this.set((short)-1, CharacterID.CharacterType.UNKNOWN);
        }

        this.character = character;
    }

    public CharacterID clone() {
        CharacterID clone = new CharacterID();
        clone.set(this.getID(), this.type);
        return clone;
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.type = b.getEnum(CharacterID.CharacterType.class);
        this.character = findCharacter(this.type, this.getID());
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.write(b.bb);
    }

    @Override
    public void write(ByteBuffer bb) {
        super.write(bb);
        bb.put((byte)this.type.ordinal());
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return super.isConsistent(connection) && this.character != null;
    }

    @Override
    public float getX() {
        return this.character == null ? 0.0F : this.character.getX();
    }

    @Override
    public float getY() {
        return this.character == null ? 0.0F : this.character.getY();
    }

    @Override
    public float getZ() {
        return this.character == null ? 0.0F : this.character.getZ();
    }

    public IsoGameCharacter getCharacter() {
        return this.character;
    }

    private static IsoGameCharacter findCharacter(CharacterID.CharacterType type, short id) {
        return (IsoGameCharacter)(switch (type) {
            case UNKNOWN -> null;
            case PLAYER -> GameServer.server ? (IsoGameCharacter)GameServer.IDToPlayerMap.get(id) : (IsoGameCharacter)GameClient.IDToPlayerMap.get(id);
            case ZOMBIE -> GameServer.server ? (IsoGameCharacter)ServerMap.instance.zombieMap.get(id) : (IsoGameCharacter)GameClient.IDToZombieMap.get(id);
            case ANIMAL -> AnimalInstanceManager.getInstance().get(id);
        });
    }

    public static enum CharacterType {
        UNKNOWN,
        PLAYER,
        ZOMBIE,
        ANIMAL;
    }
}
