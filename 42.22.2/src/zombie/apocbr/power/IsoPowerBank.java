package zombie.apocbr.power;

import java.nio.ByteBuffer;
import java.io.IOException;
import zombie.GameTime;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaHookManager;
import zombie.UsedFromLua;
import zombie.core.logger.ExceptionLogger;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.objects.IsoGenerator;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.network.GameServer;

/** Server simulation with the native IsoGenerator save and network representation. */
@UsedFromLua
public final class IsoPowerBank extends IsoGenerator {
    public static final String MARKER = "ApocBRPowerBank";
    public static final String UPDATE_HOOK = "OnPowerBankUpdate";
    private static final String SIMULATION_HOUR = "ISA_simulationHour";
    private static final double RETRY_HOURS = 1.0 / 60.0;
    private double nextUpdateHour = Double.NEGATIVE_INFINITY;
    private boolean updating;
    private boolean acknowledged;
    private boolean callbackWarning;

    public IsoPowerBank(IsoCell cell) {
        super(cell);
        getModData().rawset(MARKER, Boolean.TRUE);
    }

    @Override
    public void load(ByteBuffer input, int worldVersion, boolean debug) throws IOException {
        super.load(input, worldVersion, debug);
        getModData().rawset(MARKER, Boolean.TRUE);
    }

    /** The native payload begins with the sprite ID after its class discriminator. */
    public static boolean isPowerBankPayload(ByteBuffer input) {
        if (!GameServer.server || input.remaining() < Integer.BYTES) return false;
        IsoSprite sprite = IsoSprite.getSprite(IsoSpriteManager.instance, input.getInt(input.position()));
        return sprite != null && "solarmod_tileset_01_0".equals(sprite.getName());
    }

    @Override
    public void update() {
        if (!GameServer.server || getSquare() == null || getObjectIndex() < 0 || updating) return;
        updateElectricityIfNeeded();
        double now = GameTime.getInstance().getWorldAgeHours();
        if (now >= nextUpdateHour) {
            updating = true;
            acknowledged = false;
            nextUpdateHour = now + RETRY_HOURS;
            try {
                LuaHookManager.TriggerHook(UPDATE_HOOK, this, now);
            } finally {
                updating = false;
                if (!acknowledged) {
                    if (!callbackWarning) {
                        DebugLog.log("[IsoPowerBank] OnPowerBankUpdate did not acknowledge; suspending supply until recovery");
                        callbackWarning = true;
                    }
                    setActivated(false);
                } else {
                    callbackWarning = false;
                }
            }
        }
    }

    /** A successful Lua callback acknowledges its processed game-time boundary. */
    public void completeUpdate(double processedHour, double intervalHours) {
        if (!GameServer.server || !updating || !Double.isFinite(processedHour)
            || !Double.isFinite(intervalHours) || intervalHours <= 0 || intervalHours > 24) {
            throw new IllegalStateException("Invalid power-bank simulation acknowledgement");
        }
        double now = GameTime.getInstance().getWorldAgeHours();
        Object previous = getModData().rawget(SIMULATION_HOUR);
        if (processedHour > now || previous instanceof Double hour && processedHour < hour) {
            throw new IllegalArgumentException("Power-bank simulation time must advance monotonically");
        }
        getModData().rawset(SIMULATION_HOUR, processedHour);
        nextUpdateHour = processedHour + intervalHours;
        acknowledged = true;
        flagForHotSave();
    }

    public void requestSimulationUpdate() {
        nextUpdateHour = Double.NEGATIVE_INFINITY;
    }

    /** Energy is projected onto the native ten-litre fuel range for stock clients. */
    public void applyPowerState(boolean supply, double chargeFraction) {
        if (!GameServer.server || !Double.isFinite(chargeFraction)) {
            throw new IllegalArgumentException("Finite server power-bank charge required");
        }
        float nextFuel = (float)(10.0 * Math.max(0, Math.min(1, chargeFraction)));
        boolean nextSupply = supply && nextFuel > 0;
        boolean changed = fuel != nextFuel || condition != 100 || !connected;
        boolean activationChanged = activated != nextSupply;
        fuel = nextFuel;
        condition = 100;
        connected = true;
        setActivated(nextSupply);
        if (changed && !activationChanged) {
            flagForHotSave();
            sync();
        }
    }

    @Override
    public void setActivated(boolean value) {
        if (activated == value) return;
        // Age loaded refrigerator contents under the preceding supply state first.
        try {
            updateFridgeFreezerItems();
        } catch (Throwable error) {
            ExceptionLogger.logException(error);
        }
        activated = value;
        setSurroundingElectricity();
        flagForHotSave();
        LuaEventManager.triggerEvent("OnElectricityChanged");
        if (GameServer.server) sync();
    }

    /** Clients can request actions through ISA; native sync cannot assert bank energy. */
    @Override
    public void syncIsoObjectReceive(ByteBufferReader input, UdpConnection source) {
        input.getFloat();
        input.getInt();
        input.getBoolean();
        input.getBoolean();
        if (GameServer.server) sync();
    }
}
