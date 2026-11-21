package cloud.apocalipse.pz.agent.integration.assertions;

import cloud.apocalipse.pz.agent.integration.ServerAssertion;
import cloud.apocalipse.pz.agent.integration.ServerTestContext;

/** Generic inventory guard; individual hook tests are generated from runtime module declarations. */
public final class HookInstallationAssertion implements ServerAssertion {
    @Override public String name() { return "Transformed classes have declared installation checks"; }

    @Override public void verify(ServerTestContext server) throws Exception {
        server.assertInventoryCoverage();
    }
}
