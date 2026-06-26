// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.vehicleNetworkSound.client;

import gnu.trove.map.hash.TShortObjectHashMap;
import zombie.scripting.ScriptManager;
import zombie.vehicleNetworkSound.SharedVehicleState;

public final class Manager {
    private static Manager instance;
    private final TShortObjectHashMap<VehicleState> stateMap = new TShortObjectHashMap<>();

    public static Manager getInstance() {
        if (instance == null) {
            instance = new Manager();
        }

        return instance;
    }

    public void addVehicle(short id, String scriptName) {
        VehicleState state = this.createState(id);
        state.scriptName = scriptName;
        state.setScript(ScriptManager.instance.getVehicle(scriptName));
    }

    public void updateVehicle(SharedVehicleState state1) {
        VehicleState state = this.getState(state1.id);
        if (state != null) {
            state.set(state1);
        }
    }

    public void updateVehicle(SharedVehicleState state1, int changeBits) {
        VehicleState state = this.getState(state1.id);
        if (state != null) {
            state.set(state1, changeBits);
        }
    }

    public void removeVehicle(short id) {
        VehicleState state = this.stateMap.remove(id);
        if (state != null) {
            state.remove();
        }
    }

    VehicleState createState(short id) {
        VehicleState state = new VehicleState(id);
        this.stateMap.put(state.id, state);
        return state;
    }

    VehicleState getState(short id) {
        return this.stateMap.get(id);
    }

    public void update() {
        this.stateMap.forEachValue(state -> {
            state.update();
            return true;
        });
    }

    public void stop() {
        this.stateMap.forEachValue(state -> {
            state.remove();
            return true;
        });
        this.stateMap.clear();
    }
}
