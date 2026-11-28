package cloud.apocalipse.pz.agent.features.lua;

import cloud.apocalipse.pz.agent.features.lua.api.*;
import cloud.apocalipse.pz.agent.features.lua.entities.ApocBRModEntities;
import cloud.apocalipse.pz.agent.features.lua.events.ApocBREventControl;
import cloud.apocalipse.pz.agent.features.lua.login.ApocBRPlayerLogin;
import cloud.apocalipse.pz.agent.features.lua.power.*;
import cloud.apocalipse.pz.agent.features.lua.radio.*;
import cloud.apocalipse.pz.agent.features.lua.streaming.*;
import cloud.apocalipse.pz.agent.features.postgres.SuccessfulLoginJournal;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import zombie.Lua.*;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.network.ByteBufferReader;
import zombie.core.znet.SteamUtils;
import zombie.iso.*;
import zombie.iso.objects.IsoGenerator;
import zombie.network.*;

/** Public linkage facade invoked by advice. Native mutation and Lua stay on the server thread. */
public final class LuaRuntime {
    private static volatile Thread owner;
    private static volatile boolean started;
    private static volatile boolean closed;
    private LuaRuntime() { }
    public static void expose(Object object) {
        if (!GameServer.server) return;
        LuaManager.Exposer exposer=(LuaManager.Exposer)object;
        for(Class<?> type:new Class<?>[]{ApocBRLobbyStreaming.class,ApocBRManagedGenerators.class,ApocBREventControl.class,
                ApocBRRadioBridge.class,ApocBrApiBridge.class,ApocBrApiBridge.Endpoint.class,ApocBRPlayerLogin.class,
                IsoPowerBank.class,PowerBankBackup.class,ApocBRRoomEdits.class}) exposer.setExposed(type);
    }
    public static void luaInitialized() {
        if (!GameServer.server) return;
        if (owner == null)
            cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime.beforeShutdown(LuaRuntime::shutdown);
        owner=Thread.currentThread();
        PowerBankBackup.initialize(); ApocBRManagedGenerators.initialize(); ApocBRLobbyStreaming.initialize();
        LuaHookManager.AddEvent(IsoPowerBank.UPDATE_HOOK);
        LuaHookManager.AddEvent(PowerBankBackup.HOOK);
        LuaEventManager.AddEvent("OnApocBRLobbyEventDue");
    }
    public static void registerServerCommandEvent() {
        if (GameServer.server) {
            LuaEventManager.AddEvent(ServerCommand.EVENT);
        }
    }
    static boolean isServerLuaThread() {
        return GameServer.server && !closed && Thread.currentThread() == owner;
    }
    public static void exposed() {
        if(GameServer.server) PowerBankLuaBindings.install();
    }
    public static void start() {
        if (!GameServer.server || started || closed) return;
        if (Thread.currentThread()!=owner) throw new IllegalStateException("Lua startup owner mismatch");
        started=true;
        if(ApocBRPlayerLogin.enabled()) {
            String override=cloud.apocalipse.pz.agent.config.AgentConfiguration.systemProperty("apocbr.postgres.loginHistory.journalDirectory");
            Path directory=override==null ? Path.of(zombie.ZomboidFileSystem.instance.getSaveDir(),"Multiplayer",GameServer.serverName,"login-history-agent") : Path.of(override);
            SuccessfulLoginJournal.start(directory);
        }
        ApocBREventControl.start(); ApiBridgeLifecycle.initialize(); ApocBRBackendBridgeHooks.initialize();
    }
    public static void update() {
        if (!started || closed) return;
        if (Thread.currentThread()!=owner) throw new IllegalStateException("Lua update owner mismatch");
        ApocBREventControl.update(); ApiBridgeLifecycle.update(); ApocBRManagedGenerators.update();
    }
    public static void renewGeneratorPrewarms() {
        if (GameServer.server && started && !closed) ApocBRManagedGenerators.renewPrewarms();
    }
    public static synchronized void shutdown() {
        if (closed) return;
        closed=true;
        RuntimeException failure=null;
        Runnable[] services = started
            ? new Runnable[]{ApocBRModEntities::shutdown,ApiBridgeLifecycle::shutdown,ApocBREventControl::shutdown,
                ApocBRBackendBridgeHooks::shutdown,SuccessfulLoginJournal::shutdown}
            : new Runnable[]{ApocBRModEntities::shutdown};
        for(Runnable close:services) {
            try {close.run();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        }
        if(failure!=null)throw failure;
    }
    /** Run after vanilla saves; backup ownership must survive the final native serialization. */
    public static void cleanup() {
        ApocBRPlayerLogin.shutdown(); ApocBRManagedGenerators.shutdown(); PowerBankBackup.shutdown(); ApocBRLobbyStreaming.reset();
    }
    private static final class PopulationFields {
        static final java.lang.reflect.Field SERVER_CELLS;
        static {
            try{SERVER_CELLS=zombie.popman.LoadedAreas.class.getDeclaredField("serverCells");SERVER_CELLS.setAccessible(true);}
            catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
        }
    }
    public static void populationArea(Object object) {
        if (!GameServer.server) return;
        try {
            if (!PopulationFields.SERVER_CELLS.getBoolean(object)) ApocBRLobbyStreaming.appendPopulationArea((zombie.popman.LoadedAreas)object);
        }catch(IllegalAccessException failure){throw new IllegalStateException(failure);}
    }
    public static Object powerBank(Object cell,ByteBuffer input,Object result) {
        if(result instanceof IsoGenerator && IsoPowerBank.isPowerBankPayload(input)) return new IsoPowerBank((IsoCell)cell);
        return result;
    }
    public static boolean beforeGeneratorUpdate(Object object) {
        if(!GameServer.server) return false;
        IsoGenerator generator=(IsoGenerator)object;
        PowerBankBackup.beforeUpdate(generator);
        if(ApocBRManagedGenerators.isTagged(generator)) {
            ApocBRManagedGenerators.updateGenerator(generator);
            return !ApocBRManagedGenerators.isManagedGenerator(generator);
        }
        return false;
    }
    public static boolean skipGeneratorDepletion(Object object) {
        IsoGenerator generator=(IsoGenerator)object;
        return PowerBankBackup.isControlled(generator)||ApocBRManagedGenerators.isManagedGenerator(generator);
    }
    public static void generatorAdded(Object object) { ApocBRManagedGenerators.onAdded((IsoGenerator)object); }
    public static void generatorRemoved(Object object) { ApocBRManagedGenerators.onRemoved((IsoGenerator)object); }
    public static void generatorLoaded(Object object) { PowerBankBackup.loaded((IsoGenerator)object); }
    public static void generatorSaving(Object object) { PowerBankBackup.saving((IsoGenerator)object); }
    public static boolean denyChange(Object object,String operation,Object value) {
        IsoGenerator source=(IsoGenerator)object;
        if(!GameServer.server) return false;
        if(operation.equals("setActivated") && PowerBankBackup.denyActivation(source,(Boolean)value))return true;
        if(operation.equals("setConnected") && Boolean.FALSE.equals(value) && PowerBankBackup.isControlled(source)) PowerBankBackup.release(source);
        boolean changes=switch(operation){case "setFuel"->((Number)value).floatValue()!=10F;case "setCondition"->((Number)value).intValue()!=100;default->Boolean.FALSE.equals(value);};
        return changes && ApocBRManagedGenerators.denyChange(source,operation);
    }
    public static boolean denyRemoval(Object object) { return ApocBRManagedGenerators.denyRemoval((IsoObject)object); }
    public static boolean denyRemote(Object object,Object input) {
        IsoGenerator generator=(IsoGenerator)object;
        if(!GameServer.server || !(PowerBankBackup.isControlled(generator)||ApocBRManagedGenerators.isManagedGenerator(generator)))return false;
        ByteBufferReader reader=(ByteBufferReader)input;
        reader.getFloat();reader.getInt();reader.getBoolean();reader.getBoolean();generator.sync();return true;
    }
    public static void playerAccepted(Object object) {
        if(!GameServer.server) return;
        IConnection connection=(IConnection)object;
        for(int slot=0;slot<4;slot++) {
            IsoPlayer player=connection.getPlayerAt(slot);
            if(player==null||GameServer.IDToPlayerMap.get(player.getOnlineID())!=player)continue;
            boolean coop=GameServer.coop && SteamUtils.isSteamModeEnabled();
            ApocBRPlayerLogin.successfulLogin(player,Core.gameSaveWorld,coop?connection.getIDStr():connection.getUserName(),
                coop,slot,player.getUsername(),connection.getSteamId()>0?Long.toUnsignedString(connection.getSteamId()):null);
        }
    }
}
