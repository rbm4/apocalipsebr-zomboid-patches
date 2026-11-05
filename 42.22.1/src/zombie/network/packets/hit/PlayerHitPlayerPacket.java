// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.List;
import zombie.apocalipse.humans.ServerHumanPlayerAttacks;
import zombie.apocalipse.humans.ServerHumanPlayerDamage;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.CombatManager;
import zombie.characters.Capability;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitLongDistance;
import zombie.network.anticheats.AntiCheatHitWeapon;
import zombie.network.anticheats.AntiCheatSafety;
import zombie.network.fields.hit.Fall;
import zombie.network.fields.hit.Player;
import zombie.network.fields.hit.TracerInfo;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 0,
    anticheats = {AntiCheat.HitLongDistance, AntiCheat.HitWeapon, AntiCheat.Safety}
)
public class PlayerHitPlayerPacket extends PlayerHit implements AntiCheatHitLongDistance.IAntiCheat, AntiCheatHitWeapon.IAntiCheat, AntiCheatSafety.IAntiCheat {
    @JSONField
    public final Player target = new Player();
    @JSONField
    protected final Fall fall = new Fall();

    private boolean apocbrNpcTarget;
    private boolean apocbrAccepted;
    private boolean apocbrProcessed;
    private boolean apocbrRelay;
    private byte[] apocbrFingerprint;

    public boolean apocbrNpcTarget() { return this.apocbrNpcTarget; }
    public boolean apocbrRelayAllowed() { return !this.apocbrNpcTarget || this.apocbrRelay; }

    /** Called by the envelope after native consistency/anti-cheat and before effects. */
    public boolean apocbrAccept(UdpConnection connection, IsoPlayer envelopePlayer) {
        if (this.apocbrProcessed || this.getPlayer() != envelopePlayer) return false;
        if (!ServerHumanPlayerDamage.valid(this.hits, this.getHandWeapon())) return false;
        this.apocbrAccepted = ServerHumanPlayerAttacks.receive(
            this.getPlayer(), this.target.getPlayer(), this.getHandWeapon(), connection,
            this.getHitCount(), this.apocbrFingerprint);
        return this.apocbrAccepted;
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (!this.apocbrNpcTarget) {
            super.processServer(packetType, connection);
            return;
        }
        if (!this.apocbrAccepted || this.apocbrProcessed) return;
        this.apocbrProcessed = true;
        IsoPlayer victim = this.target.getPlayer();
        if (!ServerHumanPlayerAttacks.isNpc(victim) || victim.isDead() || victim.isGodMod()) return;
        float before = ServerHumanPlayerDamage.health(victim);
        super.processServer(packetType, connection);
        ServerHumanPlayerDamage.importLoss(this.getPlayer(), victim, this.getHandWeapon(), before);
        this.target.set(victim);
        this.fall.set(0, 0, (byte)0, 0);
        this.apocbrRelay = ServerHumanPlayerAttacks.isNpc(victim);
    }

    public PlayerHitPlayerPacket() {
    }

    public PlayerHitPlayerPacket(IsoPlayer wielder, HandWeapon weapon, CombatManager.TargetHitList hitList, List<TracerInfo> tracers) {
        this.set(wielder, weapon, hitList, tracers);
    }

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (HandWeapon)values[1], (CombatManager.TargetHitList)values[2], (List<TracerInfo>)values[3]);
    }

    @Override
    public void set(IsoPlayer wielder, HandWeapon weapon, CombatManager.TargetHitList hitList, List<TracerInfo> tracers) {
        this.set(wielder, weapon, tracers, hitList.hits());
        this.target.set((IsoPlayer)hitList.target());
        this.fall.set(((IsoPlayer)hitList.target()).getHitReactionNetworkAI());
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        int start = b.position();
        this.apocbrNpcTarget = false;
        this.apocbrAccepted = false;
        this.apocbrProcessed = false;
        this.apocbrRelay = false;
        this.apocbrFingerprint = null;
        super.parse(b, connection);
        int targetStart = b.position();
        this.target.parse(b, connection);
        if (GameServer.server && b.bb.get(targetStart + Short.BYTES)
            == zombie.network.fields.character.CharacterID.CharacterType.PLAYER.ordinal()) {
            ServerHumanPlayerAttacks.resolve(this.target);
            this.apocbrNpcTarget = ServerHumanPlayerAttacks.isNpc(this.target.getPlayer());
        }
        this.fall.parse(b, connection);
        if (this.apocbrNpcTarget) {
            ServerHumanPlayerAttacks.received();
            this.apocbrFingerprint = ServerHumanPlayerAttacks.fingerprint(b.bb, start, b.position());
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.target.write(b);
        this.fall.write(b);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return super.isConsistent(connection) && this.target.isConsistent(connection) && this.target.getPlayer().isAlive();
    }

    @Override
    public void update() {
        super.update();
        this.fall
            .set(
                this.target.getCharacter().realx,
                this.target.getCharacter().realy,
                (byte)PZMath.fastfloor(this.target.getCharacter().realz),
                this.fall.dropDirection
            );
    }

    @Override
    protected void processTarget() {
        if (GameServer.server && this.apocbrNpcTarget) {
            this.target.set(this.target.getPlayer()); // Client victim flags are not authority.
        } else {
            this.target.process();
        }
    }

    @Override
    public void process() {
        IsoGameCharacter targetCharacter = this.target.getCharacter();
        this.processHits(targetCharacter);
        if (!(GameServer.server && this.apocbrNpcTarget)) this.processFall(this.fall, targetCharacter);
    }

    @Override
    public void attack() {
        this.attack(this.getHandWeapon(), true);
        this.processTracers();
    }

    @Override
    protected void reactTarget() {
        this.target.react();
    }

    @Override
    public float getDistance() {
        return IsoUtils.DistanceTo(this.target.getX(), this.target.getY(), this.getX(), this.getY());
    }

    @Override
    public IsoObject getTarget() {
        return this.target.getPlayer();
    }

    @Override
    public boolean isMelee() {
        return this.getHandWeapon().isMelee();
    }
}
