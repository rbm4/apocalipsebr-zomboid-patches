"""Review38 candidate integration with native packet/Hit sources and engine doubles.

No game JAR, physics, Lua runtime, armor RNG or live network is executed. The
candidate is applied to temporary baselines; production src stays untouched.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"
DIFF = V / "docs/reports/2026-10-28-merge-38-player-hit-player.diff"


def candidate(work):
    paths = [line[6:] for line in DIFF.read_text().splitlines() if line.startswith("+++ b/")]
    for name in paths:
        relative = name.removeprefix("42.22.0/src/")
        source = V / "decompiled" / relative
        if not source.exists():
            # Reconstruct original copied support even after candidate application;
            # new helper files have no 42.21 baseline and must start absent.
            source = ROOT / "42.21.0/src" / relative
        if source.exists():
            destination = work / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(source.read_text(encoding="utf-8-sig"), encoding="utf-8", newline="\n")
    subprocess.run(["git", "apply", str(DIFF)], cwd=work, check=True)
    return {name.removeprefix("42.22.0/src/"): (work / name).read_text() for name in paths}


FILES = {}


def stub(path, body):
    package, name = path.rsplit("/", 1)
    FILES[path + ".java"] = "package " + package.replace("/", ".") + ";\n" + body


stub("zombie/network/JSONField", "public @interface JSONField {}")
stub("zombie/network/PacketSetting", "public @interface PacketSetting {int ordering();int priority();int reliability();zombie.characters.Capability requiredCapability();int handlingType();zombie.network.anticheats.AntiCheat[] anticheats() default {};}")
stub("zombie/characters/Capability", "public enum Capability {LoginOnServer}")
stub("zombie/characters/CharacterStat", "public enum CharacterStat {ENDURANCE}")
stub("zombie/characters/skills/PerkFactory", "public class PerkFactory {public enum Perks {Aiming}}")
stub("zombie/AttackType", "public enum AttackType {SHOT,MELEE_TO_FLOOR,MELEE_SWING}")
stub("zombie/core/Core", "public class Core {public static boolean IS_DEV;}")
stub("zombie/util/Type", "public class Type {public static <T>T tryCastTo(Object o,Class<T> t){return t.isInstance(o)?t.cast(o):null;}}")
stub("zombie/util/list/PZArrayUtil", "public class PZArrayUtil {public static <T>void addAll(java.util.Collection<T> a,java.util.Collection<? extends T>b){a.addAll(b);}}")
stub("zombie/core/math/PZMath", "public class PZMath {public static int fastfloor(float f){return (int)Math.floor(f);}public static float sqrt(float f){return (float)Math.sqrt(f);}}")
stub("zombie/core/random/Rand", "public class Rand {public static int value;public static int Next(int n){return Math.min(value,n-1);}public static float Next(float a,float b){return a;}}")
stub("zombie/debug/DebugType", "public class DebugType {public static DebugType Multiplayer=new DebugType();public void error(String s,Object...o){}public void warn(String s,Object...o){}}")
stub("zombie/debug/DebugLog", "public class DebugLog {public static void log(String s){}}")
stub("zombie/Lua/LuaEventManager", "public class LuaEventManager {public static int swings,xp;public static void triggerEvent(String s,Object...a){if(s.equals(\"OnWeaponSwingHitPoint\"))swings++;if(s.equals(\"OnWeaponHitXp\"))xp++;}}")
stub("zombie/EffectsManager", "public class EffectsManager {public static EffectsManager getInstance(){return new EffectsManager();}public void startMuzzleFlash(Object p,int n){}}")
stub("zombie/combat/HitReaction", "public enum HitReaction {KNIFE_DEATH;public String getValue(){return name();}}")
stub("zombie/iso/Vector2", "public class Vector2 {public float x,y;public Vector2 set(float x,float y){this.x=x;this.y=y;return this;}public float getDirection(){return 0;}}")
stub("zombie/iso/IsoObject", "public class IsoObject {public float x,y,z;public float getX(){return x;}public float getY(){return y;}public float getZ(){return z;}}")
stub("zombie/iso/IsoMovingObject", "public class IsoMovingObject extends IsoObject {}")
stub("zombie/iso/IsoUtils", "public class IsoUtils {public static float DistanceTo(float x,float y,float a,float b){return (float)Math.hypot(x-a,y-b);}}")
stub("zombie/iso/IsoGridSquare", "public class IsoGridSquare {public java.util.List<zombie.iso.IsoMovingObject> getMovingObjects(){return java.util.List.of();}}")
stub("zombie/iso/IsoCell", "public class IsoCell {public IsoGridSquare getGridSquare(int x,int y,int z){return new IsoGridSquare();}}")
stub("zombie/vehicles/BaseVehicle", "public class BaseVehicle extends zombie.iso.IsoObject {}")
stub("zombie/characters/HitReactionNetworkAI", "public class HitReactionNetworkAI {public zombie.iso.Vector2 finalPosition=new zombie.iso.Vector2(),finalDirection=new zombie.iso.Vector2();public byte finalPositionZ;public int falls;public void process(float x,float y,byte z,float d){falls++;}}")
stub("zombie/characters/IsoGameCharacter", r'''public class IsoGameCharacter extends zombie.iso.IsoMovingObject {
 public short id;public int lifetime;public float health=1,force=.2f,realx,realy,realz;public boolean dead,god,shove,aim,critical,remote,onFloor;public String reaction="";public int hitCalls;
 public final zombie.iso.Vector2 direction=new zombie.iso.Vector2();public final HitReactionNetworkAI fall=new HitReactionNetworkAI();
 public zombie.inventory.InventoryItem getPrimaryHandItem(){return null;}public Object getCurrentSquare(){return new Object();}public short getOnlineID(){return id;}public int getID(){return lifetime;}public int getZi(){return (int)z;}public boolean isDead(){return dead||health<=0;}public boolean isAlive(){return !isDead();}public float getHealth(){return health;}public void setHealth(float f){health=f;}
 public boolean isAimAtFloor(){return aim;}public void setAimAtFloor(boolean b){aim=b;}public boolean isDoShove(){return shove;}public void setDoShove(boolean b){shove=b;}public boolean isNpc(){return id>=100;}public boolean isProne(){return onFloor;}public boolean isOnFloor(){return onFloor;}public void setOnFloor(boolean b){onFloor=b;}
 public zombie.iso.Vector2 getHitDir(){return direction;}public float getHitForce(){return force;}public void setHitForce(float f){force=f;}public void setAttackedBy(IsoGameCharacter p){}public void addBlood(Object p,boolean a,boolean b,boolean c){}
 public boolean isRemote(){return remote;}public HitReactionNetworkAI getHitReactionNetworkAI(){return fall;}public String getHitReaction(){return reaction;}public void setHitReaction(String s){reaction=s;}public String getVariableString(String s){return reaction;}public float getVariableFloat(String s,float f){return f;}public void setVariable(String s,Object o){}
 public boolean isCloseKilled(){return false;}public boolean isHitFromBehind(){return false;}public boolean isFallOnFront(){return false;}public boolean isKnockedDown(){return false;}public void setCloseKilled(boolean b){}public void setHitFromBehind(boolean b){}public void setFallOnFront(boolean b){}public void setKnockedDown(boolean b){}
 public boolean isCriticalHit(){return critical;}public void setCriticalHit(boolean b){critical=b;}public boolean isBehind(IsoGameCharacter t){return false;}public float DistToSquared(IsoGameCharacter t){return (x-t.x)*(x-t.x)+(y-t.y)*(y-t.y);}public void die(){dead=true;}public void helmetFall(boolean b){}
 public float processHitDamage(zombie.inventory.types.HandWeapon w,IsoGameCharacter a,float d,boolean i,float m){return d;}public float getHittingMod(){return 1;}public void addWorldSoundUnlessInvisible(int a,int b,boolean c){}
 public float Hit(zombie.inventory.types.HandWeapon w,IsoGameCharacter a,float d,boolean ignore,float m,boolean remote){hitCalls++;if(a.isDoShove()&&!a.isAimAtFloor())ignore=true;if(!ignore){if(this instanceof IsoPlayer p){p.body.health-=10;}else health-=d;}return d;}
}''')
stub("zombie/characters/IsoLivingCharacter", "public class IsoLivingCharacter extends IsoGameCharacter {public zombie.inventory.types.HandWeapon bareHands=new zombie.inventory.types.HandWeapon();public zombie.inventory.InventoryItem primary;public zombie.inventory.InventoryItem getPrimaryHandItem(){return primary;}public zombie.inventory.ItemContainer getInventory(){return new zombie.inventory.ItemContainer((IsoPlayer)this);}}")
stub("zombie/characters/IsoPlayer", r'''public class IsoPlayer extends IsoLivingCharacter {
 public float useChargeDelta;public boolean invisible;public Body body=new Body();public Stats stats=new Stats();public Safety safety=new Safety();public zombie.network.fields.hit.AttackVars vars=new zombie.network.fields.hit.AttackVars();public java.util.ArrayList<zombie.network.fields.hit.HitInfo> info=new java.util.ArrayList<>();
 public boolean isGodMod(){return god;}public boolean isInvisible(){return invisible;}public String getUsername(){return "p"+id;}public Body getBodyDamage(){return body;}public Stats getStats(){return stats;}public Safety getSafety(){return safety;}public void syncVisuals(){}
 public zombie.network.fields.hit.AttackVars getAttackVars(){return vars;}public java.util.ArrayList<zombie.network.fields.hit.HitInfo> getHitInfoList(){return info;}public boolean isAttackFromBehind(){return false;}public void setAttackFromBehind(boolean b){}public boolean isDoGrapple(){return false;}public void setDoGrapple(boolean b){}public boolean isDeathDragDown(){return false;}public void setDeathDragDown(boolean b){}public boolean isShoveStompAnim(){return false;}public void setShoveStompAnim(boolean b){}public int getPerkLevel(Object o){return 0;}public zombie.AttackType getAttackType(){return zombie.AttackType.MELEE_SWING;}public void setAttackType(zombie.AttackType a){}
 public void setAttackStarted(boolean b){}public boolean isAttackStarted(){return false;}public void setUseHandWeapon(zombie.inventory.types.HandWeapon w){}public void pressedAttack(){}public void Throw(Object w){}public boolean isLocalPlayer(){return true;}public boolean isAnimal(){return false;}public int getIndex(){return 0;}public int calculateCritChance(Object t){return 0;}public void setAttackTargetSquare(Object s){}public Object getCurrentSquare(){return new Object();}public zombie.iso.IsoCell getCell(){return new zombie.iso.IsoCell();}public zombie.inventory.ItemContainer getInventory(){return new zombie.inventory.ItemContainer(this);}
 public static class Body {public float health=100;public void calculateOverallHealth(){}public float getOverallBodyHealth(){return health;}}
 public static class Stats {public float endurance=1;public float get(CharacterStat s){return endurance;}public void set(CharacterStat s,float f){endurance=f;}}
 public static class Safety {public boolean isEnabled(){return true;}public float getCooldown(){return 0;}public void setCooldown(float f){}}
}''')
stub("zombie/characters/IsoZombie", "public class IsoZombie extends IsoLivingCharacter {public void addAggro(Object a,float d){}public boolean isStaggerBack(){return false;}public boolean isFakeDead(){return false;}public boolean isBecomeCrawler(){return false;}public boolean isCrawling(){return false;}public boolean isKnifeDeath(){return false;}public boolean isJawStabAttach(){return false;}public boolean getAttackDidDamage(){return false;}public String getAttackOutcome(){return \"\";}public String getPlayerAttackPosition(){return \"\";}public void setStaggerBack(boolean b){}public void setFakeDead(boolean b){}public void setBecomeCrawler(boolean b){}public void setCrawler(boolean b){}public void setCrawling(boolean b){}public void setKnifeDeath(boolean b){}public void setJawStabAttach(boolean b){}public void setAttackDidDamage(boolean b){}public void setAttackOutcome(String s){}public void setPlayerAttackPosition(String s){}}")
stub("zombie/characters/animals/IsoAnimal", "public class IsoAnimal extends zombie.characters.IsoPlayer {}")
stub("zombie/popman/animal/AnimalInstanceManager", "public class AnimalInstanceManager {public static AnimalInstanceManager getInstance(){return new AnimalInstanceManager();}public zombie.characters.animals.IsoAnimal get(short id){return null;}}")
stub("zombie/inventory/InventoryItem", "public class InventoryItem {public int id=1;public int getID(){return id;}}")
stub("zombie/inventory/ItemContainer", "public class ItemContainer {zombie.characters.IsoPlayer p;public ItemContainer(zombie.characters.IsoPlayer p){this.p=p;}public Object getItemWithID(int id){return p.primary!=null&&p.primary.id==id?p.primary:null;}}")
stub("zombie/inventory/types/HandWeapon", r'''public class HandWeapon extends zombie.inventory.InventoryItem {
 public boolean ranged;public int projectiles=1,maxHits=1;public int getProjectileCount(){return projectiles;}public int getMaxHitCount(){return maxHits;}public float getMaxRange(zombie.characters.IsoPlayer p){return 10;}public boolean isRanged(){return ranged;}public boolean isMelee(){return !ranged;}public boolean isAimedFirearm(){return ranged;}public boolean isRangeFalloff(){return false;}public String getName(){return "weapon";}public String getDisplayName(){return "weapon";}public String getFullType(){return "Base.Weapon";}public Object getPhysicsObject(){return null;}public int getCondition(){return 10;}public int getHitChance(){return 80;}public float getCriticalChance(){return 10;}public float getMinDamage(){return .1f;}public float getMaxDamage(){return .2f;}public float getDamageMod(Object a){return 1;}public int getSoundRadius(){return 1;}public int getSoundVolume(){return 1;}public String getSwingSound(){return "swing";}
}''')
stub("zombie/inventory/types/WeaponType", "public enum WeaponType {UNARMED;public static WeaponType getWeaponType(Object p){return UNARMED;}}")
stub("zombie/network/GameServer", r'''public class GameServer {
 public static boolean server=true;public static java.util.Map<Short,zombie.characters.IsoPlayer>IDToPlayerMap=new java.util.HashMap<>();public static java.util.Map<zombie.characters.IsoPlayer,zombie.core.raknet.UdpConnection>connections=new java.util.IdentityHashMap<>();public static int disconnects;public static zombie.core.raknet.UdpConnection getConnectionFromPlayer(zombie.characters.IsoPlayer p){return connections.get(p);}public static void doDelayedDisconnect(Object p){disconnects++;}public static void addDelayToDelayedConnection(String s,int i){disconnects++;}public static void sendChangeSafety(Object o){}
}''')
stub("zombie/network/GameClient", "public class GameClient {public static boolean client;public static java.util.Map<Short,zombie.characters.IsoPlayer>IDToPlayerMap=new java.util.HashMap<>();public static java.util.Map<Short,zombie.characters.IsoZombie>IDToZombieMap=new java.util.HashMap<>();}")
stub("zombie/network/ServerMap", "public class ServerMap {public static ServerMap instance=new ServerMap();public java.util.Map<Short,zombie.characters.IsoZombie>zombieMap=new java.util.HashMap<>();}")
stub("zombie/network/ServerOptions", "public class ServerOptions {public static ServerOptions getInstance(){return new ServerOptions();}public Option safetyCooldownTimer=new Option();public static class Option{public int getValue(){return 1;}}}")
stub("zombie/network/PVPLogTool", "public class PVPLogTool {public static void logCombat(Object a,Object b,String s,float f){}}")
stub("zombie/network/IConnection", "public interface IConnection {zombie.characters.IsoPlayer getPlayerAt(int i);boolean hasPlayer(zombie.characters.IsoPlayer p);default boolean isOwnPlayerOnServer(zombie.characters.IsoGameCharacter c){return !GameServer.server||c instanceof zombie.characters.IsoPlayer p&&hasPlayer(p);}}")
stub("zombie/core/raknet/UdpConnection", "public class UdpConnection implements zombie.network.IConnection {public zombie.characters.IsoPlayer[] players=new zombie.characters.IsoPlayer[4];public zombie.characters.IsoPlayer getPlayerAt(int i){return players[i];}public boolean hasPlayer(zombie.characters.IsoPlayer p){for(var own:players)if(own==p)return true;return false;}public boolean isFullyConnected(){return true;}}")
stub("zombie/network/packets/IDescriptor", "public interface IDescriptor {default String getDescription(){return getClass().getSimpleName();}}")
stub("zombie/network/packets/INetworkPacket", r'''public interface INetworkPacket extends zombie.network.fields.INetworkPacketField {
 default void setData(Object...v){}default void processServer(zombie.network.PacketTypes.PacketType t,zombie.core.raknet.UdpConnection c){}default void processClient(zombie.core.raknet.UdpConnection c){}default void sync(zombie.network.PacketTypes.PacketType t,zombie.network.IConnection c){}
 public static java.util.List<byte[]> SENT=new java.util.ArrayList<>();default void sendToClient(zombie.network.PacketTypes.PacketType t,zombie.network.IConnection c){if(t!=zombie.network.PacketTypes.PacketType.PlayerHit)throw new AssertionError("standalone child send");var b=java.nio.ByteBuffer.allocate(65536);write(new zombie.core.network.ByteBufferWriter(b));SENT.add(java.util.Arrays.copyOf(b.array(),b.position()));}default void sendToRelativeClients(zombie.network.PacketTypes.PacketType t,zombie.core.raknet.UdpConnection c,float x,float y){sendToClient(t,c);}static void send(zombie.network.IConnection c,zombie.network.PacketTypes.PacketType t,Object...v){}
}''')
stub("zombie/network/PacketTypes", "public class PacketTypes {public enum PacketType {PlayerHit,PlayerHitSquare,PlayerHitObject,PlayerHitAnimal,PlayerHitPlayer,PlayerHitZombie,PlayerHitVehicle,HitStatistic,RangedWeaponSound;public zombie.network.anticheats.AntiCheat[] anticheats={zombie.network.anticheats.AntiCheat.HitWeapon};public static PacketType getById(short id){return id>=0&&id<values().length?values()[id]:null;}}}")
stub("zombie/network/anticheats/AntiCheat", "public enum AntiCheat {None,HitLongDistance,HitWeapon,Safety;public static boolean valid=true;public boolean isEnabled(){return true;}public boolean isValid(zombie.core.raknet.UdpConnection c,Object p){return valid;}}")
for name, methods in {
    "AntiCheatHitLongDistance": "float getDistance();",
    "AntiCheatHitWeapon": "zombie.inventory.types.HandWeapon getHandWeapon();zombie.characters.IsoPlayer getPlayer();int getHitCount();float getDistance();",
    "AntiCheatSafety": "zombie.iso.IsoObject getTarget();zombie.characters.IsoPlayer getPlayer();boolean isMelee();",
}.items():
    stub("zombie/network/anticheats/" + name, "public class " + name + " {public interface IAntiCheat {" + methods + "}}")
stub("zombie/network/fields/hit/AttackVars", r'''public class AttackVars {
 public boolean closeKill,aimAtFloor;public float useChargeDelta,targetDistance;public Target targetOnGround=new Target(),targetStanding=new Target();public void clear(){}public void setWeapon(Object w){}public void copy(AttackVars v){aimAtFloor=v.aimAtFloor;closeKill=v.closeKill;}public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){aimAtFloor=b.getBoolean();}public void write(zombie.core.network.ByteBufferWriter b){b.putBoolean(aimAtFloor);}public static class Target{public void set(Object t){}}
}''')
stub("zombie/network/fields/hit/HitInfo", "public class HitInfo {public HitInfo init(Object o,float a,float b,float x,float y,float z){return this;}public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){}public void write(zombie.core.network.ByteBufferWriter b){}}")
stub("zombie/network/fields/hit/TracerInfo", "public class TracerInfo {public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){}public void write(zombie.core.network.ByteBufferWriter b){}public void process(Object p){}}")
stub("zombie/network/fields/Square", "public class Square {public void set(Object p){}public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){}public void write(zombie.core.network.ByteBufferWriter b){}public boolean isConsistent(zombie.network.IConnection c){return true;}public void process(Object p){}public float getX(){return 0;}public float getY(){return 0;}}")
stub("zombie/CombatManager", r'''public class CombatManager {
 public static boolean miss;public static int maintenance,weaponEndurance,meleeEndurance;public static CombatManager getInstance(){return new CombatManager();}public static boolean isHitRolledByClient(zombie.iso.IsoMovingObject t){return t instanceof zombie.characters.IsoZombie;}
 public HitData getHitData(Object a,Object w,Object t,float c){return miss?null:new HitData((byte)0,false,false,false,true);}public float getHitDistanceSquared(Object a,Object t){return 1;}public Chance calculateHitChanceData(Object a,Object w,Object t,float d){return new Chance();}public record Chance(){public float hitChance(){return 100;}}
 public HitData calculateHitData(Object a,Object w,Object t,boolean ignore){return new HitData((byte)0,false,ignore,false,true);}public HitDamageData getHitDamage(Object a,Object w,Object t,HitData d,int i){return new HitDamageData(.1f,1,false,false);}public void processHit(Object w,Object a,Object t){}public void attachKnife(Object a,Object t){}public void splash(Object t,Object w,Object a){}public void applyMeleeEnduranceLoss(Object a,Object t,Object w,float d){meleeEndurance++;}public void processMaintenanceCheck(Object a,Object w,Object t){maintenance++;}public void processWeaponEndurance(zombie.characters.IsoPlayer p,Object w){weaponEndurance++;p.stats.endurance-=.01f;}public void clearDotList(){}public boolean isCloseKill(Object a,Object w,Object t,float d){return false;}public boolean isCriticalHit(Object a,Object w,Object t,float d){return false;}
 public record HitData(byte hitHead,boolean hitLegs,boolean isIgnoreDamage,boolean isPiercedTargetDamageReduction,boolean isValidHitLocation){}public record HitDamageData(float damageSplit,float rangeDel,boolean isHelmetFall,boolean isRemoveKnife){}public record TargetHitList(zombie.iso.IsoObject target,java.util.List<zombie.network.fields.hit.Hit> hits){}
}''')

# Logical engine doubles record integration effects, not their real persistence/AI.
stub("zombie/apocalipse/humans/brain/HumanObservation", "public class HumanObservation {public record Identity(short id,int lifetime){}}")
stub("zombie/apocalipse/humans/brain/HumanCombatDamage", "public class HumanCombatDamage {public static float packetDamage(float d,boolean r){return d;}}")
stub("zombie/apocalipse/humans/encounters/HumanRelationships", "public class HumanRelationships {public enum Outcome{ATTACKED}}")
stub("zombie/apocalipse/humans/encounters/EncounterPort", "public class EncounterPort {public record Player(Object i,Object k,int n,boolean a,boolean b){}}")
stub("zombie/apocalipse/humans/state/HumanState", "public class HumanState {public enum Stat{ENDURANCE}}")
stub("zombie/apocalipse/humans/state/HumanMutation", "public interface HumanMutation {public record Damage(float loss,float a,float b,float c)implements HumanMutation{}public record Adjust(HumanState.Stat stat,float delta)implements HumanMutation{}}")
stub("zombie/apocalipse/humans/ServerHuman", r'''final class ServerHuman {
 final zombie.characters.IsoPlayer player;final ServerHumanAttackReplay playerAttackReplay=new ServerHumanAttackReplay();long nextPlayerHitTrace;int playerHitTraceLines;Object deathKiller;
 final Profile profile=new Profile();final Definition definition=new Definition();final State state=new State();final Physiology physiology=new Physiology();final Speech speech=new Speech();final Services movement=new Services(),perception=new Services(),territory=new Services();final Inventory inventory=new Inventory();final java.util.Set<zombie.core.raknet.UdpConnection>viewers=new java.util.HashSet<>();
 ServerHuman(zombie.characters.IsoPlayer p){player=p;}boolean isRelevant(Object v){return true;}
 static class Profile {String id(){return "test";}int revision(){return 1;}Profile encounter(){return this;}boolean enabled(){return true;}}
 static class Definition{String key(){return "npc";}}
 class Physiology{void project(){player.health=state.health;player.body.health=100*state.health;}}
 static class State{float health=1,endurance=1;int damageCommands;State snapshot(){return this;}boolean dead(){return health<=0;}State vitals(){return this;}float health(){return health;}float endurance(){return endurance;}void dispatch(zombie.apocalipse.humans.state.HumanMutation m){if(m instanceof zombie.apocalipse.humans.state.HumanMutation.Damage d){health-=d.loss();damageCommands++;}if(m instanceof zombie.apocalipse.humans.state.HumanMutation.Adjust a)endurance+=a.delta();}}
 static class Speech {int attacks,damages;void attacked(Object a,Object w,long t){attacks++;}void damage(Object a,float d,Object w,long t){damages++;}void attack(Object p,Object w,long t){}void zombieKilled(long t){}}
 static class Services {boolean traversing(){return false;}boolean eligible(Object p){return true;}boolean visible(Object p){return true;}boolean contains(float x,float y,float z){return true;}}
 class Inventory{zombie.inventory.types.HandWeapon item(int id){return (zombie.inventory.types.HandWeapon)player.primary;}}
}''')
stub("zombie/apocalipse/humans/ServerHumanFactory", "public class ServerHumanFactory {static java.util.Map<Short,ServerHuman> humans=new java.util.HashMap<>();static ServerHuman tradingHuman(short id){return humans.get(id);}}")
stub("zombie/apocalipse/humans/ServerHumanRetaliation", "public class ServerHumanRetaliation {static boolean pvp=true;static boolean pvp(Object a,Object t,boolean r){return pvp;}static Module module(ServerHuman h){return new Module();}static class Module{void attacked(Object i,long n){}}}")
stub("zombie/apocalipse/humans/ServerHumanAttackLine", "public class ServerHumanAttackLine {static boolean clear=true;static boolean clear(ServerHuman h,Object a,float x,float y,boolean r){return clear;}}")
stub("zombie/apocalipse/humans/ServerHumanEncounters", "public class ServerHumanEncounters {ServerHumanEncounters(ServerHuman h){}void record(Object p,Object o,Object c){}static Object key(ServerHuman h,Object p){return p;}}")
stub("zombie/apocalipse/humans/ServerHumanDialogue", "public class ServerHumanDialogue {static void close(ServerHuman h,String s){}}")
stub("zombie/apocalipse/humans/ServerHumanTrading", "public class ServerHumanTrading {static void close(ServerHuman h){}}")
stub("zombie/apocalipse/humans/ServerHumanTransport", "public class ServerHumanTransport {static void equipment(ServerHuman h){}}")
stub("zombie/apocalipse/humans/ServerHumanCombatAudio", "public class ServerHumanCombatAudio {static void impact(ServerHuman h,Object t,Object w){}}")
stub("zombie/apocalipse/humans/HumanLog", "public class HumanLog {public enum Level{INFO,DEBUG}static void log(Level l,java.util.function.Supplier<String>s){}}")

NATIVE = [
    "zombie/network/fields/INetworkPacketField.java", "zombie/network/fields/IPositional.java",
    "zombie/network/fields/IDShort.java", "zombie/network/fields/IDInteger.java",
    "zombie/network/fields/character/Player.java", "zombie/network/fields/character/ConnectionPlayer.java",
    "zombie/network/fields/character/CharacterID.java", "zombie/network/fields/hit/Character.java",
    "zombie/network/fields/hit/Player.java", "zombie/network/fields/hit/Zombie.java",
    "zombie/network/fields/hit/Weapon.java", "zombie/network/fields/hit/Fall.java",
    "zombie/network/packets/hit/PlayerHit.java", "zombie/network/packets/hit/HitCharacter.java",
    "zombie/network/packets/hit/PlayerHitZombiePacket.java", "zombie/network/packets/hit/PlayerHitSquarePacket.java",
    "zombie/core/network/ByteBufferReader.java",
]


def method(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def main():
    with tempfile.TemporaryDirectory(prefix="pz-npc-hit-port-") as directory:
        work = Path(directory)
        sources = candidate(work)
        # ServerHuman is a composition root; check its proposed replay state but
        # compile a logical-engine double instead of initializing the entire NPC.
        assert "lastPlayerShot" not in sources.pop("zombie/apocalipse/humans/ServerHuman.java")
        factory = sources.pop("zombie/apocalipse/humans/ServerHumanFactory.java")
        assert method(factory, "private short allocateId()") == method(
            (ROOT / "42.21.0/src/zombie/apocalipse/humans/ServerHumanFactory.java").read_text(),
            "private short allocateId()",
        )
        server = sources.pop("zombie/network/GameServer.java")
        assert "static final int FIRST_ID = ServerHumanPresentationIds.FIRST_ID;" in factory
        assert "static final int LAST_ID = ServerHumanPresentationIds.LAST_ID;" in factory
        native_server = (V / "decompiled/zombie/network/GameServer.java").read_text(encoding="utf-8-sig")
        server_change = """if (SlotToConnection[n] == null
                && !zombie.apocalipse.humans.ServerHumanPresentationIds.reservesConnectionSlot(n))"""
        assert server.replace(server_change, "if (SlotToConnection[n] == null)") == native_server
        # Execute the actual candidate allocation methods against map/slot doubles.
        # Do not compile a fabricated replacement for the complete GameServer/factory.
        sources.update(FILES)
        fake_factory = sources["zombie/apocalipse/humans/ServerHumanFactory.java"].replace("public class ServerHumanFactory", "import zombie.network.GameServer;public class ServerHumanFactory")
        sources["zombie/apocalipse/humans/ServerHumanFactory.java"] = fake_factory[:-1] + """
+          static final int FIRST_ID=ServerHumanPresentationIds.FIRST_ID,LAST_ID=ServerHumanPresentationIds.LAST_ID;
+          private int nextId=FIRST_ID;short testAllocate(){return allocateId();}
+        """.replace("\n+", "\n") + method(factory, "private short allocateId()") + "}"
        fake_server = sources["zombie/network/GameServer.java"]
        sources["zombie/network/GameServer.java"] = fake_server[:-1] + """
+          public static java.util.Map<Short,Long>IDToAddressMap=new java.util.HashMap<>();
+          public static zombie.core.raknet.UdpConnection[] SlotToConnection=new zombie.core.raknet.UdpConnection[255];
+          public static Engine udpEngine=new Engine();public static class Engine{public int getMaxConnections(){return 255;}}
+          public static short testFreeSlot(){return getFreeSlot();}
+        """.replace("\n+", "\n") + method(server, "private static short getFreeSlot()") + "}"
        for relative in NATIVE:
            sources[relative] = (V / "decompiled" / relative).read_text(encoding="utf-8-sig")
        # Real writer has unrelated GameWindow dependencies; this double implements
        # only its inspected primitive/UTF/enum byte contract.
        sources["zombie/core/network/ByteBufferWriter.java"] = '''package zombie.core.network;public class ByteBufferWriter {public final java.nio.ByteBuffer bb;public ByteBufferWriter(java.nio.ByteBuffer b){bb=b;}public void putByte(int b){bb.put((byte)b);}public void putShort(int s){bb.putShort((short)s);}public void putInt(int i){bb.putInt(i);}public void putFloat(float f){bb.putFloat(f);}public void putBoolean(boolean b){bb.put((byte)(b?1:0));}public void putUTF(String s){byte[] v=s.getBytes(java.nio.charset.StandardCharsets.UTF_8);bb.putShort((short)v.length).put(v);}public void putEnum(Enum<?> e){bb.put((byte)e.ordinal());}}'''
        for name in ["PlayerHitObjectPacket", "PlayerHitAnimalPacket", "PlayerHitVehiclePacket"]:
            sources["zombie/network/packets/hit/" + name + ".java"] = "package zombie.network.packets.hit;public class " + name + " extends PlayerHitSquarePacket {}"
        sources["zombie/apocalipse/humans/CombatTest.java"] = TEST
        java = []
        for relative, content in sources.items():
            destination = work / "fixture" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content, encoding="utf-8")
            java.append(str(destination))
        subprocess.run(["javac", "-d", str(work / "classes"), *java], check=True)
        subprocess.run(["java", "-cp", str(work / "classes"), "zombie.apocalipse.humans.CombatTest"], check=True)


TEST = r'''package zombie.apocalipse.humans;
import java.nio.*;import java.util.*;import zombie.characters.*;import zombie.inventory.types.*;import zombie.network.*;import zombie.network.fields.hit.*;import zombie.network.packets.hit.*;import zombie.network.packets.INetworkPacket;import zombie.core.network.*;import zombie.core.raknet.*;import zombie.CombatManager;
public class CombatTest {
 static class RandSetter{static void set(int value){zombie.core.random.Rand.value=value;}}
 static int checks;static void check(boolean b){if(!b)throw new AssertionError("check "+checks);checks++;}
 static IsoPlayer actor,npc;static HandWeapon weapon;static UdpConnection connection;static ServerHuman human;
 static void setup(){GameServer.server=true;GameClient.client=false;System.setProperty("apocbr.npc.playerHitTrace","false");actor=new IsoPlayer();actor.id=1;actor.lifetime=10;weapon=new HandWeapon();weapon.id=2;actor.primary=weapon;actor.bareHands.id=-10;npc=new IsoPlayer();npc.id=(short)ServerHumanPresentationIds.FIRST_ID;npc.lifetime=20;npc.x=1;human=new ServerHuman(npc);ServerHumanFactory.humans.clear();ServerHumanFactory.humans.put(npc.id,human);connection=new UdpConnection();connection.players[0]=actor;GameServer.IDToPlayerMap.clear();GameServer.IDToAddressMap.clear();GameServer.IDToPlayerMap.put(actor.id,actor);GameServer.connections.clear();GameServer.disconnects=0;GameServer.connections.put(actor,connection);INetworkPacket.SENT.clear();CombatManager.miss=false;zombie.core.random.Rand.value=0;zombie.network.anticheats.AntiCheat.valid=true;ServerHumanRetaliation.pvp=true;ServerHumanAttackLine.clear=true;}
 static Hit hit(float d){var h=new Hit();h.set(.2f,1,0,new CombatManager.HitData((byte)0,false,false,false,true),false,d);return h;}
 static byte[] envelope(IsoPlayer target,List<Hit> hits){var p=new PlayerHitPacket();p.setData(actor,weapon,List.of(new CombatManager.TargetHitList(target,hits)),List.of());var b=ByteBuffer.allocate(8192);p.write(new ByteBufferWriter(b));byte[] wire=Arrays.copyOf(b.array(),b.position());ByteBuffer.wrap(wire).putShort((short)0);return wire;}
 static PlayerHitPacket parse(byte[] bytes){var p=new PlayerHitPacket();p.parse(new ByteBufferReader(ByteBuffer.wrap(bytes)),connection);return p;}
 static void dispatch(byte[] bytes){var p=parse(bytes);check(p.isConsistent(connection));p.processServer(PacketTypes.PacketType.PlayerHit,connection);}
 static int relayedChildren(){return ByteBuffer.wrap(INetworkPacket.SENT.getLast()).get(6);}
 public static void main(String[] args){
  // Native login allocation excludes complete reserved slots even before NPC creation.
  setup();GameServer.SlotToConnection=new UdpConnection[255];
  for(int slot=0;slot<192;slot++){check(GameServer.testFreeSlot()==slot);GameServer.SlotToConnection[slot]=new UdpConnection();for(int split=0;split<4;split++){int id=slot*4+split;check(id<ServerHumanPresentationIds.FIRST_ID);}}
  check(GameServer.testFreeSlot()==-1);GameServer.SlotToConnection[7]=null;check(GameServer.testFreeSlot()==7);GameServer.SlotToConnection[7]=new UdpConnection();check(GameServer.testFreeSlot()==-1);
  check(ServerHumanPresentationIds.FIRST_ID==768&&ServerHumanPresentationIds.LAST_ID==1023);check(!ServerHumanPresentationIds.reservesConnectionSlot(-1));check(ServerHumanPresentationIds.reservesConnectionSlot(192));check(ServerHumanPresentationIds.reservesConnectionSlot(254));check(ServerHumanPresentationIds.reservesConnectionSlot(255));
  // Actual monotonic factory method consumes each reserved ID once and fails closed.
  var allocator=new ServerHumanFactory();var allocated=new HashSet<Short>();for(int i=0;i<256;i++){short id=allocator.testAllocate();check(id==768+i);check(allocated.add(id));}for(int retry=0;retry<2;retry++){try{allocator.testAllocate();throw new AssertionError("ID recycled");}catch(IllegalStateException expected){check(expected.getMessage().contains("restart required"));}}
  setup();allocator=new ServerHumanFactory();GameServer.IDToPlayerMap.put((short)768,actor);GameServer.IDToAddressMap.put((short)769,1L);check(allocator.testAllocate()==770);GameServer.IDToPlayerMap.clear();GameServer.IDToAddressMap.clear();check(allocator.testAllocate()==771); // Removed/failed identities are not recycled.
  // Replay cache boundaries, identity separation, saturation, immutable stored hash.
  var cache=new ServerHumanAttackReplay();Object a=new Object(),c=new Object(),w=new Object();byte[] d=new byte[32];check(cache.accept(a,c,w,d,0));d[0]=1;check(cache.accept(a,c,w,d,1));check(!cache.accept(a,c,w,new byte[32],2));check(cache.accept(a,c,w,new byte[32],500_000_000));check(cache.accept(new Object(),c,w,new byte[32],500_000_001));check(!cache.accept(a,c,w,null,0));
  cache=new ServerHumanAttackReplay();for(int i=0;i<128;i++){var hash=new byte[32];hash[0]=(byte)i;check(cache.accept(a,c,w,hash,0));}var extra=new byte[32];extra[1]=1;check(!cache.accept(a,c,w,extra,1));check(cache.accept(a,c,w,extra,500_000_000));
  setup();byte[] bytes=envelope(npc,List.of(hit(199)));int disconnects=GameServer.disconnects;dispatch(bytes);check(npc.hitCalls==1);check(human.state.damageCommands==1);check(Math.abs(human.state.health-.9f)<.00001);check(human.speech.attacks==1&&human.speech.damages==1);check(GameServer.disconnects==disconnects);check(relayedChildren()==1);dispatch(bytes);check(npc.hitCalls==1&&human.state.damageCommands==1);check(relayedChildren()==0);
  // New payloads stay eligible during the window (no blanket attack cooldown).
  dispatch(envelope(npc,List.of(hit(198))));check(npc.hitCalls==2);check(human.state.damageCommands==2);
  setup();CombatManager.miss=true;dispatch(envelope(npc,List.of(hit(200))));check(npc.hitCalls==0&&human.state.damageCommands==0);check(human.speech.attacks==1);
  setup();actor.shove=true;dispatch(envelope(npc,List.of(hit(1))));check(npc.hitCalls==1&&human.state.damageCommands==0);
  setup();actor.shove=true;actor.aim=true;dispatch(envelope(npc,List.of(hit(1))));check(human.state.damageCommands==1);
  setup();npc.body.health=5;human.state.health=.05f;dispatch(envelope(npc,List.of(hit(1))));check(human.state.dead());check(human.deathKiller==actor);check(GameServer.disconnects==disconnects);
  setup();npc.remote=true;npc.onFloor=false;var evil=new PlayerHitPlayerPacket();evil.set(actor,weapon,new CombatManager.TargetHitList(npc,List.of(hit(1))),List.of());evil.target.setCharacterFlags((short)32);var buf=ByteBuffer.allocate(8192);evil.write(new ByteBufferWriter(buf));var child=new PlayerHitPlayerPacket();child.parse(new ByteBufferReader(buf.flip()),connection);check(child.isConsistent(connection));check(child.apocbrAccept(connection,actor));child.processServer(PacketTypes.PacketType.PlayerHitPlayer,connection);check(!npc.onFloor&&npc.fall.falls==0);int calls=npc.hitCalls;child.processServer(PacketTypes.PacketType.PlayerHitPlayer,connection);check(npc.hitCalls==calls);
  // Victim mapping with a mismatched native CharacterType must not become NPC.
  setup();var bare=new PlayerHitPlayerPacket();bare.set(actor,weapon,new CombatManager.TargetHitList(npc,List.of(hit(1))),List.of());bare.target.set(npc.id,zombie.network.fields.character.CharacterID.CharacterType.ZOMBIE);buf=ByteBuffer.allocate(8192);bare.write(new ByteBufferWriter(buf));child=new PlayerHitPlayerPacket();child.parse(new ByteBufferReader(buf.flip()),connection);check(!child.apocbrNpcTarget());check(!child.isConsistent(connection));
  // Gates reject before damage and remove the NPC child from outward relay.
  for(int mode=0;mode<8;mode++){setup();bytes=envelope(npc,List.of(hit(1)));switch(mode){case 0->GameServer.connections.clear();case 1->npc.god=true;case 2->ServerHumanRetaliation.pvp=false;case 3->ServerHumanAttackLine.clear=false;case 4->npc.x=100;case 5->npc.x=Float.NaN;case 6->actor.dead=true;case 7->human.state.health=0;}dispatch(bytes);check(npc.hitCalls==0&&human.speech.attacks==0);check(relayedChildren()==0);}
  setup();zombie.network.anticheats.AntiCheat.valid=false;dispatch(envelope(npc,List.of(hit(1))));check(npc.hitCalls==0);check(relayedChildren()==0);
  setup();var impostor=new IsoPlayer();impostor.id=3;GameServer.IDToPlayerMap.put(impostor.id,impostor);var oldActor=actor;actor=impostor;impostor.primary=weapon;bytes=envelope(npc,List.of(hit(1)));actor=oldActor;dispatch(bytes);check(npc.hitCalls==0);check(relayedChildren()==0);
  // Validate native Hit payload fields/counts; do not use client damage as NPC authority.
  setup();for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1,201})check(!ServerHumanPlayerDamage.valid(List.of(hit(bad)),weapon));check(!ServerHumanPlayerDamage.valid(List.of(),weapon));check(!ServerHumanPlayerDamage.valid(List.of(hit(1),hit(1)),weapon));check(ServerHumanPlayerDamage.valid(List.of(hit(200)),weapon));
  setup();var p=new PlayerHitPacket();p.setData(actor,weapon,List.of(new CombatManager.TargetHitList(npc,List.of(hit(1))),new CombatManager.TargetHitList(npc,List.of(hit(2)))),List.of());buf=ByteBuffer.allocate(8192);p.write(new ByteBufferWriter(buf));bytes=Arrays.copyOf(buf.array(),buf.position());ByteBuffer.wrap(bytes).putShort((short)0);dispatch(bytes);check(npc.hitCalls==1&&relayedChildren()==1);
  // Removing an NPC claim must also remove it from common critical/closest-target selection.
  setup();var realVictim=new IsoPlayer();realVictim.id=3;realVictim.x=3;GameServer.IDToPlayerMap.put(realVictim.id,realVictim);p=new PlayerHitPacket();p.setData(actor,weapon,List.of(new CombatManager.TargetHitList(npc,List.of(hit(1))),new CombatManager.TargetHitList(realVictim,List.of(hit(1)))),List.of());buf=ByteBuffer.allocate(8192);p.write(new ByteBufferWriter(buf));bytes=Arrays.copyOf(buf.array(),buf.position());ByteBuffer.wrap(bytes).putShort((short)0);p=parse(bytes);npc.god=true;p.processServer(PacketTypes.PacketType.PlayerHit,connection);check(npc.hitCalls==0&&realVictim.hitCalls==1&&relayedChildren()==1);try{var field=PlayerHitPacket.class.getDeclaredField("closestTarget");field.setAccessible(true);check(field.get(p)==realVictim);}catch(ReflectiveOperationException problem){throw new AssertionError(problem);}
  // Outgoing NPC combat: native envelope, one local authored hit, no client intake.
  setup();human.player.primary=weapon;human.viewers.add(connection);var zombie=new IsoZombie();zombie.id=9;zombie.x=2;GameServer.IDToPlayerMap.put(npc.id,npc);GameServer.IDToPlayerMap.put(actor.id,actor);int endurance=CombatManager.weaponEndurance;check(ServerHumanCombatNetwork.commit(human,zombie,weapon.id,1,1,0));check(zombie.hitCalls==1);check(CombatManager.weaponEndurance==endurance+1);check(INetworkPacket.SENT.size()==1);check(relayedChildren()==1);
  // Compare the custom outbound envelope against the native serializer for the same child.
  setup();var authored=new ServerHumanPlayerHitPacket(npc,weapon,actor,List.of(new ServerHumanCommittedHit(.1f,.2f,1,0)));var out=new ServerHumanCombatEnvelope(npc,weapon,PacketTypes.PacketType.PlayerHitPlayer,authored);buf=ByteBuffer.allocate(8192);out.write(new ByteBufferWriter(buf));var nativeOut=new PlayerHitPacket();nativeOut.setData(npc,weapon,List.of(new CombatManager.TargetHitList(actor,List.of(new ServerHumanCommittedHit(.1f,.2f,1,0)))),List.of());var other=ByteBuffer.allocate(8192);nativeOut.write(new ByteBufferWriter(other));check(Arrays.equals(Arrays.copyOf(buf.array(),buf.position()),Arrays.copyOf(other.array(),other.position())));

  // Changed attacker instance/connection/weapon have distinct cache ownership.
  cache=new ServerHumanAttackReplay();check(cache.accept(a,c,w,new byte[32],0));check(cache.accept(a,new Object(),w,new byte[32],1));check(cache.accept(a,c,new Object(),new byte[32],2));
  // Fingerprinting cannot consume or mutate the receive buffer.
  buf=ByteBuffer.wrap(new byte[]{0,1,2,3,4});buf.position(2);var first=ServerHumanPlayerAttacks.fingerprint(buf,1,4);check(buf.position()==2&&buf.limit()==5);check(Arrays.equals(first,ServerHumanPlayerAttacks.fingerprint(ByteBuffer.wrap(new byte[]{8,1,2,3,9}),1,4)));
  // A target removed/replaced after parsing is not damageable through its old instance.
  setup();bare=new PlayerHitPlayerPacket();bare.set(actor,weapon,new CombatManager.TargetHitList(npc,List.of(hit(1))),List.of());buf=ByteBuffer.allocate(8192);bare.write(new ByteBufferWriter(buf));child=new PlayerHitPlayerPacket();child.parse(new ByteBufferReader(buf.flip()),connection);var replacement=new IsoPlayer();replacement.id=npc.id;ServerHumanFactory.humans.put(npc.id,new ServerHuman(replacement));check(!child.apocbrAccept(connection,actor));child.processServer(PacketTypes.PacketType.PlayerHitPlayer,connection);check(npc.hitCalls==0&&replacement.hitCalls==0&&!child.apocbrRelayAllowed());
  // Client target force/reaction/fall flags cannot replace native server NPC state.
  setup();var forceHit=new Hit();forceHit.set(999,999,999,new CombatManager.HitData((byte)127,true,false,false,true),false,199);dispatch(envelope(npc,List.of(forceHit)));check(npc.force==.2f&&npc.direction.x==0&&npc.direction.y==0);
  setup();var badForce=hit(1);buf=ByteBuffer.allocate(20);badForce.write(new ByteBufferWriter(buf));buf.putFloat(0,Float.NaN);badForce.parse(new ByteBufferReader(buf.flip()),connection);check(!ServerHumanPlayerDamage.valid(List.of(badForce),weapon));weapon.projectiles=Integer.MAX_VALUE;weapon.maxHits=Integer.MAX_VALUE;check(ServerHumanPlayerDamage.valid(Collections.nCopies(32,hit(1)),weapon));check(!ServerHumanPlayerDamage.valid(Collections.nCopies(33,hit(1)),weapon));
  // NPC -> player uses the same single local commit and one maintenance event.
  setup();npc.primary=weapon;human.viewers.add(connection);int maintenance=CombatManager.maintenance;check(ServerHumanCombatNetwork.commit(human,actor,weapon.id,1,1,0));check(actor.hitCalls==1&&CombatManager.maintenance==maintenance+1);check(human.player.body.health==100);check(ByteBuffer.wrap(INetworkPacket.SENT.getLast()).getInt(7)==PacketTypes.PacketType.PlayerHitPlayer.ordinal());
  // NPC ranged miss still presents a native square child; no local damage.
  setup();npc.primary=weapon;weapon.ranged=true;human.viewers.add(connection);zombie=new IsoZombie();zombie.id=9;zombie.x=2;RandSetter.set(99);endurance=CombatManager.weaponEndurance;check(ServerHumanCombatNetwork.commit(human,zombie,weapon.id,2,1,0));check(zombie.hitCalls==0&&CombatManager.weaponEndurance==endurance+1);check(ByteBuffer.wrap(INetworkPacket.SENT.getLast()).getInt(7)==PacketTypes.PacketType.PlayerHitSquare.ordinal());
  // Actual client-side envelope/Hit path resolves server online IDs, no intake.
  setup();npc.primary=weapon;GameServer.server=false;GameClient.client=true;GameClient.IDToPlayerMap.put(npc.id,npc);GameClient.IDToPlayerMap.put(actor.id,actor);nativeOut=new PlayerHitPacket();GameServer.server=true;nativeOut.setData(npc,weapon,List.of(new CombatManager.TargetHitList(actor,List.of(hit(.1f)))),List.of());buf=ByteBuffer.allocate(8192);nativeOut.write(new ByteBufferWriter(buf));GameServer.server=false;nativeOut=new PlayerHitPacket();nativeOut.parse(new ByteBufferReader(buf.flip()),connection);check(nativeOut.isConsistent(connection));nativeOut.processClient(connection);check(actor.hitCalls==1&&human.speech.attacks==0); // Proposed IDs pass the unmodified target envelope/ConnectionPlayer path.
  // Keep a regression for the old factory boundary: valid map entry still rejects.
  npc.id=1024;GameClient.IDToPlayerMap.put(npc.id,npc);GameServer.server=true;nativeOut=new PlayerHitPacket();nativeOut.setData(npc,weapon,List.of(new CombatManager.TargetHitList(actor,List.of(hit(.1f)))),List.of());buf=ByteBuffer.allocate(8192);nativeOut.write(new ByteBufferWriter(buf));GameServer.server=false;nativeOut=new PlayerHitPacket();nativeOut.parse(new ByteBufferReader(buf.flip()),connection);check(!nativeOut.isConsistent(connection));npc.id=1023;GameClient.IDToPlayerMap.put(npc.id,npc);GameServer.server=true;nativeOut=new PlayerHitPacket();nativeOut.setData(npc,weapon,List.of(new CombatManager.TargetHitList(actor,List.of(hit(.1f)))),List.of());buf=ByteBuffer.allocate(8192);nativeOut.write(new ByteBufferWriter(buf));GameServer.server=false;nativeOut=new PlayerHitPacket();nativeOut.parse(new ByteBufferReader(buf.flip()),connection);check(nativeOut.isConsistent(connection));
  // Human -> NPC relays keep a normal outer player ID and remain consistent.
  GameServer.server=true;nativeOut=new PlayerHitPacket();nativeOut.setData(actor,weapon,List.of(new CombatManager.TargetHitList(npc,List.of(hit(.1f)))),List.of());buf=ByteBuffer.allocate(8192);nativeOut.write(new ByteBufferWriter(buf));GameServer.server=false;nativeOut=new PlayerHitPacket();nativeOut.parse(new ByteBufferReader(buf.flip()),connection);check(nativeOut.isConsistent(connection));nativeOut.processClient(connection);check(npc.hitCalls==1&&human.speech.attacks==0&&human.state.damageCommands==0);

  // Ordinary player hits remain native and retain connectionless-player bookkeeping.
  setup();var regular=new IsoPlayer();regular.id=2;GameServer.IDToPlayerMap.put(regular.id,regular);dispatch(envelope(regular,List.of(hit(1))));check(regular.hitCalls==1);check(GameServer.disconnects==disconnects+1);
  System.out.println("NPC combat / reserved identities candidate: "+checks+" assertions passed (native packets/Hit, engine doubles)");
 }
}'''


if __name__ == "__main__":
    main()
