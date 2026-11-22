package cloud.apocalipse.pz.agent.features.postgres;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Inlined hook bodies call public agent helpers; no new members are added to signed game classes. */
public final class PostgresAdvice {
    private PostgresAdvice() { }

    public static final class AccountCall {
        @Advice.OnMethodEnter public static PostgresRuntime.Scope enter() {
            return PostgresRuntime.enter();
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(
                @Advice.Enter PostgresRuntime.Scope scope, @Advice.Thrown(readOnly=false) Throwable thrown) {
            thrown = PostgresRuntime.finish(scope, thrown);
        }
    }

    public static final class Authentication {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Local("postgresScope") PostgresRuntime.Scope scope) {
            scope = PostgresRuntime.enterAuthentication();
            return scope == null;
        }
        @Advice.OnMethodExit(onThrowable=Throwable.class)
        public static void exit(@Advice.This Object owner, @Advice.Local("postgresScope") PostgresRuntime.Scope scope,
                @Advice.Return(readOnly=false,typing=Assigner.Typing.DYNAMIC) Object result,
                @Advice.Thrown(readOnly=false) Throwable thrown) {
            Throwable failure = PostgresRuntime.finish(scope, thrown);
            if (scope == null || failure != thrown) {
                result = PostgresRuntime.deny(owner, result);
            }
            // Preserve unrelated native exceptions. SQL failures caught by vanilla are represented by a denied result.
        }
    }

    public static final class Bootstrap {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object owner) { PostgresRuntime.bootstrap(owner); return true; }
    }
    public static final class Connect {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter() { PostgresRuntime.connect(); return true; }
    }
    public static final class Close {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter() { return true; }
    }
    public static final class NewAccount {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter() { return true; }
        @Advice.OnMethodExit public static void exit(@Advice.This Object owner, @Advice.Argument(0) String username,
                @Advice.Argument(2) long steamId, @Advice.Return(readOnly=false) boolean allowed) {
            allowed = PostgresRuntime.newAccountAllowed(owner, username, steamId);
        }
    }
    public static final class ServerLifecycle {
        @Advice.OnMethodEnter public static void enter(@Advice.Origin Class<?> server) {
            PostgresRuntime.verifyAccountHooks(server.getClassLoader());
        }
    }
    public static final class Shutdown {
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Thrown(readOnly=false) Throwable thrown) {
            thrown=PostgresRuntime.shutdownAfter(thrown);
        }
    }
    public static final class GracefulQuit {
        @Advice.OnMethodEnter public static ForegroundDatabase.GracefulQuit enter() {
            return ForegroundDatabase.gracefulQuit();
        }
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(
                @Advice.Enter ForegroundDatabase.GracefulQuit scope) {
            scope.close();
        }
    }
}
