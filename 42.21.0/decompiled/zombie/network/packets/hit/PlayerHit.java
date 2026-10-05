// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.ArrayList;
import java.util.List;
import zombie.EffectsManager;
import zombie.characters.IsoLivingCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameClient;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketTypes;
import zombie.network.fields.hit.Player;
import zombie.network.fields.hit.TracerInfo;
import zombie.network.fields.hit.Weapon;

public abstract class PlayerHit implements HitCharacter {
    @JSONField
    protected final Player wielder = new Player();
    @JSONField
    protected final Weapon weapon = new Weapon();
    @JSONField
    protected byte shotID;
    @JSONField
    protected int hitCount;
    @JSONField
    protected List<TracerInfo> tracers = new ArrayList<>(8);

    public void set(IsoPlayer wielder, HandWeapon weapon, boolean isCriticalHit, List<TracerInfo> tracers, int hitCount) {
        this.wielder.set(wielder, isCriticalHit);
        this.weapon.set(weapon);
        this.shotID = wielder.getNetworkCharacterAI().getShotID();
        this.tracers.clear();
        if (tracers != null) {
            this.tracers.addAll(tracers);
        }

        this.hitCount = hitCount;
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.wielder.parse(b, connection);
        this.weapon.parse(b, connection, (IsoLivingCharacter)this.wielder.getCharacter());
        this.shotID = b.getByte();
        this.tracers.clear();
        int numTracers = b.getByte();

        for (int i = 0; i < numTracers; i++) {
            TracerInfo tracer = new TracerInfo();
            tracer.parse(b, connection);
            this.tracers.add(tracer);
        }

        this.hitCount = b.getByte();
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.wielder.write(b);
        this.weapon.write(b);
        b.putByte(this.shotID);
        int count = Math.min(100, this.tracers.size());
        b.putByte(count);

        for (int i = 0; i < count; i++) {
            TracerInfo tracer = this.tracers.get(i);
            tracer.write(b);
        }

        b.putByte(this.hitCount);
    }

    @Override
    public boolean isRelevant(UdpConnection connection) {
        return this.wielder.isRelevant(connection);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.wielder.isConsistent(connection) && this.weapon.isConsistent(connection);
    }

    @Override
    public void preProcess() {
        this.wielder.process();
    }

    @Override
    public void postProcess() {
        this.wielder.process();
    }

    @Override
    public void attack() {
        this.wielder.attack(this.weapon.getWeapon(), false, this.shotID, this.hitCount);
        this.processTracers();
    }

    public int getHitCount() {
        return this.hitCount;
    }

    public IsoPlayer getWielder() {
        return this.wielder.getPlayer();
    }

    public HandWeapon getHandWeapon() {
        return this.weapon.getWeapon();
    }

    public byte getShotID() {
        return this.shotID;
    }

    public void processTracers() {
        if (GameClient.client && !this.tracers.isEmpty()) {
            IsoPlayer player = this.wielder.getPlayer();
            if (player != null) {
                EffectsManager.getInstance().startMuzzleFlash(player, 1);

                for (TracerInfo tracer : this.tracers) {
                    tracer.process(player);
                }
            }
        }
    }

    @Override
    public void sync(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (this.wielder.isConsistent(connection)) {
            this.attack();
        }
    }
}
