package cloud.apocalipse.pz.agent.integration;

/** Register a public no-argument implementation in the test ServiceLoader file. */
public interface ServerAssertion {
    String name();

    /** Runs sequentially against the shared server; leave it usable for later assertions. */
    void verify(ServerTestContext server) throws Exception;
}
