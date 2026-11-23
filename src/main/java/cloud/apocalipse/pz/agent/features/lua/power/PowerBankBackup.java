package cloud.apocalipse.pz.agent.features.lua.power;

import java.util.Map;
import java.util.WeakHashMap;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.Lua.LuaHookManager;
import zombie.Lua.LuaManager;
import zombie.iso.objects.IsoGenerator;
import zombie.network.GameServer;
import cloud.apocalipse.pz.agent.features.lua.streaming.ApocBRManagedGenerators;

/** Agent-owned backup ownership. Lua-only instance bindings delegate here without adding native members. */
public final class PowerBankBackup {
    public static final String HOOK = "OnPowerBankBackupUpdate";
    public static final String OWNER = "ApocBRISABackupOwner";
    private static final Map<IsoGenerator, State> STATES = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean enabled;
    private static Thread owner;
    private static final class State {
        KahluaTable coordinates;
        double next = Double.NEGATIVE_INFINITY;
        boolean applying;
    }
    private PowerBankBackup() { }
    public static void enable() { guard(); enabled=true; }
    private static void guard() {
        if (!GameServer.server || Thread.currentThread()!=owner) throw new IllegalStateException("Server thread required");
    }
    public static void initialize() { owner=Thread.currentThread(); enabled=false; STATES.clear(); }
    public static void shutdown() { STATES.clear(); enabled=false; owner=null; }
    public static boolean isControlled(IsoGenerator source) {
        return enabled && GameServer.server && Thread.currentThread()==owner && STATES.containsKey(source)
            && !(source instanceof IsoPowerBank) && !ApocBRManagedGenerators.isTagged(source);
    }
    public static KahluaTable getISABackupOwner(IsoGenerator source) {
        guard(); State s=STATES.get(source); return s==null?null:copy(s.coordinates);
    }
    private static KahluaTable copy(KahluaTable input) {
        if (input==null) return null;
        KahluaTable result=LuaManager.platform.newTable();
        for (String key:new String[]{"x","y","z"}) {
            Object value=input.rawget(key);
            if (!(value instanceof Double d) || !Double.isFinite(d) || d!=Math.rint(d)) return null;
            result.rawset(key,value);
        }
        return result;
    }
    public static boolean claim(IsoGenerator source,KahluaTable coordinates) {
        guard(); KahluaTable next=copy(coordinates);
        if (!enabled || next==null || source instanceof IsoPowerBank || ApocBRManagedGenerators.isTagged(source)) return false;
        State previous=STATES.get(source);
        if (previous!=null) {
            for (String key:new String[]{"x","y","z"})
                if (!java.util.Objects.equals(previous.coordinates.rawget(key),next.rawget(key))) return false;
            return true;
        }
        State state=new State(); state.coordinates=next; STATES.put(source,state);
        source.getModData().rawset(OWNER,copy(next)); stop(source);
        source.flagForHotSave(); source.transmitModData(); return true;
    }
    public static void loaded(IsoGenerator source) {
        if (!GameServer.server) return;
        // Native deserialization owns this object, including on a chunk worker. Publish a detached copy.
        STATES.remove(source);
        Object saved=source.getModData().rawget(OWNER);
        if (saved instanceof KahluaTable table) {
            KahluaTable coordinates=copy(table);
            if (coordinates!=null) { State s=new State(); s.coordinates=coordinates; STATES.put(source,s); }
        }
    }
    public static void saving(IsoGenerator source) {
        if (!GameServer.server) return;
        State s=STATES.get(source);
        source.getModData().rawset(OWNER,s==null?null:copy(s.coordinates));
    }
    public static void resetISABackupClock(IsoGenerator source) {
        guard(); GeneratorAccess.resetHour(source);State state=STATES.get(source);
        if(state!=null)state.next=Double.NEGATIVE_INFINITY;
    }
    public static boolean setISABackupOwner(IsoGenerator source,KahluaTable coordinates) {
        guard();
        KahluaTable next=copy(coordinates);
        STATES.remove(source);
        if(next!=null){State state=new State();state.coordinates=next;STATES.put(source,state);}
        source.getModData().rawset(OWNER,copy(next));
        return coordinates==null||next!=null;
    }
    public static boolean isISABackupActivationAllowed(IsoGenerator source,boolean value) {
        guard();State state=STATES.get(source);
        return !isControlled(source)||state.applying||!value&&(source.fuel<=0||source.condition<=0);
    }
    public static void release(IsoGenerator source) {
        guard(); stop(source); STATES.remove(source); source.getModData().rawset(OWNER,null);
        GeneratorAccess.resetHour(source); source.flagForHotSave(); source.transmitModData();
    }
    public static void stop(IsoGenerator source) {
        guard();
        if (isControlled(source)) applyISABackupState(source,source.getFuel(),source.getCondition(),false);
        else source.setActivated(false);
    }
    public static void applyISABackupState(IsoGenerator source,double fuel,int condition,boolean supply) {
        guard();
        if (!isControlled(source) || !Double.isFinite(fuel)) throw new IllegalStateException("Unowned ISA backup");
        State s=STATES.get(source); float next=(float)Math.clamp(fuel,0,10); int health=Math.clamp(condition,0,100);
        boolean changed=source.fuel!=next || source.condition!=health;
        source.fuel=next; source.condition=health; GeneratorAccess.resetHour(source);
        boolean active=supply && next>0 && health>0;
        boolean activationChanged=source.activated!=active;
        s.applying=true;
        try { source.setActivated(active); } finally { s.applying=false; }
        source.flagForHotSave(); if (changed && !activationChanged) source.sync();
    }
    public static boolean denyActivation(IsoGenerator source,boolean value) {
        if (!isControlled(source)) return false;
        State s=STATES.get(source);
        if (s.applying || !value && (source.fuel<=0 || source.condition<=0)) return false;
        source.sync(); return true;
    }
    public static void beforeUpdate(IsoGenerator source) {
        if (!isControlled(source)) return;
        State s=STATES.get(source); double now=GameTime.getInstance().getWorldAgeHours();
        if (now>=s.next) {
            s.next=now+1.0/6.0; saving(source);
            LuaHookManager.TriggerHook(HOOK,source,copy(s.coordinates),now);
        }
    }
}
