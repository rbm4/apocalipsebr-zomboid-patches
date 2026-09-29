// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie;

import zombie.debug.DebugType;

public final class UnitTests {
    private static int unitTestsPassed = 0;
    private static int unitTestsFailed = 0;

    public static void runIfEnabled() {
        unitTestsPassed = 0;
        unitTestsFailed = 0;
        if (isEnabled()) {
            DebugType.UnitTest.debugln(" ----------------- Running UnitTests -----------------");
            run();
            if (unitTestsFailed > 0) {
                DebugType.UnitTest.error(" ---------------- UnitTests Run Report ---------------");
                DebugType.UnitTest.error(" Total : %d", unitTestsPassed + unitTestsFailed);
                DebugType.UnitTest.error(" Passed: %d", unitTestsPassed);
                DebugType.UnitTest.error(" FAILED: %d", unitTestsFailed);
                DebugType.UnitTest.error(" --------------- UnitTests Run complete. -------------");
            } else {
                DebugType.UnitTest.debugln(" --------------- UnitTests Run complete. -------------");
            }
        }
    }

    private static boolean isEnabled() {
        return DebugType.UnitTest.isEnabled();
    }

    private static void run() {
        ZomboidFileSystem.UnitTests.run();
    }

    public static void unitTestPassed() {
        unitTestsPassed++;
    }

    public static void unitTestFailed() {
        unitTestsFailed++;
    }
}
