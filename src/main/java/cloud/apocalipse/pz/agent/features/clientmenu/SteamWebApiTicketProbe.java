package cloud.apocalipse.pz.agent.features.clientmenu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/** Java bridge for Steam Web API tickets, driven by the vanilla Steam callback pump. */
public final class SteamWebApiTicketProbe {
    private static final int CALLBACK_ID = 100 + 68;
    private static final int CALLBACK_BYTES = 12 + 2_560;
    private static final String IDENTITY = "apocalipsebr-auth";
    private static final long TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();
    private static final AtomicReference<String> STATUS = new AtomicReference<>("NOT_STARTED");
    private static volatile Pending active;
    private static volatile Thread steamThread;

    private SteamWebApiTicketProbe() {}

    /**
     * Result delivered on the Steam callback-pump thread. Ticket bytes are copied from native callback
     * memory and belong to the caller, which should discard them after use.
     */
    public record TicketResult(boolean successful, int steamResult, byte[] ticket) {
        public TicketResult {
            ticket = ticket == null ? new byte[0] : ticket.clone();
        }

        @Override public byte[] ticket() { return ticket.clone(); }
    }

    public static String status() { return STATUS.get(); }

    static void steamUnavailable() {
        STATUS.compareAndSet("NOT_STARTED", "STEAM_UNAVAILABLE");
    }

    static void start() {
        steamThread = Thread.currentThread();
        STATUS.set("STARTING");
        if (!requestTicket(IDENTITY, result -> {
            STATUS.set(result.successful() ? "SUCCEEDED" : "FAILED");
            DebugLog.log(result.successful()
                ? "[ApocBR][ClientMenu] Steam Web API ticket probe succeeded; ticket discarded"
                : "[ApocBR][ClientMenu] Steam Web API ticket probe failed; result=" + result.steamResult());
        })) {
            STATUS.set("NATIVE_BRIDGE_UNAVAILABLE");
        } else STATUS.set("WAITING_FOR_CALLBACK");
    }

    /**
     * Requests a Web API ticket. Call from the thread that invokes SteamUtils.runLoop(); the completion
     * runs on that same thread after Steam dispatches the asynchronous callback. Only one ticket may be
     * pending because this bridge owns one native callback object.
     *
     * @return true if Steam accepted the request for callback delivery
     */
    public static synchronized boolean requestTicket(String identity, Consumer<TicketResult> completion) {
        if (GameServer.server || !SteamUtils.isSteamModeEnabled() || active != null
                || identity == null || identity.isBlank() || completion == null
                || steamThread == null || Thread.currentThread() != steamThread) return false;
        Pending request = null;
        try {
            request = new Pending(identity, completion);
            active = request;
            request.register();
            int handle = (int) request.requestTicket();
            request.handle = handle;
            if (handle == 0) {
                finish(request, new TicketResult(false, -1, new byte[0]));
                return false;
            }
            return true;
        } catch (Throwable failure) {
            if (request != null) finish(request, new TicketResult(false, -1, new byte[0]));
            DebugLog.log("[ApocBR][ClientMenu] Steam Web API ticket request could not start: " + failure);
            return false;
        }
    }

    /** Runs after SteamUtils.runLoop returns, so native callback dispatch has unwound before freeing memory. */
    static void afterSteamLoop() {
        Pending request = active;
        if (request == null) return;
        if (request.completed) {
            boolean success = request.result == 1 && request.ticketLength > 0 && request.ticket != null;
            finish(request, new TicketResult(success, request.result, success ? request.ticket : new byte[0]));
        } else if (System.nanoTime() - request.startedNanos >= TIMEOUT_NANOS) {
            finish(request, new TicketResult(false, -1, new byte[0]));
        }
    }

    static synchronized void shutdown() {
        Pending request = active;
        if (request != null) finish(request, new TicketResult(false, -1, new byte[0]));
        steamThread = null;
    }

    private static synchronized void finish(Pending request, TicketResult result) {
        if (active != request) return;
        active = null;
        try { request.unregisterAndCancel(); }
        catch (Throwable ignored) { /* Shutdown and probe diagnostics must not affect vanilla Steam teardown. */ }
        request.close();
        try { request.completion.accept(result); }
        catch (Throwable failure) { DebugLog.log("[ApocBR][ClientMenu] Steam ticket completion failed: " + failure); }
    }

    private static void callbackRun(MemorySegment self, MemorySegment data) {
        try {
            Pending request = active;
            if (request == null || request.callbackObject.address() != self.address() || data.address() == 0) return;
            MemorySegment payload = data.reinterpret(CALLBACK_BYTES);
            int callbackHandle = payload.get(ValueLayout.JAVA_INT, 0);
            int result = payload.get(ValueLayout.JAVA_INT, 4);
            int ticketLength = payload.get(ValueLayout.JAVA_INT, 8);
            if (callbackHandle != request.handle) return;
            request.result = result;
            request.ticketLength = Math.max(0, Math.min(ticketLength, 2_560));
            if (result == 1 && request.ticketLength > 0) {
                request.ticket = payload.asSlice(12, request.ticketLength).toArray(ValueLayout.JAVA_BYTE);
            }
            request.completed = true;
        } catch (Throwable failure) {
            STATUS.set("CALLBACK_FAILED");
            Pending request = active;
            if (request != null) {
                request.result = -1;
                request.ticketLength = 0;
                request.completed = true;
            }
        }
    }

    private static void callbackRunCallResult(MemorySegment self, MemorySegment data, boolean ioFailure, long call) {
        callbackRun(self, data);
    }

    private static int callbackSize(MemorySegment self) { return CALLBACK_BYTES; }

    private static final class Pending {
        final Linker linker = Linker.nativeLinker();
        final Arena arena = Arena.ofShared();
        final long startedNanos = System.nanoTime();
        final MemorySegment user;
        final MemorySegment callbackObject;
        final MemorySegment vtable;
        final MemorySegment callbackRunStub;
        final MemorySegment callbackResultStub;
        final MemorySegment callbackSizeStub;
        final String identity;
        final Consumer<TicketResult> completion;
        final MethodHandle registerCallback;
        final MethodHandle unregisterCallback;
        final MethodHandle getAuthTicketForWebApi;
        final MethodHandle cancelAuthTicket;
        volatile int handle;
        volatile int result = -1;
        volatile int ticketLength;
        volatile boolean completed;
        volatile boolean registered;
        volatile byte[] ticket;

        Pending(String identity, Consumer<TicketResult> completion) throws Throwable {
            this.identity = identity;
            this.completion = completion;
            SymbolLookup symbols = SymbolLookup.loaderLookup();
            MemorySegment userAccessor = symbol(symbols, "SteamAPI_SteamUser_v023");
            MemorySegment userPointer = (MemorySegment) this.linker.downcallHandle(userAccessor,
                FunctionDescriptor.of(ValueLayout.ADDRESS)).invokeExact();
            if (userPointer.address() == 0) throw new IllegalStateException("SteamUser interface unavailable");
            this.user = userPointer;
            this.registerCallback = downcall(this.linker, symbols, "SteamAPI_RegisterCallback",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            this.unregisterCallback = downcall(this.linker, symbols, "SteamAPI_UnregisterCallback",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            this.getAuthTicketForWebApi = downcall(this.linker, symbols, "SteamAPI_ISteamUser_GetAuthTicketForWebApi",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            this.cancelAuthTicket = downcall(this.linker, symbols, "SteamAPI_ISteamUser_CancelAuthTicket",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            this.callbackRunStub = this.linker.upcallStub(
                lookup.findStatic(SteamWebApiTicketProbe.class, "callbackRun",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), this.arena);
            this.callbackResultStub = this.linker.upcallStub(
                lookup.findStatic(SteamWebApiTicketProbe.class, "callbackRunCallResult",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                        boolean.class, long.class)),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_LONG), this.arena);
            this.callbackSizeStub = this.linker.upcallStub(
                lookup.findStatic(SteamWebApiTicketProbe.class, "callbackSize",
                    MethodType.methodType(int.class, MemorySegment.class)),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), this.arena);

            this.vtable = this.arena.allocate(3 * ValueLayout.ADDRESS.byteSize(), ValueLayout.ADDRESS.byteAlignment());
            this.vtable.set(ValueLayout.ADDRESS, 0, this.callbackRunStub);
            this.vtable.set(ValueLayout.ADDRESS, ValueLayout.ADDRESS.byteSize(), this.callbackResultStub);
            this.vtable.set(ValueLayout.ADDRESS, 2L * ValueLayout.ADDRESS.byteSize(), this.callbackSizeStub);
            this.callbackObject = this.arena.allocate(16, 8);
            this.callbackObject.set(ValueLayout.ADDRESS, 0, this.vtable);
            this.callbackObject.set(ValueLayout.JAVA_BYTE, 8, (byte) 0);
            this.callbackObject.set(ValueLayout.JAVA_INT, 12, CALLBACK_ID);
        }

        void register() throws Throwable {
            this.registerCallback.invokeExact(this.callbackObject, CALLBACK_ID);
            this.registered = true;
        }

        int requestTicket() throws Throwable {
            MemorySegment identityAddress = this.arena.allocateFrom(this.identity);
            return (int) this.getAuthTicketForWebApi.invokeExact(this.user, identityAddress);
        }

        void unregisterAndCancel() throws Throwable {
            if (this.registered) this.unregisterCallback.invokeExact(this.callbackObject);
            this.registered = false;
            if (this.handle != 0) this.cancelAuthTicket.invokeExact(this.user, this.handle);
        }

        void close() {
            if (this.arena.scope().isAlive()) this.arena.close();
        }
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(symbol(symbols, name), descriptor);
    }

    private static MemorySegment symbol(SymbolLookup symbols, String name) {
        Optional<MemorySegment> address = symbols.find(name);
        return address.orElseThrow(() -> new IllegalStateException("Missing Steamworks export: " + name));
    }
}
