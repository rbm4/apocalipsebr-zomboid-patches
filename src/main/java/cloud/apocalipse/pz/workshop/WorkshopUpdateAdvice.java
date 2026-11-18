package cloud.apocalipse.pz.workshop;

import net.bytebuddy.asm.Advice;

/** Inlined into ItemQueryJava.inform; leaves the original body and cleanup intact. */
public final class WorkshopUpdateAdvice {
    private WorkshopUpdateAdvice() {}

    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(0) String message) {
        if ("CheckModsNeedUpdate: Mods need update".equals(message)) {
            try {
                ModUpdateRestartNotifier.onUpdateDetected();
            } catch (RuntimeException | LinkageError failure) {
                // Never expose request details or prevent vanilla query cleanup.
                System.err.println("[ApocBR-Agent] Unable to submit mod-update restart notification.");
            }
        }
    }
}
