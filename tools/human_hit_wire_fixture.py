"""Native 42.21.0 serialization methods used by the player-hit regression fixture.

World/animation effects remain stubbed; field ordering and variable payloads are
read from the exact decompiled serializers rather than recreated by the test.
"""
from pathlib import Path


def method(root, name, signature):
    source = (root / '42.21.0/decompiled/zombie' / name).read_text()
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def configure(fixture, root):
    common = 'import zombie.AttackType;import zombie.core.network.*;import zombie.network.*;import zombie.network.fields.hit.*;import zombie.characters.*;import java.util.*;'
    for name in ['IDShort', 'character/PlayerID', 'character/CharacterID']:
        relative = 'zombie/network/fields/' + name + '.java'
        fixture[relative] = (root / '42.21.0/decompiled' / relative).read_text()
    for kind, body, getter in [('Zombie', 'zombie.characters.IsoZombie', 'getZombie'), ('Animal', 'zombie.characters.animals.IsoAnimal', 'getAnimal')]:
        fixture[f'zombie/network/fields/character/{kind}ID.java'] = f'''package zombie.network.fields.character;
public class {kind}ID extends zombie.network.fields.IDShort {{public void set({body} p){{}}public {body} {getter}(){{return null;}}
public float getX(){{return 0;}}public float getY(){{return 0;}}public float getZ(){{return 0;}}}}'''
    fixture['zombie/characters/animals/IsoAnimal.java'] = 'package zombie.characters.animals;public abstract class IsoAnimal extends zombie.characters.IsoPlayer {public IsoAnimal(){super(null,null,0,0,0);}}'
    fixture['zombie/network/IConnection.java'] = 'package zombie.network;public interface IConnection {}'
    fixture['zombie/core/raknet/UdpConnection.java'] = fixture['zombie/core/raknet/UdpConnection.java'].replace('public class UdpConnection {', 'public class UdpConnection implements zombie.network.IConnection {')
    fixture['zombie/network/GameClient.java'] = 'package zombie.network;public class GameClient {public static boolean client;public static final java.util.Map<Short,zombie.characters.IsoPlayer> IDToPlayerMap=new java.util.HashMap<>();}'
    fixture['zombie/network/GameServer.java'] = fixture['zombie/network/GameServer.java'].replace('public static String serverName', 'public static IsoPlayer getPlayerFromConnection(IConnection c,int index){return ((UdpConnection)c).getPlayerAt(index);}public static String serverName')
    fixture['zombie/characters/IsoPlayer.java'] = fixture['zombie/characters/IsoPlayer.java'].replace('public float useChargeDelta;', 'public boolean isLocal(){return !remote;}public int getIndex(){return playerIndex;}public static IsoPlayer getPlayer(int n){return null;}public float useChargeDelta;')
    fixture['zombie/core/network/ByteBufferReader.java'] = '''package zombie.core.network;public class ByteBufferReader {
public final java.nio.ByteBuffer bb;public ByteBufferReader(java.nio.ByteBuffer b){bb=b;}public byte getByte(){return bb.get();}public short getShort(){return bb.getShort();}
public int getInt(){return bb.getInt();}public float getFloat(){return bb.getFloat();}public boolean getBoolean(){return bb.get()!=0;}
public String getUTF(){int n=Short.toUnsignedInt(bb.getShort());byte[] v=new byte[n];bb.get(v);return new String(v,java.nio.charset.StandardCharsets.UTF_8);}
public <T extends Enum<T>>T getEnum(Class<T> c){return c.getEnumConstants()[getByte()];}}
'''
    fixture['zombie/core/network/ByteBufferWriter.java'] = '''package zombie.core.network;public class ByteBufferWriter {
public final java.nio.ByteBuffer bb;public ByteBufferWriter(){this(java.nio.ByteBuffer.allocate(65536));}public ByteBufferWriter(java.nio.ByteBuffer b){bb=b;}
public void putByte(byte v){bb.put(v);}public void putByte(int v){bb.put((byte)v);}public void putShort(short v){bb.putShort(v);}public void putInt(int v){bb.putInt(v);}
public void putFloat(float v){bb.putFloat(v);}public void putBoolean(boolean v){bb.put((byte)(v?1:0));}
public void putUTF(String v){byte[] b=v.getBytes(java.nio.charset.StandardCharsets.UTF_8);bb.putShort((short)b.length);bb.put(b);}
public void putEnum(Enum<?> v){putByte(v.ordinal());}}
'''
    # Character and Player retain their native inheritance/identity lookup.
    character = '''package zombie.network.fields.hit;''' + common + '''
public class WireCharacter extends zombie.network.fields.character.CharacterID {
protected short characterFlags;protected float positionX,positionY,positionZ,directionX,directionY;
protected String characterReaction="",playerReaction="",zombieReaction="";
'''
    for signature in ['public void parse(ByteBufferReader b, IConnection connection)', 'public void write(ByteBufferWriter b)']:
        character += method(root, 'network/fields/hit/Character.java', signature)
    fixture['zombie/network/fields/hit/WireCharacter.java'] = character + '}'
    player = fixture['zombie/network/fields/hit/Player.java'].replace('public class Player {', 'public class Player extends WireCharacter {')
    player = player.replace('package zombie.network.fields.hit;', 'package zombie.network.fields.hit;' + common)
    player = player.replace('public zombie.characters.IsoGameCharacter getCharacter(){return player;}', '')
    fixture['zombie/characters/IsoPlayer.java'] = fixture['zombie/characters/IsoPlayer.java'].replace('public short id;', 'public short onlineId;public short id;')
    player = player.replace('player=p;id=p.getOnlineID();', 'p.onlineId=p.getOnlineID();super.set(p);player=p;id=p.getOnlineID();')
    player = player.replace('public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){}', '')
    player = player.replace('public void write(zombie.core.network.ByteBufferWriter b){}', '')
    extra = '''protected short playerIndex,playerFlags;protected float charge,perkAiming,combatSpeed;protected zombie.AttackType attackType=zombie.AttackType.SHOT;
protected WireAttackVars attackVars=new WireAttackVars();private final List<HitInfo> hitList=new ArrayList<>();'''
    parse = method(root, 'network/fields/hit/Player.java', 'public void parse(ByteBufferReader b, IConnection connection)')
    parse = parse[:-1] + 'player=(IsoPlayer)getCharacter();id=super.getID();}'
    extra += parse + method(root, 'network/fields/hit/Player.java', 'public void write(ByteBufferWriter b)')
    fixture['zombie/network/fields/hit/Player.java'] = player[:-1] + extra + '}'
    # Variable attack lists are empty in this wire case, but their native flags,
    # ground-object field and recoil payload must still be consumed in order.
    attack = 'package zombie.network.fields.hit;' + common + '''public class WireAttackVars {
private boolean isBareHeadsWeapon;public boolean aimAtFloor,closeKill,doShove,doGrapple;public float useChargeDelta;public int recoilDelay;
public List<HitInfo> targetsStanding=new ArrayList<>(),targetsProne=new ArrayList<>();public EmptyObject targetOnGround=new EmptyObject();
'''
    attack += 'public static class EmptyObject {private byte objectType,squareZ;private short objectId;private int squareX,squareY;private boolean isProcessed;'
    for signature in ['public void parse(ByteBufferReader b, IConnection connection)', 'public void write(ByteBufferWriter b)']:
        attack += method(root, 'network/fields/MovingObject.java', signature)
    attack += '}'
    native = (root / '42.21.0/decompiled/zombie/network/fields/hit/AttackVars.java').read_text()
    enum = native[native.index('public static class AttackFlags'):]
    attack += enum[:enum.rfind('}')]
    for signature in ['public void parse(ByteBufferReader b, IConnection connection)', 'public void write(ByteBufferWriter b)']:
        attack += method(root, 'network/fields/hit/AttackVars.java', signature).replace('AttackVars.AttackFlags', 'WireAttackVars.AttackFlags')
    fixture['zombie/network/fields/hit/WireAttackVars.java'] = attack + '}'
    fixture['zombie/network/fields/hit/HitInfo.java'] = fixture['zombie/network/fields/hit/HitInfo.java'].replace('public class HitInfo {', 'public class HitInfo {public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){throw new AssertionError("unexpected hit list");}public void write(zombie.core.network.ByteBufferWriter b){throw new AssertionError("unexpected hit list");}')
    # Exercise the native tracer and Fall layouts, including optional tracer data.
    for name, fields in [('Fall', 'public float dropPositionX,dropPositionY,dropDirection;public byte dropPositionZ;'),
                         ('TracerInfo', 'public boolean hasEndPosition,hasSquare;public float range,endX,endY,endZ;private int squareX,squareY;private byte squareZ;')]:
        value = 'package zombie.network.fields.hit;' + common + 'public class ' + name + '{' + fields
        if name == 'Fall':
            value += 'public void set(Object ai){}public void set(float x,float y,byte z,float d){dropPositionX=x;dropPositionY=y;dropPositionZ=z;dropDirection=d;}public void process(Object target){}'
        for signature in ['public void parse(ByteBufferReader b, IConnection connection)', 'public void write(ByteBufferWriter b)']:
            value += method(root, 'network/fields/hit/' + name + '.java', signature)
        fixture['zombie/network/fields/hit/' + name + '.java'] = value + '}'
    # Native hit data is 4 floats followed by range, body-part byte and two booleans.
    hit = fixture['zombie/network/fields/hit/WeaponHit.java'].replace('public float damage;', 'public float damage,hitForce,hitDirectionX,hitDirectionY,range;public byte hitPart;public boolean removeKnife,ignoreDamage;').replace('return ignored;', 'return ignoreDamage||ignored;')
    for signature in ['public void parse(ByteBufferReader b, IConnection connection)', 'public void write(ByteBufferWriter b)']:
        base = method(root, 'network/fields/hit/Hit.java', signature)
        child = method(root, 'network/fields/hit/WeaponHit.java', signature)
        child = child.replace('super.parse(b, connection);', base[base.index('{')+1:-1]).replace('super.write(b);', base[base.index('{')+1:-1])
        hit = hit[:-1] + child.replace('ByteBufferReader', 'zombie.core.network.ByteBufferReader').replace('ByteBufferWriter', 'zombie.core.network.ByteBufferWriter').replace('IConnection', 'zombie.network.IConnection') + '}'
    fixture['zombie/network/fields/hit/WeaponHit.java'] = hit
    parent = fixture['zombie/network/packets/hit/PlayerHitCharacter.java']
    parent = parent.replace('import java.util.*;', 'import zombie.core.network.*;import zombie.network.*;import java.util.*;')
    parent = parent.replace('public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection c){}', '')
    parent = parent.replace('public void write(zombie.core.network.ByteBufferWriter b){}', '')
    parent = parent.replace('protected HandWeapon weapon;', 'protected HandWeapon weapon;protected List<TracerInfo> tracers=new ArrayList<>();protected WireWeapon wireWeapon=new WireWeapon();')
    parent = parent.replace('weapon=w;', 'weapon=w;wireWeapon.weapon=w;')
    parent = parent.replace('this.hits=new ArrayList<>(hits);', 'this.hits=new ArrayList<>(hits);this.tracers.clear();for(Object tracer:tracers)this.tracers.add((TracerInfo)tracer);')
    extra = '''public static class WireWeapon {public HandWeapon weapon;public void write(ByteBufferWriter b){b.putInt(weapon.getID());}
public void parse(ByteBufferReader b,IConnection c,IsoPlayer p){int id=b.getInt();weapon=p==null?null:(p.bareHands.getID()==id?p.bareHands:(HandWeapon)p.getInventory().getItemWithID(id));}}'''
    for signature in ['public void parse(ByteBufferReader b, IConnection connection)', 'public void write(ByteBufferWriter b)']:
        base = method(root, 'network/packets/hit/PlayerHit.java', signature).replace('this.weapon', 'this.wireWeapon').replace('(IsoLivingCharacter)', '(IsoPlayer)')
        child = method(root, 'network/packets/hit/PlayerHitCharacter.java', signature)
        child = child.replace('super.parse(b, connection);', base[base.index('{')+1:-1] + 'weapon=wireWeapon.weapon;').replace('super.write(b);', base[base.index('{')+1:-1])
        extra += child
    fixture['zombie/network/packets/hit/PlayerHitCharacter.java'] = parent[:-1] + extra + '}'
