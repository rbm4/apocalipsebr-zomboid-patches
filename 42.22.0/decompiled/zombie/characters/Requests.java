// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.characters;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public abstract class Requests {
    private final Map<String, Set<String>> requests = new HashMap<>();

    public void request(IsoPlayer requester, IsoPlayer responder) {
        this.requests.computeIfAbsent(requester.getUsername(), username -> new HashSet<>()).add(responder.getUsername());
    }

    public boolean response(IsoPlayer requester, IsoPlayer responder) {
        Set<String> responders = this.requests.get(requester.getUsername());
        return responders != null && responders.remove(responder.getUsername());
    }

    public void remove(IsoPlayer player) {
        this.requests.remove(player.getUsername());
        this.requests.values().forEach(responders -> responders.remove(player.getUsername()));
    }
}
