"""Compile the production power-bank subclass with engine doubles and exercise its lifecycle."""
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "42.22.2/src/zombie"
JAVAC = ROOT / "jdk/bin/javac.exe"
JAVA = ROOT / "jdk/bin/java.exe"

STUBS = {
    "zombie/Lua/LuaManager.java": "package zombie.Lua; public class LuaManager { public static final Platform platform=new Platform(); public static class Platform { public se.krka.kahlua.vm.KahluaTable newTable() { return new zombie.iso.objects.IsoGenerator.Data(); } } }",
    "se/krka/kahlua/vm/KahluaTable.java": "package se.krka.kahlua.vm; public interface KahluaTable { Object rawget(Object key); void rawset(Object key,Object value); }",
    "zombie/network/ApocBRManagedGenerators.java": "package zombie.network; public class ApocBRManagedGenerators { public static boolean isTagged(zombie.iso.objects.IsoGenerator gen) { return gen.tagged; } public static boolean denyChange(Object gen,String action) { return false; } }",
    "zombie/network/GameClient.java": "package zombie.network; public class GameClient { public static boolean client; }",
    "zombie/core/logger/ExceptionLogger.java": "package zombie.core.logger; public class ExceptionLogger { public static void logException(Throwable error) {} }",
    "zombie/debug/DebugLog.java": "package zombie.debug; public class DebugLog { public static void log(String text) {} }",
    "zombie/UsedFromLua.java": "package zombie; public @interface UsedFromLua {}",
    "zombie/GameTime.java": """package zombie;
        public class GameTime {
            public static final GameTime instance = new GameTime();
            public double hour;
            public static GameTime getInstance() { return instance; }
            public double getWorldAgeHours() { return hour; }
        }""",
    "zombie/network/GameServer.java": "package zombie.network; public class GameServer { public static boolean server=true; }",
    "zombie/iso/IsoCell.java": "package zombie.iso; public class IsoCell {}",
    "zombie/iso/sprite/IsoSpriteManager.java": """package zombie.iso.sprite;
        public class IsoSpriteManager { public static final IsoSpriteManager instance=new IsoSpriteManager(); }""",
    "zombie/iso/sprite/IsoSprite.java": """package zombie.iso.sprite;
        public class IsoSprite {
            private final String name;
            public IsoSprite(String name) { this.name=name; }
            public String getName() { return name; }
            public static IsoSprite getSprite(IsoSpriteManager manager,int id) {
                return id==575 ? new IsoSprite("solarmod_tileset_01_0") : null;
            }
        }""",
    "zombie/core/raknet/UdpConnection.java": "package zombie.core.raknet; public class UdpConnection {}",
    "zombie/core/network/ByteBufferReader.java": """package zombie.core.network;
        public class ByteBufferReader {
            public int fields;
            public float getFloat() { fields++; return 10; }
            public int getInt() { fields++; return 100; }
            public boolean getBoolean() { fields++; return true; }
        }""",
    "zombie/Lua/LuaEventManager.java": "package zombie.Lua; public class LuaEventManager { public static void triggerEvent(String name) {} }",
    "zombie/Lua/LuaHookManager.java": """package zombie.Lua;
        public class LuaHookManager {
            public static java.util.function.BiConsumer<Object,Double> callback;
            public static int calls;
            public static boolean TriggerHook(String name,Object... args) {
                calls++;
                if(callback!=null) callback.accept(args[0],(Double)args[args.length-1]);
                return callback!=null;
            }
        }""",
    "zombie/iso/objects/IsoGenerator.java": """package zombie.iso.objects;
        import zombie.GameTime;
        import zombie.network.GameClient;
        import zombie.network.GameServer;
        import zombie.Lua.LuaEventManager;
        import zombie.core.logger.ExceptionLogger;
        import zombie.core.network.ByteBufferReader;
        import zombie.core.raknet.UdpConnection;
        public class IsoGenerator {
            public float fuel;
            public int condition;
            public boolean connected,activated,tagged;
            public int clockResets;
            private int lastHour;
            private boolean isaApplyingBackupState;
            private se.krka.kahlua.vm.KahluaTable isaBackupOwner;
            public int syncs,foodUpdates,electricityUpdates;
            public boolean inWorld=true;
            public static class Data extends java.util.HashMap<Object,Object> implements se.krka.kahlua.vm.KahluaTable {
                public Object rawget(Object key) { return get(key); }
                public void rawset(Object key,Object value) { put(key,value); }
            }
            private final Data data=new Data();
            public IsoGenerator(zombie.iso.IsoCell cell) {}
            public Data getModData() { return data; }
            public Object getSquare() { return inWorld ? this : null; }
            public int getObjectIndex() { return inWorld ? 0 : -1; }
            public void update() { throw new AssertionError("Gasoline update invoked"); }
            public void load(java.nio.ByteBuffer input,int version,boolean debug) throws java.io.IOException { data.clear(); }
            public void setActivated(boolean value) { if(isISABackupActivationAllowed(value)) activated=value; else sync(); }
            public void syncIsoObjectReceive(zombie.core.network.ByteBufferReader input,zombie.core.raknet.UdpConnection source) {}
            protected final void updateElectricityIfNeeded() {}
            protected final void updateFridgeFreezerItems() { foodUpdates++; }
            public void setSurroundingElectricity() { electricityUpdates++; }
            public void flagForHotSave() {}
            public void sync() { syncs++; }
            public void transmitModData() {}
            public float getFuel() { return fuel; }
            public int getCondition() { return condition; }
            public void playGeneratorSound(String name) {}
            public void stopAllSounds() {}
            public void resetISABackupClock() { clockResets++; }
        }""",
}

HARNESS = """import java.nio.ByteBuffer;
import zombie.GameTime;
import zombie.Lua.LuaHookManager;
import zombie.apocbr.power.IsoPowerBank;
import zombie.core.network.ByteBufferReader;
import zombie.iso.IsoCell;
import zombie.network.GameServer;

public class PowerBankFixture {
    static void check(boolean value) { if(!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        ByteBuffer bytes=ByteBuffer.allocate(8).putInt(575).putInt(123);
        bytes.flip();
        check(IsoPowerBank.isPowerBankPayload(bytes));
        check(bytes.position()==0);
        bytes.putInt(0,9);
        check(!IsoPowerBank.isPowerBankPayload(bytes));
        check(!IsoPowerBank.isPowerBankPayload(ByteBuffer.allocate(0)));
        GameServer.server=false;
        bytes.putInt(0,575);
        check(!IsoPowerBank.isPowerBankPayload(bytes));
        GameServer.server=true;

        IsoPowerBank bank=new IsoPowerBank(new IsoCell());
        check(Boolean.TRUE.equals(bank.getModData().rawget(IsoPowerBank.MARKER)));
        bank.load(bytes,251,false);
        check(Boolean.TRUE.equals(bank.getModData().rawget(IsoPowerBank.MARKER)));
        LuaHookManager.callback=(object,hour)-> {
            IsoPowerBank current=(IsoPowerBank)object;
            current.update(); // A callback cannot reenter simulation.
            current.applyPowerState(true,0.5);
            current.completeUpdate(hour,1.0/6);
        };
        bank.update();
        check(LuaHookManager.calls==1 && bank.activated && bank.fuel==5);
        check(bank.foodUpdates==1 && bank.electricityUpdates==1 && bank.syncs==1);
        bank.update();
        check(LuaHookManager.calls==1);
        GameTime.instance.hour=1.0/6;
        bank.update();
        check(LuaHookManager.calls==2 && bank.syncs==1);
        bank.requestSimulationUpdate(); bank.update();
        check(LuaHookManager.calls==3);

        ByteBufferReader packet=new ByteBufferReader();
        bank.syncIsoObjectReceive(packet,null);
        check(packet.fields==4 && bank.fuel==5 && bank.activated);
        bank.inWorld=false; bank.requestSimulationUpdate(); bank.update();
        check(LuaHookManager.calls==3);
        bank.inWorld=true;
        LuaHookManager.callback=null;
        bank.update();
        check(!bank.activated && LuaHookManager.calls==4);
        bank.update(); check(LuaHookManager.calls==4);

        LuaHookManager.callback=(object,hour)-> { throw new IllegalStateException("fixture"); };
        bank.requestSimulationUpdate();
        try { bank.update(); throw new AssertionError(); } catch(IllegalStateException expected) {}
        LuaHookManager.callback=(object,hour)->((IsoPowerBank)object).completeUpdate(hour,1);
        bank.requestSimulationUpdate(); bank.update();
        check(LuaHookManager.calls==6);
        try { bank.completeUpdate(0,1); throw new AssertionError(); } catch(IllegalStateException expected) {}
        // Deferred scheduling keeps supply and cannot be dispatched again per frame.
        LuaHookManager.callback=(object,hour)->((IsoPowerBank)object).deferUpdate();
        bank.applyPowerState(true,0.5);
        bank.requestSimulationUpdate(); bank.update();
        int calls=LuaHookManager.calls;
        GameTime.instance.hour=3;
        bank.update(); check(LuaHookManager.calls==calls && bank.activated);
        bank.prepareSimulationUpdate(); bank.checkpointUpdate(2);
        bank.update(); check(LuaHookManager.calls==calls);
        bank.completeUpdate(3,1);
        bank.update(); check(LuaHookManager.calls==calls);
        bank.failScheduledUpdate(); check(!bank.activated);

        zombie.apocbr.power.PowerBankBackup.enable();
        zombie.iso.objects.IsoGenerator source=new zombie.iso.objects.IsoGenerator(new IsoCell());
        source.connected=true;
        source.activated=true; source.fuel=5; source.condition=90;
        zombie.iso.objects.IsoGenerator.Data owner=new zombie.iso.objects.IsoGenerator.Data();
        owner.rawset("x",1.0); owner.rawset("y",2.0); owner.rawset("z",0.0);
        check(zombie.apocbr.power.PowerBankBackup.claim(source,owner));
        check(zombie.apocbr.power.PowerBankBackup.isControlled(source) && !source.activated);
        owner.rawset("x",9.0);
        check(source.getISABackupOwner().rawget("x").equals(1.0));
        owner.rawset("x",1.0);
        source.applyISABackupState(2.5,80,true);
        check(source.fuel==2.5 && source.condition==80 && source.activated);
        check(zombie.apocbr.power.PowerBankBackup.claim(source,owner) && source.activated);
        source.setActivated(false); check(source.activated);
        source.getModData().rawset(zombie.apocbr.power.PowerBankBackup.OWNER,null);
        source.setActivated(false); check(source.activated); // Generic ModData cannot release ownership.
        source.setConnected(false); check(source.connected);
        source.condition=0; source.setActivated(false); check(!source.activated);
        source.applyISABackupState(0,80,true);
        check(source.fuel==0 && !source.activated);
        source.setActivated(true); check(!source.activated);
        source.applyISABackupState(2.5,80,false);
        ByteBufferReader sourcePacket=new ByteBufferReader();
        source.syncIsoObjectReceive(sourcePacket,null);
        check(sourcePacket.fields==4 && source.fuel==2.5 && source.condition==80 && !source.activated);
        check(zombie.apocbr.power.PowerBankBackup.claim(source,owner));
        zombie.iso.objects.IsoGenerator.Data other=new zombie.iso.objects.IsoGenerator.Data();
        other.putAll(owner); other.rawset("x",9.0);
        check(!zombie.apocbr.power.PowerBankBackup.claim(source,other));
        check(!zombie.apocbr.power.PowerBankBackup.claim(bank,owner));
        source.tagged=true; check(!zombie.apocbr.power.PowerBankBackup.isControlled(source));
        source.tagged=false;
        zombie.apocbr.power.PowerBankBackup.release(source);
        check(!zombie.apocbr.power.PowerBankBackup.isControlled(source) && source.clockResets==1 && !source.activated);
        source.setActivated(true); check(source.activated);
        source.setConnected(false); check(!source.connected);
        source.syncIsoObjectReceive(new ByteBufferReader(),null);
        check(source.activated && source.fuel==10 && source.condition==100);
        zombie.iso.objects.IsoGenerator ordinary=new zombie.iso.objects.IsoGenerator(new IsoCell());
        ordinary.getModData().rawset(zombie.apocbr.power.PowerBankBackup.OWNER,owner);
        check(!zombie.apocbr.power.PowerBankBackup.isControlled(ordinary));
        owner.rawset("x",Double.NaN);
        check(!zombie.apocbr.power.PowerBankBackup.claim(source,owner));
        System.out.println("Production IsoPowerBank lifecycle fixture passed");
    }
}
"""


def main():
    generator_source=(TARGET / 'iso/objects/IsoGenerator.java').read_text(encoding='utf-8')
    begin=generator_source.index('    public void applyISABackupState(')
    end=generator_source.index('    public void resetISABackupClock(',begin)
    apply_method=generator_source[begin:end]
    begin=generator_source.index('    public boolean isISABackupActivationAllowed(')
    guard_method=generator_source[begin:generator_source.index('    public void setActivated(',begin)]
    begin=generator_source.index('    public void setConnected(')
    connection_method=generator_source[begin:generator_source.index('    @Override',begin)]
    begin=generator_source.index('    public void syncIsoObjectReceive(')
    receive_method=generator_source[begin:generator_source.index('    private boolean touchesChunk(',begin)]
    begin=generator_source.index('    public se.krka.kahlua.vm.KahluaTable getISABackupOwner(')
    ownership_methods=generator_source[begin:generator_source.index('    public boolean isActivated(',begin)]
    with tempfile.TemporaryDirectory(prefix="apocbr-powerbank-") as directory:
        work = Path(directory)
        sources = []
        for relative, content in STUBS.items():
            if relative=='zombie/iso/objects/IsoGenerator.java':
                content=content.replace('public void resetISABackupClock()',apply_method+'\npublic void resetISABackupClock()')
                content=content.replace('public void resetISABackupClock()',ownership_methods+guard_method+connection_method+'\npublic void resetISABackupClock()')
                content=content.replace('public void syncIsoObjectReceive(zombie.core.network.ByteBufferReader input,zombie.core.raknet.UdpConnection source) {}',receive_method)
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(str(path))
        harness = work / "PowerBankFixture.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources += [str(harness), str(TARGET / "apocbr/power/IsoPowerBank.java"), str(TARGET / "apocbr/power/PowerBankBackup.java")]
        subprocess.run([str(JAVAC), "-d", str(work), *sources], check=True)
        subprocess.run([str(JAVA), "-cp", str(work), "PowerBankFixture"], check=True)


if __name__ == "__main__":
    main()
