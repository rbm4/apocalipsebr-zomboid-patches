"""Compile the production power-bank subclass with engine doubles and exercise its lifecycle."""
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "42.22.2/src/zombie"
JAVAC = ROOT / "jdk/bin/javac.exe"
JAVA = ROOT / "jdk/bin/java.exe"

STUBS = {
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
            public static boolean TriggerHook(String name,Object bank,Object hour) {
                calls++;
                if(callback!=null) callback.accept(bank,(Double)hour);
                return callback!=null;
            }
        }""",
    "zombie/iso/objects/IsoGenerator.java": """package zombie.iso.objects;
        public class IsoGenerator {
            public float fuel;
            public int condition;
            public boolean connected,activated;
            public int syncs,foodUpdates,electricityUpdates;
            public boolean inWorld=true;
            public static class Data extends java.util.HashMap<String,Object> {
                public Object rawget(String key) { return get(key); }
                public void rawset(String key,Object value) { put(key,value); }
            }
            private final Data data=new Data();
            public IsoGenerator(zombie.iso.IsoCell cell) {}
            public Data getModData() { return data; }
            public Object getSquare() { return inWorld ? this : null; }
            public int getObjectIndex() { return inWorld ? 0 : -1; }
            public void update() { throw new AssertionError("Gasoline update invoked"); }
            public void load(java.nio.ByteBuffer input,int version,boolean debug) throws java.io.IOException { data.clear(); }
            public void setActivated(boolean value) { activated=value; }
            public void syncIsoObjectReceive(zombie.core.network.ByteBufferReader input,zombie.core.raknet.UdpConnection source) {}
            protected final void updateElectricityIfNeeded() {}
            protected final void updateFridgeFreezerItems() { foodUpdates++; }
            public void setSurroundingElectricity() { electricityUpdates++; }
            public void flagForHotSave() {}
            public void sync() { syncs++; }
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
        System.out.println("Production IsoPowerBank lifecycle fixture passed");
    }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-powerbank-") as directory:
        work = Path(directory)
        sources = []
        for relative, content in STUBS.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(str(path))
        harness = work / "PowerBankFixture.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources += [str(harness), str(TARGET / "apocbr/power/IsoPowerBank.java")]
        subprocess.run([str(JAVAC), "-d", str(work), *sources], check=True)
        subprocess.run([str(JAVA), "-cp", str(work), "PowerBankFixture"], check=True)


if __name__ == "__main__":
    main()
