package cloud.apocalipse.pz.agent.features.hotfixes;

import net.bytebuddy.asm.Advice;

/** Rewrites the resolved PZ XML filename before the vanilla internal file open. */
public final class XmlCaseAdvice {
    private XmlCaseAdvice() {
    }

    public static final class ParseInternal {
        private ParseInternal() {
        }

        @Advice.OnMethodEnter
        public static void enter(@Advice.Argument(value = 0, readOnly = false) String fileName) {
            fileName = XmlCaseResolver.resolve(fileName);
        }
    }
}
