package zombie.network;

/** Native option signatures used by the account-limit hook double. */
public final class ServerOptions {
    public static final ServerOptions instance=new ServerOptions();
    public final IntegerServerOption maxAccountsPerUser=new IntegerServerOption();
    public static final class IntegerServerOption {
        public int value=1;
        public int getValue() { return value; }
    }
}
