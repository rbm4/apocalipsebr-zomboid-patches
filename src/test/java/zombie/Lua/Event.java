package zombie.Lua;

import java.util.ArrayList;

/** Structural double for the packaged-agent telemetry smoke check; never packaged. */
public final class Event {
    public final ArrayList<Object> callbacks = new ArrayList<>();
    public String name = "OnTick";

    public boolean trigger(Object environment, Object caller, Object[] parameters) {
        return true;
    }
}
