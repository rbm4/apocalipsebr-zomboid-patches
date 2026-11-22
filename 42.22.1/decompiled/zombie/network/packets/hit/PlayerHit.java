// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import zombie.CombatManager;
import zombie.EffectsManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoLivingCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameClient;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketTypes;
import zombie.network.fields.hit.Fall;
import zombie.network.fields.hit.Hit;
import zombie.network.fields.hit.Player;
import zombie.network.fields.hit.TracerInfo;
import zombie.network.fields.hit.Weapon;

public abstract class PlayerHit extends Player implements HitCharacter {
    private static final int MAX_TRACERS = 100;
    private static final int MAX_HITS = 100;
    private static int hitIndex;
    private static boolean isShotgunXpAwarded;
    @JSONField
    protected final Weapon weapon = new Weapon();
    @JSONField
    protected final List<Hit> hits = new ArrayList<>();
    @JSONField
    protected List<TracerInfo> tracers = new ArrayList<>(8);

    public abstract float getDistance();

    public abstract void set(IsoPlayer var1, HandWeapon var2, CombatManager.TargetHitList var3, List<TracerInfo> var4);

    public static void resetAttackState() {
        hitIndex = 0;
        isShotgunXpAwarded = false;
    }

    public static int getHitIndex() {
        return hitIndex++;
    }

    public static boolean isShotgunXpAwarded() {
        return isShotgunXpAwarded;
    }

    public static void setShotgunXpAwarded() {
        isShotgunXpAwarded = true;
    }

    public void set(IsoPlayer wielder, HandWeapon weapon, List<TracerInfo> tracers, List<Hit> hits) {
        this.set(wielder);
        this.weapon.set(weapon);
        this.hits.clear();
        if (hits != null) {
            this.hits.addAll(hits);
        }

        this.tracers.clear();
        if (tracers != null) {
            this.tracers.addAll(tracers);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.weapon.parse(b, connection, (IsoLivingCharacter)this.getCharacter());
        this.hits.clear();
        int hitsSize = b.getByte();

        for (int i = 0; i < hitsSize; i++) {
            Hit hit = new Hit();
            hit.parse(b, connection);
            this.hits.add(hit);
        }

        this.tracers.clear();
        int tracersSize = b.getByte();

        for (int i = 0; i < tracersSize; i++) {
            TracerInfo tracer = new TracerInfo();
            tracer.parse(b, connection);
            this.tracers.add(tracer);
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.weapon.write(b);
        int hitsSize = Math.min(100, this.hits.size());
        b.putByte(hitsSize);

        for (int i = 0; i < hitsSize; i++) {
            Hit hit = this.hits.get(i);
            hit.write(b);
        }

        int tracersSize = Math.min(100, this.tracers.size());
        b.putByte(tracersSize);

        for (int i = 0; i < tracersSize; i++) {
            TracerInfo tracer = this.tracers.get(i);
            tracer.write(b);
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        if (super.isConsistent(connection) && this.weapon.isConsistent(connection) && connection.isOwnPlayerOnServer(this.getPlayer())) {
            for (Hit hit : this.hits) {
                if (!hit.isConsistent(connection)) {
                    return false;
                }
            }

            return true;
        } else {
            return false;
        }
    }

    public void processWielder() {
        super.process();
    }

    @Override
    public void preProcess() {
        this.processWielder();
        if (this.getTarget() instanceof IsoGameCharacter target) {
            this.rollHits(target);
        }

        if (this.getHitCount() > 0) {
            this.processTarget();
        }
    }

    @Override
    public void postProcess() {
        this.processWielder();
        if (this.getHitCount() > 0) {
            this.processTarget();
        }
    }

    @Override
    public void react() {
        if (this.getHitCount() > 0) {
            this.reactTarget();
        }
    }

    protected void processTarget() {
    }

    protected void reactTarget() {
    }

    protected void processFall(Fall fall, IsoGameCharacter target) {
        if (this.getHitCount() > 0 && target != null && target.isRemote()) {
            fall.process(target);
        }
    }

    @Override
    public void attack() {
        super.attack(this.weapon.getWeapon(), false);
        this.processTracers();
    }

    public int getHitCount() {
        return this.hits.size();
    }

    public HandWeapon getHandWeapon() {
        return this.weapon.getWeapon();
    }

    private void rollHits(IsoGameCharacter target) {
        IsoGameCharacter wielderCharacter = this.getCharacter();
        HandWeapon handWeapon = this.getHandWeapon();
        Iterator<Hit> iterator = this.hits.iterator();

        while (iterator.hasNext()) {
            if (!iterator.next().roll(wielderCharacter, target, handWeapon)) {
                iterator.remove();
            }
        }
    }

    public void processHits(IsoGameCharacter target) {
        IsoGameCharacter wielderCharacter = this.getCharacter();
        HandWeapon handWeapon = this.getHandWeapon();

        for (Hit hit : this.hits) {
            hit.process(wielderCharacter, target, handWeapon);
        }
    }

    public void processTracers() {
        if (GameClient.client && !this.tracers.isEmpty()) {
            IsoPlayer player = this.getPlayer();
            if (player != null) {
                EffectsManager.getInstance().startMuzzleFlash(player, 1);

                for (TracerInfo tracer : this.tracers) {
                    tracer.process(player);
                }
            }
        }
    }

    @Override
    public void sync(PacketTypes.PacketType packetType, IConnection connection) {
        if (this.isConsistent(connection) && connection.isOwnPlayerOnServer(this.getPlayer())) {
            this.attack();
        }
    }
}
