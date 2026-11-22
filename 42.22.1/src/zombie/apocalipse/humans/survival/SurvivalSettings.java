package zombie.apocalipse.humans.survival;

/** Immutable profile data. Fractions are logical vitals, distances are tiles, durations milliseconds. */
public record SurvivalSettings(int crowdCount, int crowdRadius, double retreatHealth, double reengageHealth,
        double retreatEndurance, double reengageEndurance, int retreatDistance, int safeDistance,
        int safeHoldMs, int minimumRetreatMs, int retryMs, int routeTimeoutMs) {
    public static final SurvivalSettings DEFAULT = new SurvivalSettings(4,4,.35,.55,.24,.72,12,6,5000,8000,2000,14000);
    public SurvivalSettings {
        if(crowdCount<2||crowdCount>32||crowdRadius<1||crowdRadius>16||retreatDistance<2||retreatDistance>24
            ||safeDistance<crowdRadius||safeDistance>24||safeHoldMs<1000||safeHoldMs>60000
            ||minimumRetreatMs<1000||minimumRetreatMs>120000||retryMs<500||retryMs>30000
            ||routeTimeoutMs<1000||routeTimeoutMs>60000)throw new IllegalArgumentException("Invalid survival limits");
        fractions(retreatHealth,reengageHealth);fractions(retreatEndurance,reengageEndurance);
    }
    private static void fractions(double low,double high){
        if(!Double.isFinite(low)||!Double.isFinite(high)||low<0||low>=high||high>1)
            throw new IllegalArgumentException("Recovery threshold must exceed retreat threshold");
    }
}
