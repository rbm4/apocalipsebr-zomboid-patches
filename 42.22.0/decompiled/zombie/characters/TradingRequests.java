// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.characters;

public class TradingRequests extends Requests {
    private static final TradingRequests INSTANCE = new TradingRequests();

    private TradingRequests() {
    }

    public static TradingRequests getInstance() {
        return INSTANCE;
    }
}
