// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields.hit;

import java.util.Optional;
import zombie.characters.IsoGameCharacter;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.fields.INetworkPacketField;
import zombie.network.fields.IPositional;
import zombie.network.fields.character.CharacterID;

public abstract class Character extends CharacterID implements IPositional, INetworkPacketField {
    @JSONField
    protected short characterFlags;
    @JSONField
    protected String characterReaction;
    @JSONField
    protected String playerReaction;
    @JSONField
    protected String zombieReaction;

    public void setCharacterFlags(short characterFlags) {
        this.characterFlags = characterFlags;
    }

    @Override
    public void set(IsoGameCharacter character) {
        super.set(character);
        this.characterFlags = 0;
        this.characterFlags = (short)(this.characterFlags | (short)(character.isDead() ? 1 : 0));
        this.characterFlags = (short)(this.characterFlags | (short)(character.isCloseKilled() ? 2 : 0));
        this.characterFlags = (short)(this.characterFlags | (short)(character.isHitFromBehind() ? 4 : 0));
        this.characterFlags = (short)(this.characterFlags | (short)(character.isFallOnFront() ? 8 : 0));
        this.characterFlags = (short)(this.characterFlags | (short)(character.isKnockedDown() ? 16 : 0));
        this.characterFlags = (short)(this.characterFlags | (short)(character.isOnFloor() ? 32 : 0));
        this.characterReaction = Optional.ofNullable(character.getHitReaction()).orElse("");
        this.playerReaction = Optional.ofNullable(character.getVariableString("PlayerHitReaction")).orElse("");
        this.zombieReaction = Optional.ofNullable(character.getVariableString("ZombieHitReaction")).orElse("");
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.characterFlags = b.getShort();
        this.characterReaction = b.getUTF();
        this.playerReaction = b.getUTF();
        this.zombieReaction = b.getUTF();
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        b.putShort(this.characterFlags);
        b.putUTF(this.characterReaction);
        b.putUTF(this.playerReaction);
        b.putUTF(this.zombieReaction);
    }

    void process() {
        this.getCharacter().setHitReaction(this.characterReaction);
        this.getCharacter().setVariable("PlayerHitReaction", this.playerReaction);
        this.getCharacter().setVariable("ZombieHitReaction", this.zombieReaction);
        this.getCharacter().setCloseKilled((this.characterFlags & 2) != 0);
        this.getCharacter().setHitFromBehind((this.characterFlags & 4) != 0);
        this.getCharacter().setFallOnFront((this.characterFlags & 8) != 0);
        this.getCharacter().setKnockedDown((this.characterFlags & 16) != 0);
        this.getCharacter().setOnFloor((this.characterFlags & 32) != 0);
        if (GameServer.server && (this.characterFlags & 32) == 0 && (this.characterFlags & 4) != 0) {
            this.getCharacter().setFallOnFront(true);
        }
    }

    public void react() {
    }
}
