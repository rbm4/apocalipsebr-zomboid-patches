package zombie.apocalipse.zombies;

import zombie.apocalipse.zombies.ZombieVictimPort.Victim;

/** One incarnation and one target. Timers never replay attacks after a stalled server tick. */
final class ServerZombieSession {
    enum Phase { PURSUIT, WINDUP, RECOVERY }
    final ServerZombieNativeAdapter nativeBody;
    final Victim identity;
    Phase phase = Phase.PURSUIT;
    long lastUpdate, phaseUntil, nextRoute, attack;
    long lastSeen, progressAt;
    float progressX, progressY;
    boolean committed;
    String result = "pursuit";

    ServerZombieSession(ServerZombieNativeAdapter nativeBody, Victim target, long now) {
        this.nativeBody = nativeBody; identity = target;
        lastUpdate = lastSeen = progressAt = now;
        progressX = nativeBody.x(); progressY = nativeBody.y();
    }

    String update(Victim victim, ZombieVictimPort victims, long now) {
        float seconds = (float)Math.clamp((now-lastUpdate)/1_000_000_000.0, 0, .15);
        lastUpdate = now;
        if (victim == null || victim.body() != identity.body()) return "victim_lost";
        if (!nativeBody.valid()) return "body_removed_or_dead";
        if (nativeBody.interrupted()) return "native_reaction";
        if (nativeBody.playerTakesPriority(victim)) return "player_priority";
        if (nativeBody.distance(victim) > 24 || nativeBody.z() != victim.z()) return "out_of_scope";
        if (nativeBody.visible(victim)) lastSeen = now;
        if (now-lastSeen > 5_000_000_000L) return "target_memory_expired";
        nativeBody.target(victim);

        if (phase == Phase.RECOVERY) {
            nativeBody.present(false, false);
            if (now >= phaseUntil) phase = Phase.PURSUIT;
            return null;
        }
        if (phase == Phase.WINDUP) {
            nativeBody.present(false, true);
            // A late tick must not turn a long-finished animation into a delayed bite.
            if (!committed && now >= phaseUntil) {
                committed = true;
                result = now-phaseUntil > 750_000_000L ? "strike_expired"
                    : nativeBody.contact(victim) ? victims.strike(victim, nativeBody.zombie, attack) : "contact_lost";
                nativeBody.diagnostic("strike", result);
                phase = Phase.RECOVERY;
                phaseUntil = now + 900_000_000L;
                nativeBody.present(false, false);
            }
            return null;
        }
        if (nativeBody.contact(victim)) {
            phase = Phase.WINDUP; committed = false; attack++;
            phaseUntil = now + 550_000_000L;
            nativeBody.present(false, true);
            nativeBody.diagnostic("windup", "contact");
            return null;
        }
        if (now >= nextRoute) {
            nextRoute = now + 750_000_000L;
            if (!nativeBody.route(victim)) return "route_unavailable";
        }
        boolean moved = nativeBody.move(victim, seconds);
        nativeBody.present(moved, false);
        float dx=nativeBody.x()-progressX, dy=nativeBody.y()-progressY;
        if (dx*dx+dy*dy >= .04F) {
            progressX=nativeBody.x(); progressY=nativeBody.y(); progressAt=now;
        }
        if (now-progressAt >= 2_500_000_000L) return "pursuit_stalled";
        return null;
    }
}
